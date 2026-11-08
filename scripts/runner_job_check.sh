#!/bin/bash
# The ROM runner's job-started hook: refuses every job but the Release workflow on a v* tag the owner pushed.
#
# A personal repo can't limit which workflows use a self-hosted runner, and anyone with write access can push a
# branch whose workflow asks for it (runs-on: [self-hosted, pokedaisy-roms]) - it would run on the Mac that has
# the ROMs. The runner runs this before each job and fails the job when it exits non-zero, before any step runs.
#
# Install (docs/RELEASING.md, "The ROM runner"): copy it into the runner's own folder, not a checkout - a job
# could rewrite a checkout - and name it in the runner's .env, then restart the runner:
#   cp scripts/runner_job_check.sh ~/actions-runner/job_check.sh && chmod +x ~/actions-runner/job_check.sh
#   echo "ACTIONS_RUNNER_HOOK_JOB_STARTED=$HOME/actions-runner/job_check.sh" >> ~/actions-runner/.env
set -u

OWNER=lidor30
REPO=lidor30/pokedaisy

refuse() {
    echo "::error::This runner only takes the owner's releases: $1"
    exit 1
}

[ "${GITHUB_REPOSITORY:-}" = "$REPO" ] || refuse "repository ${GITHUB_REPOSITORY:-?}"
[ "${GITHUB_EVENT_NAME:-}" = push ] || refuse "event ${GITHUB_EVENT_NAME:-?}"
case "${GITHUB_REF:-}" in
    refs/tags/v*) ;;
    *) refuse "ref ${GITHUB_REF:-?}" ;;
esac
[ "${GITHUB_ACTOR:-}" = "$OWNER" ] || refuse "pushed by ${GITHUB_ACTOR:-?}"
# A re-run is started by whoever clicks it.
[ "${GITHUB_TRIGGERING_ACTOR:-$OWNER}" = "$OWNER" ] || refuse "run by ${GITHUB_TRIGGERING_ACTOR}"
case "${GITHUB_WORKFLOW_REF:-}" in
    "$REPO/.github/workflows/release.yml@refs/tags/v"*) ;;
    *) refuse "workflow ${GITHUB_WORKFLOW_REF:-?}" ;;
esac
exit 0
