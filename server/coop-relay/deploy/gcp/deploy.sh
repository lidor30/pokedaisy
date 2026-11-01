#!/usr/bin/env bash
# Builds the relay's image, pushes it to Artifact Registry and rolls it out on the VM setup.sh made, then waits for
# https://$RELAY_HOST/healthz. .github/workflows/coop-relay.yml runs it; it also runs by hand (gcloud signed in as
# a project owner, Docker running):
#   PROJECT=my-project RELAY_HOST=relay.example.com server/coop-relay/deploy/gcp/deploy.sh
# TAG overrides the image tag (default: the commit, "-dirty" with uncommitted relay changes).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
source "$here/config.sh"
relay=$(cd "$here/../.." && pwd)

if [ -z "${TAG:-}" ]; then
  TAG=$(git -C "$relay" rev-parse --short=12 HEAD)
  git -C "$relay" diff --quiet HEAD -- . || TAG=$TAG-dirty
fi
image=$IMAGE_BASE:$TAG

echo "== Building $image"
gcloud auth configure-docker "$REGION-docker.pkg.dev" --quiet >/dev/null 2>&1
docker build --platform linux/amd64 -t "$image" "$relay"
docker push "$image"

echo "== Rolling it out on $VM"
ssh_flags=(--project "$PROJECT" --zone "$ZONE" --tunnel-through-iap --quiet --strict-host-key-checking=no)
# In CI each run makes a new key: let OS Login forget it soon.
[ -n "${CI:-}" ] && ssh_flags+=(--ssh-key-expire-after=30m)
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
cp "$here/compose.yml" "$here/remote.sh" "$relay/Caddyfile" "$stage/"
dest=coop-relay-deploy-$TAG-$$
gcloud compute scp --recurse "$stage" "$VM:$dest" "${ssh_flags[@]}"
gcloud compute ssh "$VM" "${ssh_flags[@]}" \
  --command "sudo bash \"\$HOME/$dest/remote.sh\" '$image' '$RELAY_HOST' \"\$HOME/$dest\""

echo "== Waiting for https://$RELAY_HOST/healthz"
for _ in $(seq 1 36); do
  if [ "$(curl -fsS --max-time 5 "https://$RELAY_HOST/healthz" 2>/dev/null)" = ok ]; then
    echo "ok: $image is live"
    exit 0
  fi
  sleep 5
done
echo "https://$RELAY_HOST/healthz never said ok. Does $RELAY_HOST's A record point at the VM's address?" >&2
exit 1
