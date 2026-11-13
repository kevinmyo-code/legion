#!/usr/bin/env bash
# One-time setup so GitHub Actions can deploy the engine (ship.yml) and publish phone releases
# (release-phone.yml) with no laptop. Kevin, 2026-11-13: run it from Google Cloud Shell
# (shell.cloud.google.com, gcloud is already signed in there), on the phone if need be.
#
#   bash deploy/cloudrun/github_setup.sh
#
# It does README.md "Shipping from GitHub" steps 1 to 4, gives the same deploy account write access
# to the APK bucket, and then PRINTS what to paste into GitHub (step 5): three variables, plus the
# CLOUDRUN_ENV secret rebuilt from the live service's own non-secret settings (deploy/cloudrun/.env
# lives only on the laptop). It never prints or reads a secret's value. Safe to run twice: steps that
# already exist are skipped. Undo: README.md "Undo".
set -euo pipefail

PROJECT="${GOOGLE_CLOUD_PROJECT:-legion-engine-260906}"
REPO="kevinmyo-code/legion"
REGION="${CLOUD_RUN_REGION:-us-south1}"
SERVICE="legion"
SA_NAME="legion-ship"
SA="${SA_NAME}@${PROJECT}.iam.gserviceaccount.com"

say() { printf '\n== %s\n' "$*"; }
exists() { "$@" >/dev/null 2>&1; }

gcloud config set project "$PROJECT" >/dev/null
NUMBER="$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')"
say "Project $PROJECT (number $NUMBER), repo $REPO"

say "1. Deploy service account"
if exists gcloud iam service-accounts describe "$SA"; then
  echo "exists: $SA"
else
  gcloud iam service-accounts create "$SA_NAME" --display-name "LEGION ship (GitHub)"
fi

say "2. Its roles"
for role in roles/run.developer roles/cloudbuild.builds.editor roles/artifactregistry.writer \
            roles/cloudscheduler.admin roles/logging.viewer roles/storage.admin \
            roles/secretmanager.viewer roles/serviceusage.serviceUsageConsumer; do
  gcloud projects add-iam-policy-binding "$PROJECT" --member "serviceAccount:$SA" --role "$role" \
    --condition=None >/dev/null
  echo "granted $role"
done
gcloud iam service-accounts add-iam-policy-binding "${NUMBER}-compute@developer.gserviceaccount.com" \
  --member "serviceAccount:$SA" --role roles/iam.serviceAccountUser >/dev/null
echo "may deploy as the runtime account"

say "3. Workload Identity pool and provider (trusts only $REPO, environment production)"
if exists gcloud iam workload-identity-pools describe github --location global; then
  echo "exists: pool github"
else
  gcloud iam workload-identity-pools create github --location global --display-name "GitHub Actions"
fi
if exists gcloud iam workload-identity-pools providers describe legion --location global \
     --workload-identity-pool github; then
  echo "exists: provider legion"
else
  gcloud iam workload-identity-pools providers create-oidc legion --location global \
    --workload-identity-pool github \
    --issuer-uri "https://token.actions.githubusercontent.com" \
    --attribute-mapping "google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref,attribute.environment=assertion.environment" \
    --attribute-condition "assertion.repository == '${REPO}' && assertion.environment == 'production'"
fi

say "4. Let the repo's production environment act as the deploy account"
gcloud iam service-accounts add-iam-policy-binding "$SA" --role roles/iam.workloadIdentityUser \
  --member "principalSet://iam.googleapis.com/projects/${NUMBER}/locations/global/workloadIdentityPools/github/attribute.repository/${REPO}" \
  >/dev/null
echo "bound"

say "Reading the live service (non-secret settings only)"
DESC="$(gcloud run services describe "$SERVICE" --region "$REGION" --format=json)"
ENV_TEXT="$(printf '%s' "$DESC" | python3 -c '
import json, sys
svc = json.load(sys.stdin)
envs = svc["spec"]["template"]["spec"]["containers"][0].get("env", [])
for e in envs:
    if "value" in e and "valueFrom" not in e:  # plain settings only; secrets stay in Secret Manager
        print(e["name"] + "=" + e["value"])
')"
URL="$(printf '%s' "$DESC" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"]["url"])')"
BUCKET="$(printf '%s\n' "$ENV_TEXT" | sed -n 's/^LEGION_APK_BUCKET=//p')"
grep -q '^GOOGLE_CLOUD_PROJECT=' <<<"$ENV_TEXT" || ENV_TEXT="${ENV_TEXT}
GOOGLE_CLOUD_PROJECT=${PROJECT}"
grep -q '^CLOUD_RUN_REGION=' <<<"$ENV_TEXT" || ENV_TEXT="${ENV_TEXT}
CLOUD_RUN_REGION=${REGION}"

if [ -n "$BUCKET" ]; then
  say "APK bucket gs://$BUCKET: the deploy account may write releases there"
  gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" --member "serviceAccount:$SA" \
    --role roles/storage.objectCreator >/dev/null
  echo "granted roles/storage.objectCreator"
else
  say "No LEGION_APK_BUCKET on the live service: phone releases cannot publish until one is set"
fi

cat <<EOF

================ Paste these into GitHub ================
github.com/${REPO} > Settings > Environments > New environment: production
  - Required reviewers: yourself
  - Deployment branches: dev
  Environment VARIABLES:
    GCP_PROJECT              = ${PROJECT}
    GCP_WIF_PROVIDER         = projects/${NUMBER}/locations/global/workloadIdentityPools/github/providers/legion
    GCP_SHIP_SERVICE_ACCOUNT = ${SA}
    LEGION_ENGINE_URL        = ${URL}
    LEGION_APK_BUCKET        = ${BUCKET:-<none set>}
  Environment SECRET CLOUDRUN_ENV = everything between the lines below:
-----------------------------------------------------------
${ENV_TEXT}
-----------------------------------------------------------
For phone releases, two more environment SECRETS (see .github/workflows/release-phone.yml):
  LEGION_ENGINE_TOKEN       = an owner device token (the curl line in that file's header)
  PHONE_DEBUG_KEYSTORE_B64  = the signing key (also in that file's header)
EOF
