#!/usr/bin/env bash
# One-time infra for self-update (alfred-ota ticket 04). Run by Kevin, once per project:
#
#     bash deploy/release/setup_bucket.sh legion-engine-260906            # for real
#     bash deploy/release/setup_bucket.sh legion-engine-260906 --dry-run  # print, run nothing
#
# The project comes from the first argument or GOOGLE_CLOUD_PROJECT. There is no default
# project, the same refusal `deploy/cloudrun/_common.py:require_project` makes (CLAUDE.md
# section 7: nothing Kevin-hosted for strangers; a stranger's clone names its own).
#
# What it does, each step safe to re-run:
#   1. Enables Cloud Storage and IAM Credentials (the signBlob API a V4 URL is signed through).
#   2. Creates the PRIVATE bucket gs://legion-apk-<project>: uniform bucket-level access and
#      public access prevention enforced, so no object in it can ever be made public. If the
#      bucket already exists it is updated to both settings instead.
#   3. Gives the Cloud Run service account `roles/storage.objectViewer` on that bucket only.
#   4. Gives the same account `roles/iam.serviceAccountTokenCreator` on ITSELF, which carries
#      `iam.serviceAccounts.signBlob`. On Cloud Run there is no private key file, so the engine
#      signs each download URL by asking IAM to sign it as this account (`releases/signing.py`).
#
# The service account is read from the deployed service (`gcloud run services describe`).
# `deploy/cloudrun/deploy.py` passes no --service-account, so on a service that does not
# exist yet, or one with none set, it falls back to the default compute account,
# `<project number>-compute@developer.gserviceaccount.com`, the way `install_schedule.py` does.
#
# Uploading is not granted here: `tools/release_apk.py` uploads with Kevin's own gcloud login,
# which as project owner can already write the bucket.
#
# After this, set LEGION_APK_BUCKET=legion-apk-<project> in deploy/cloudrun/.env and redeploy
# (`python deploy/cloudrun/deploy.py`). Until then the download endpoint refuses in words.
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
    echo "Run: bash deploy/release/setup_bucket.sh <project-id>" >&2
    exit 1
fi

BUCKET="gs://legion-apk-${PROJECT}"

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

echo "1. Enabling the APIs (no-op if already on) ..."
run gcloud services enable storage.googleapis.com iamcredentials.googleapis.com --project "$PROJECT"

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
echo "4. Read access to the bucket, and nothing else in the project ..."
run gcloud storage buckets add-iam-policy-binding "$BUCKET" --project "$PROJECT" \
    --member "serviceAccount:${ACCOUNT}" --role roles/storage.objectViewer

echo
echo "5. signBlob on itself, so it can sign a V4 download URL with no key file ..."
run gcloud iam service-accounts add-iam-policy-binding "$ACCOUNT" --project "$PROJECT" \
    --member "serviceAccount:${ACCOUNT}" --role roles/iam.serviceAccountTokenCreator

echo
if [ "$DRY_RUN" -eq 1 ]; then
    echo "Dry run: nothing was created or granted."
else
    echo "Done. Next: LEGION_APK_BUCKET=legion-apk-${PROJECT} in deploy/cloudrun/.env, then"
    echo "python deploy/cloudrun/deploy.py"
fi
