#!/usr/bin/env bash
# One-time Google Cloud setup for the co-op relay; safe to run again (it skips what exists and re-applies the rest).
# Creates the Artifact Registry repository, the VM (Debian, Docker from its startup script) with a static address,
# the firewall rules (80 / 443 from anywhere, 22 from IAP only), a service account for the VM and one for GitHub
# Actions, and keyless sign-in for the repository's "coop-relay" environment (Workload Identity Federation). Then it
# creates that GitHub environment (main branch only) and sets the variables .github/workflows/coop-relay.yml reads.
#
# Needs gcloud signed in as a project owner and gh signed in to the repository:
#   PROJECT=my-project RELAY_HOST=relay.example.com server/coop-relay/deploy/gcp/setup.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
source "$here/config.sh"
GITHUB_REPO=${GITHUB_REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}
ENVIRONMENT=${ENVIRONMENT:-coop-relay}
POOL=${POOL:-github}
PROVIDER=${PROVIDER:-github}
ADDRESS=$VM-ip
TAG=coop-relay
VM_SA=coop-relay-vm@$PROJECT.iam.gserviceaccount.com
DEPLOY_SA=coop-relay-deploy@$PROJECT.iam.gserviceaccount.com

g() { gcloud --project "$PROJECT" --quiet "$@"; }
exists() { g "$@" >/dev/null 2>&1; }
# New service accounts take a few seconds to be usable in policies.
retry() { for i in 1 2 3 4 5 6; do "$@" >/dev/null && return; sleep 10; done; "$@" >/dev/null; }
step() { printf '\n== %s\n' "$*"; }

step "APIs"
g services enable compute.googleapis.com artifactregistry.googleapis.com iam.googleapis.com \
  iamcredentials.googleapis.com sts.googleapis.com iap.googleapis.com oslogin.googleapis.com

step "Artifact Registry repository $REPOSITORY ($REGION)"
exists artifacts repositories describe "$REPOSITORY" --location "$REGION" ||
  g artifacts repositories create "$REPOSITORY" --location "$REGION" --repository-format docker \
    --description "PokeDaisy co-op relay images"
policy=$(mktemp)
cat > "$policy" <<'JSON'
[
  {"name": "keep-recent", "action": {"type": "Keep"}, "mostRecentVersions": {"keepCount": 10}},
  {"name": "delete-old", "action": {"type": "Delete"}, "condition": {"tagState": "any", "olderThan": "7d"}}
]
JSON
g artifacts repositories set-cleanup-policies "$REPOSITORY" --location "$REGION" --policy "$policy" --no-dry-run >/dev/null
rm -f "$policy"

step "Service accounts"
exists iam service-accounts describe "$VM_SA" ||
  g iam service-accounts create coop-relay-vm --display-name "Co-op relay VM"
exists iam service-accounts describe "$DEPLOY_SA" ||
  g iam service-accounts create coop-relay-deploy --display-name "Co-op relay deploy (GitHub Actions)"
retry g artifacts repositories add-iam-policy-binding "$REPOSITORY" --location "$REGION" \
  --member "serviceAccount:$VM_SA" --role roles/artifactregistry.reader
retry g artifacts repositories add-iam-policy-binding "$REPOSITORY" --location "$REGION" \
  --member "serviceAccount:$DEPLOY_SA" --role roles/artifactregistry.writer
for role in roles/logging.logWriter roles/monitoring.metricWriter; do
  retry g projects add-iam-policy-binding "$PROJECT" --member "serviceAccount:$VM_SA" --role "$role" --condition None
done
# SSH through IAP with OS Login: find the VM, open the tunnel, log in as the VM's admin (sudo).
for role in roles/compute.viewer roles/iap.tunnelResourceAccessor; do
  retry g projects add-iam-policy-binding "$PROJECT" --member "serviceAccount:$DEPLOY_SA" --role "$role" --condition None
done
retry g iam service-accounts add-iam-policy-binding "$VM_SA" \
  --member "serviceAccount:$DEPLOY_SA" --role roles/iam.serviceAccountUser

step "Network"
exists compute networks describe default || g compute networks create default --subnet-mode auto
exists compute firewall-rules describe coop-relay-web ||
  g compute firewall-rules create coop-relay-web --network default --direction INGRESS \
    --allow tcp:80,tcp:443 --source-ranges 0.0.0.0/0 --target-tags "$TAG"
exists compute firewall-rules describe coop-relay-iap-ssh ||
  g compute firewall-rules create coop-relay-iap-ssh --network default --direction INGRESS \
    --allow tcp:22 --source-ranges 35.235.240.0/20 --target-tags "$TAG"
exists compute addresses describe "$ADDRESS" --region "$REGION" ||
  g compute addresses create "$ADDRESS" --region "$REGION"
IP=$(g compute addresses describe "$ADDRESS" --region "$REGION" --format 'value(address)')

step "VM $VM ($MACHINE, $ZONE)"
if exists compute instances describe "$VM" --zone "$ZONE"; then
  g compute instances add-metadata "$VM" --zone "$ZONE" --metadata enable-oslogin=TRUE \
    --metadata-from-file startup-script="$here/startup.sh"
else
  g compute instances create "$VM" --zone "$ZONE" --machine-type "$MACHINE" \
    --image-family debian-12 --image-project debian-cloud \
    --boot-disk-size 20GB --boot-disk-type pd-standard \
    --address "$IP" --tags "$TAG" --shielded-secure-boot \
    --service-account "$VM_SA" --scopes cloud-platform \
    --metadata enable-oslogin=TRUE --metadata-from-file startup-script="$here/startup.sh"
fi
retry g compute instances add-iam-policy-binding "$VM" --zone "$ZONE" \
  --member "serviceAccount:$DEPLOY_SA" --role roles/compute.osAdminLogin

step "Keyless sign-in for $GITHUB_REPO's $ENVIRONMENT environment"
NUMBER=$(g projects describe "$PROJECT" --format 'value(projectNumber)')
exists iam workload-identity-pools describe "$POOL" --location global ||
  g iam workload-identity-pools create "$POOL" --location global --display-name "GitHub Actions"
exists iam workload-identity-pools providers describe "$PROVIDER" --location global --workload-identity-pool "$POOL" ||
  g iam workload-identity-pools providers create-oidc "$PROVIDER" --location global --workload-identity-pool "$POOL" \
    --display-name "GitHub" --issuer-uri https://token.actions.githubusercontent.com \
    --attribute-mapping "google.subject=assertion.sub,attribute.repository=assertion.repository" \
    --attribute-condition "assertion.repository == '$GITHUB_REPO'"
# Only jobs running in the environment may act as the deploy account.
retry g iam service-accounts add-iam-policy-binding "$DEPLOY_SA" --role roles/iam.workloadIdentityUser \
  --member "principal://iam.googleapis.com/projects/$NUMBER/locations/global/workloadIdentityPools/$POOL/subject/repo:$GITHUB_REPO:environment:$ENVIRONMENT"

step "GitHub environment $ENVIRONMENT (main only) and its variables"
gh api -X PUT "repos/$GITHUB_REPO/environments/$ENVIRONMENT" \
  -F 'deployment_branch_policy[protected_branches]=false' \
  -F 'deployment_branch_policy[custom_branch_policies]=true' >/dev/null
gh api "repos/$GITHUB_REPO/environments/$ENVIRONMENT/deployment-branch-policies" \
  --jq '.branch_policies[].name' | grep -qx main ||
  gh api -X POST "repos/$GITHUB_REPO/environments/$ENVIRONMENT/deployment-branch-policies" -f name=main >/dev/null
setvar() { gh variable set "$1" --env "$ENVIRONMENT" --repo "$GITHUB_REPO" --body "$2"; }
setvar GCP_PROJECT "$PROJECT"
setvar GCP_REGION "$REGION"
setvar GCP_ZONE "$ZONE"
setvar RELAY_VM "$VM"
setvar RELAY_HOST "$RELAY_HOST"
setvar GCP_DEPLOY_SA "$DEPLOY_SA"
setvar GCP_WIF_PROVIDER "projects/$NUMBER/locations/global/workloadIdentityPools/$POOL/providers/$PROVIDER"

cat <<DONE

Done. Point a DNS A record for $RELAY_HOST at $IP (DNS only: no Cloudflare proxy, Caddy gets the
certificate itself). Then deploy: run the "Co-op relay" workflow on main, or right here:
  PROJECT=$PROJECT RELAY_HOST=$RELAY_HOST server/coop-relay/deploy/gcp/deploy.sh
DONE
