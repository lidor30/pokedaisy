# Settings shared by setup.sh and deploy.sh. Override any of them in the environment.
# PROJECT and RELAY_HOST have no default: the Google Cloud project id and the relay's domain name.
: "${PROJECT:?set PROJECT to the Google Cloud project id}"
: "${RELAY_HOST:?set RELAY_HOST to the domain name of the relay, e.g. relay.example.com}"
# e2-micro in us-west1, us-central1 or us-east1 is in Google Cloud's free tier.
REGION=${REGION:-us-central1}
ZONE=${ZONE:-$REGION-a}
VM=${VM:-coop-relay}
MACHINE=${MACHINE:-e2-micro}
# The Artifact Registry repository holding the relay's images.
REPOSITORY=${REPOSITORY:-coop-relay}
IMAGE_BASE=$REGION-docker.pkg.dev/$PROJECT/$REPOSITORY/relay
