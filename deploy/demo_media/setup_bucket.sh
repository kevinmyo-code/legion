#!/usr/bin/env bash
# One-time infra for the recruiter page's media files (Supabase egress cut 4, 2026-11-23).
# Run by Kevin, once per project:
#
#     bash deploy/demo_media/setup_bucket.sh legion-engine-260906            # for real
#     bash deploy/demo_media/setup_bucket.sh legion-engine-260906 --dry-run  # print, run nothing
#
# The project comes from the first argument or GOOGLE_CLOUD_PROJECT. There is no default project,
# the same refusal `deploy/release/setup_bucket.sh` makes (CLAUDE.md section 7: nothing
# Kevin-hosted for strangers; a stranger's clone names its own).
#
# What it does, each step safe to re-run:
#   1. Enables Cloud Storage.
#   2. Creates the PRIVATE bucket gs://legion-demo-media-<project>: uniform bucket-level access
#      and public access prevention enforced, so no object in it can ever be made public. If the
#      bucket already exists it is updated to both settings instead. NO lifecycle rule: these
#      files are the live page, not a handoff, and nothing may delete them under it.
#   3. Gives the Cloud Run service account `roles/storage.objectViewer` on that bucket only. The
#      engine only ever READS: `manage.py load_recruiter_content` writes, and that runs on the
#      owner's own machine with his own credentials, not here. No signed
#      URL is ever made, so no signBlob grant is needed: the engine reads the object itself and
#      streams the bytes to the visit over its session, exactly as it streamed them out of
#      Postgres. No link to a media file works without a demo visit.
#
# The service account is read from the deployed service, falling back to the default compute
# account exactly as `deploy/claude_files/setup_bucket.sh` does.
#
# After this, set LEGION_DEMO_MEDIA_BUCKET=legion-demo-media-<project> in deploy/cloudrun/.env and
# redeploy (`python deploy/cloudrun/deploy.py`), THEN reload the content with the same variable
# set in that shell, which is what puts the bytes in the bucket. Until the reload, the engine
# reads them out of Postgres as before: the column is still written.
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
    echo "Run: bash deploy/demo_media/setup_bucket.sh <project-id>" >&2
    exit 1
fi

BUCKET="gs://legion-demo-media-${PROJECT}"

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
echo "3. The Cloud Run service account ..."
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
echo "4. Read on this bucket, and nothing else in the project ..."
run gcloud storage buckets add-iam-policy-binding "$BUCKET" --project "$PROJECT" \
    --member "serviceAccount:${ACCOUNT}" --role roles/storage.objectViewer

echo
if [ "$DRY_RUN" -eq 1 ]; then
    echo "Dry run: nothing was created or granted."
else
    echo "Done. Next: LEGION_DEMO_MEDIA_BUCKET=legion-demo-media-${PROJECT} in deploy/cloudrun/.env,"
    echo "then python deploy/cloudrun/deploy.py, then reload the content with that same variable"
    echo "exported in the shell that runs load_recruiter_content."
fi
