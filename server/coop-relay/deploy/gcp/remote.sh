#!/usr/bin/env bash
# Runs on the VM as root; deploy.sh copies it there with compose.yml and the Caddyfile. Puts them under
# /opt/coop-relay, pulls the new image with the VM's own service account and restarts what changed.
# Usage: remote.sh IMAGE RELAY_HOST SOURCE_DIR
set -euo pipefail
image=$1 host=$2 src=$3
dir=/opt/coop-relay

# The first deploy can come before the startup script has finished installing Docker.
for _ in $(seq 1 60); do
  docker compose version >/dev/null 2>&1 && docker info >/dev/null 2>&1 && break
  echo "waiting for Docker..."
  sleep 5
done
docker compose version >/dev/null
docker info >/dev/null

install -d -m 755 "$dir" "$dir/caddy"
install -m 644 "$src/compose.yml" "$dir/compose.yml"
install -m 644 "$src/Caddyfile" "$dir/caddy/Caddyfile"
printf 'RELAY_IMAGE=%s\nRELAY_HOST=%s\n' "$image" "$host" > "$dir/.env"

token=$(curl -fsS -H 'Metadata-Flavor: Google' \
  http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token |
  sed -E 's/.*"access_token" *: *"([^"]+)".*/\1/')
docker login -u oauth2accesstoken --password-stdin "https://${image%%/*}" <<<"$token" >/dev/null

cd "$dir"
docker compose pull --quiet
docker compose up -d --remove-orphans --wait --wait-timeout 120
# A changed Caddyfile alone doesn't restart Caddy.
docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile
docker image prune -af >/dev/null
rm -rf "$src"
docker compose ps
