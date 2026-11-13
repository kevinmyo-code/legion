#!/usr/bin/env bash
# One-time setup so GitHub Actions can deploy the engine (ship.yml) and publish phone releases
# (release-phone.yml) with no laptop. Kevin, 2026-11-13: run it from Google Cloud Shell
# (shell.cloud.google.com, gcloud is already signed in there), on the phone if need be.
#
#   bash deploy/cloudrun/github_setup.sh            # prints what to paste into GitHub
#   bash deploy/cloudrun/github_setup.sh --github   # and sets it on GitHub itself, through gh
#
# It does README.md "Shipping from GitHub" steps 1 to 4, gives the same deploy account write access
# to the APK bucket, and then PRINTS what to paste into GitHub (step 5): three variables, plus the
# CLOUDRUN_ENV secret rebuilt from the live service's own non-secret settings (deploy/cloudrun/.env
# lives only on the laptop). It never prints or reads a secret's value. Safe to run twice: steps that
# already exist are skipped. Undo: README.md "Undo".
set -euo pipefail

SET_GITHUB=false
[ "${1:-}" = "--github" ] && SET_GITHUB=true

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

WIF_PROVIDER="projects/${NUMBER}/locations/global/workloadIdentityPools/github/providers/legion"

if $SET_GITHUB; then
  say "5. GitHub: environment production, its reviewer, branch, variables and secret (through gh)"
  if ! command -v gh >/dev/null 2>&1; then
    echo "gh is not installed here. Install it (https://cli.github.com) or run without --github and paste by hand."
    exit 1
  fi
  gh auth status >/dev/null 2>&1 || gh auth login --hostname github.com --git-protocol https --web
  ME_ID="$(gh api user --jq .id)"
  printf '{"reviewers":[{"type":"User","id":%s}],"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' \
    "$ME_ID" | gh api -X PUT "repos/${REPO}/environments/production" --input - >/dev/null
  echo "environment production: you are its required reviewer"
  if gh api "repos/${REPO}/environments/production/deployment-branch-policies" \
       --jq '.branch_policies[].name' | grep -qx dev; then
    echo "exists: branch policy dev"
  else
    gh api -X POST "repos/${REPO}/environments/production/deployment-branch-policies" \
      -f name=dev -f type=branch >/dev/null
    echo "deploys only from dev"
  fi
  gh variable set GCP_PROJECT --env production --repo "$REPO" --body "$PROJECT"
  gh variable set GCP_WIF_PROVIDER --env production --repo "$REPO" --body "$WIF_PROVIDER"
  gh variable set GCP_SHIP_SERVICE_ACCOUNT --env production --repo "$REPO" --body "$SA"
  gh variable set LEGION_ENGINE_URL --env production --repo "$REPO" --body "$URL"
  if [ -n "$BUCKET" ]; then
    gh variable set LEGION_APK_BUCKET --env production --repo "$REPO" --body "$BUCKET"
  fi
  printf '%s\n' "$ENV_TEXT" | gh secret set CLOUDRUN_ENV --env production --repo "$REPO"
  echo "variables set; secret CLOUDRUN_ENV set (its value was not printed)"
  cat <<EOF

================ Done on GitHub ================
Environment production is set up for ${REPO}. Tell Claude "done".
Still to add later, for phone releases (see .github/workflows/release-phone.yml):
  secrets LEGION_ENGINE_TOKEN and PHONE_DEBUG_KEYSTORE_B64, variable MAPBOX_ACCESS_TOKEN
EOF
  exit 0
fi

cat <<EOF

================ Paste these into GitHub ================
github.com/${REPO} > Settings > Environments > New environment: production
  - Required reviewers: yourself
  - Deployment branches: dev
  Environment VARIABLES:
    GCP_PROJECT              = ${PROJECT}
    GCP_WIF_PROVIDER         = ${WIF_PROVIDER}
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
