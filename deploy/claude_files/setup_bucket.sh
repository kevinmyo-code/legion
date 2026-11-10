#!/usr/bin/env bash
# One-time infra for files to phone (ADR 0080). Run by Kevin, once per project:
#
#     bash deploy/claude_files/setup_bucket.sh legion-engine-260906            # for real
#     bash deploy/claude_files/setup_bucket.sh legion-engine-260906 --dry-run  # print, run nothing
#
# The project comes from the first argument or GOOGLE_CLOUD_PROJECT. There is no default
# project, the same refusal `deploy/release/setup_bucket.sh` makes (CLAUDE.md section 7: nothing
# Kevin-hosted for strangers; a stranger's clone names its own).
#
# What it does, each step safe to re-run:
#   1. Enables Cloud Storage.
#   2. Creates the PRIVATE bucket gs://legion-claude-files-<project>: uniform bucket-level access
#      and public access prevention enforced, so no object in it can ever be made public. If the
#      bucket already exists it is updated to both settings instead.
#   3. Sets a lifecycle rule that deletes every object one day old. The engine deletes a file the
#      moment the phone has it, and its hourly `claude_files_prune` deletes the rest at 24 hours;
#      this rule is the backstop if either ever fails.
#   4. Gives the Cloud Run service account `roles/storage.objectAdmin` on that bucket only: the
#      engine writes a file when the laptop uploads it, reads it back for the phone, and deletes
#      it. Nothing else in the project. No signed URLs are made, so no signBlob grant is needed.
#
# The service account is read from the deployed service, falling back to the default compute
# account exactly as `deploy/release/setup_bucket.sh` does.
#
# After this, set LEGION_CLAUDE_FILES_BUCKET=legion-claude-files-<project> in
# deploy/cloudrun/.env and redeploy (`python deploy/cloudrun/deploy.py`). Until then delivered
# files wait in Postgres instead, deleted on the same two triggers.
set -euo pipefail

PROJECT=""
DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        -*) echo "Unknown option: $arg" >&2; exit 2 ;;
        *) PROJECT="$arg" ;;
    esac
done
PROJECT="${PROJECT:-${GOOGLE_CLOUD_PROJECT:-}}"
SERVICE="${CLOUD_RUN_SERVICE:-legion}"
REGION="${CLOUD_RUN_REGION:-us-south1}"

if [ -z "$PROJECT" ]; then
    echo "No project given and GOOGLE_CLOUD_PROJECT is not set. Nothing was created." >&2
    echo "Run: bash deploy/claude_files/setup_bucket.sh <project-id>" >&2
    exit 1
fi

BUCKET="gs://legion-claude-files-${PROJECT}"

run() {
    echo "\$ $*"
    if [ "$DRY_RUN" -eq 0 ]; then
        "$@"
    fi
}

echo "project   $PROJECT"
echo "service   $SERVICE ($REGION)"
echo "bucket    $BUCKET"
[ "$DRY_RUN" -eq 1 ] && echo "--dry-run: printing every command, running none of them."
echo

echo "1. Enabling Cloud Storage (no-op if already on) ..."
run gcloud services enable storage.googleapis.com --project "$PROJECT"

echo
echo "2. The private bucket ..."
if [ "$DRY_RUN" -eq 0 ] && gcloud storage buckets describe "$BUCKET" --project "$PROJECT" >/dev/null 2>&1; then
    echo "$BUCKET exists; enforcing uniform access and public access prevention on it."
    run gcloud storage buckets update "$BUCKET" --project "$PROJECT" \
        --uniform-bucket-level-access --public-access-prevention
else
    [ "$DRY_RUN" -eq 1 ] && echo "[plan] would describe $BUCKET first and update it instead if it exists:"
    run gcloud storage buckets create "$BUCKET" --project "$PROJECT" --location "$REGION" \
        --uniform-bucket-level-access --public-access-prevention
fi

echo
echo "3. Delete every object a day old (the backstop) ..."
LIFECYCLE="$(mktemp)"
trap 'rm -f "$LIFECYCLE"' EXIT
printf '%s\n' '{"rule": [{"action": {"type": "Delete"}, "condition": {"age": 1}}]}' > "$LIFECYCLE"
run gcloud storage buckets update "$BUCKET" --project "$PROJECT" --lifecycle-file "$LIFECYCLE"

echo
echo "4. The Cloud Run service account ..."
ACCOUNT=""
if [ "$DRY_RUN" -eq 0 ]; then
    ACCOUNT="$(gcloud run services describe "$SERVICE" --region "$REGION" --project "$PROJECT" \
        --format 'value(spec.template.spec.serviceAccountName)' 2>/dev/null || true)"
    if [ -z "$ACCOUNT" ]; then
        NUMBER="$(gcloud projects describe "$PROJECT" --format 'value(projectNumber)')"
        if [ -z "$NUMBER" ]; then
            echo "Could not resolve the project number for $PROJECT. The bucket exists; no access was granted." >&2
            exit 1
        fi
        ACCOUNT="${NUMBER}-compute@developer.gserviceaccount.com"
        echo "The service names no account of its own, so it runs as the default: $ACCOUNT"
    else
        echo "The service runs as $ACCOUNT"
    fi
else
    ACCOUNT="PROJECT_NUMBER-compute@developer.gserviceaccount.com"
    echo "[plan] would read it from \`gcloud run services describe $SERVICE\`, else build it from"
    echo "       \`gcloud projects describe $PROJECT --format value(projectNumber)\`; showing a placeholder."
fi

echo
echo "5. Write, read and delete on this bucket, and nothing else in the project ..."
run gcloud storage buckets add-iam-policy-binding "$BUCKET" --project "$PROJECT" \
    --member "serviceAccount:${ACCOUNT}" --role roles/storage.objectAdmin

echo
if [ "$DRY_RUN" -eq 1 ]; then
    echo "Dry run: nothing was created or granted."
else
    echo "Done. Next: LEGION_CLAUDE_FILES_BUCKET=legion-claude-files-${PROJECT} in"
    echo "deploy/cloudrun/.env, then python deploy/cloudrun/deploy.py"
fi
