#!/usr/bin/env bash
# ship_engine_deploy.sh: the production half of .github/workflows/ship.yml (cloud-builds step 2,
# 2026-11-21): what the laptop ship_engine_*_1121.sh scripts do from their step 1 live checks to the end.
# Runs in the job that holds the Google login (production environment, one approval tap), from the
# workspace checkout of FIX (= the dev tip the run was dispatched on).
#
# Environment (set by the workflow): FIX LIVE_CODE EXPECT_LIVE_REV SWITCHES (names that must stay unset)
# MIGRATIONS (file of '<path> <blob>' lines, may be empty) DRY (true = checks and plan only) PROJECT REGION
# SERVICE JOB ENGINE_URL, and LEGION_ENGINE_TOKEN (the owner token, for two signed-in dark reads).
# deploy/cloudrun/.env must already be written (the CLOUDRUN_ENV secret).
#
# Order (each a stop):
#  1. dev = main = FIX now (after the approval wait); the live revision is EXPECT_LIVE_REV with 100% of the
#     traffic (review S1); the switches are absent from .env, the deploy plan, the live service (names only)
#     and the job; the deploy keeps every live env name and adds none; the runtime (max instances,
#     timeout, concurrency) is unchanged. Rollback facts printed before anything changes.
#  2. DRY: stop here, plan printed.
#  3. the deploy window: starts only by :22 or after :45 (the :40 consolidate cron); waits up to 25 min.
#  4. dev, main and the live revision re-checked right before the deploy (a laptop ship in between stops it).
#  5. deploy.py --ci (Cloud Build image server:latest, then the service; the container applies schema files on
#     start); only after that succeeds, deploy_job.py --ci moves the job to the same image (review S7: a
#     failed schema step leaves traffic and the job on the old code).
#  6. confirm: a NEW revision with 100% of the traffic, every live env name kept, the switches absent on the
#     revision (names only) and the job, the runtime unchanged.
#  7. each pinned schema file confirmed applied (showmigrations through the job, read only).
#  8. post_deploy_probes.sh: health, /try, the service worker, the no-session probes, the signed-in dark reads,
#     0 error-level log lines on the new revision. Then the rollback text again.
set -euo pipefail
: "${FIX:?}" "${LIVE_CODE:?}" "${EXPECT_LIVE_REV:?}" "${SWITCHES:?}" "${PROJECT:?}" "${REGION:?}" "${SERVICE:?}" "${JOB:?}" "${ENGINE_URL:?}"
MIGRATIONS=${MIGRATIONS:-}
DRY=${DRY:-true}
ENV_FILE=deploy/cloudrun/.env
G=(--project "$PROJECT" --region "$REGION")
stop() { echo "::error::$*"; exit 1; }
norm() { tr -d '\r' | tr ' ;,' '\n\n\n' | awk 'NF' | LC_ALL=C sort -u | tr '\n' ' ' | sed 's/ $//'; }

check_refs() {
  git fetch -q origin dev main
  [ "$(git rev-parse origin/dev)" = "$FIX" ] && [ "$(git rev-parse origin/main)" = "$FIX" ] \
    || stop "dev ($(git rev-parse --short origin/dev)) and main ($(git rev-parse --short origin/main)) must both be FIX ${FIX:0:8}: something moved; nothing deployed"
}
traffic() {  # "<revision> <percent>" lines with a non-zero percent
  gcloud run services describe "$SERVICE" "${G[@]}" --format=json \
    | python3 -c "import json,sys
for t in json.load(sys.stdin)['status'].get('traffic', []):
    if t.get('percent'): print(t.get('revisionName', ''), t['percent'])"
}
check_live() {
  local rev t
  rev=$(gcloud run services describe "$SERVICE" "${G[@]}" --format='value(status.latestReadyRevisionName)' | tr -d '\r')
  [ "$rev" = "$EXPECT_LIVE_REV" ] || stop "the live revision is $rev, not the pinned $EXPECT_LIVE_REV (its code may not be ${LIVE_CODE:0:8}); nothing deployed"
  t=$(traffic)
  [ "$t" = "$EXPECT_LIVE_REV 100" ] || stop "traffic is not 100% on $EXPECT_LIVE_REV: $(echo $t); nothing deployed"
}

echo "== 1) live state, the plan and the switches (read only)"
check_refs
check_live
echo "dev = main = ${FIX:0:8}; live revision $EXPECT_LIVE_REV at 100% (pinned as running ${LIVE_CODE:0:8})"
[ -f "$ENV_FILE" ] || stop "no $ENV_FILE (the CLOUDRUN_ENV secret)"
for n in $SWITCHES; do
  ! sed 's/\r$//' "$ENV_FILE" | grep -qE "^[[:space:]]*(export[[:space:]]+)?$n[[:space:]]*=" || stop "$ENV_FILE names $n; it must be absent"
done
SVC_NAMES=$(gcloud run services describe "$SERVICE" "${G[@]}" --format='value(spec.template.spec.containers[0].env[].name)' | norm)
JOB_NAMES=$(gcloud run jobs describe "$JOB" "${G[@]}" --format='value(spec.template.spec.template.spec.containers[0].env[].name)' | norm)
[ -n "$SVC_NAMES" ] && [ -n "$JOB_NAMES" ] || stop "could not read the live env names"
for n in $SWITCHES; do
  case " $SVC_NAMES " in *" $n "*) stop "the live service carries $n";; esac
  case " $JOB_NAMES " in *" $n "*) stop "the live job carries $n";; esac
done
PLAN=$(python3 - deploy/cloudrun "$ENV_FILE" <<'EOF'
import sys
sys.path.insert(0, sys.argv[1])
import _common as c
import deploy as d
values = {}
for raw in open(sys.argv[2], encoding="utf-8").read().splitlines():
    line = raw.strip()
    if line and not line.startswith("#") and "=" in line:
        k, _, v = line.partition("=")
        values[k.strip()] = v.strip().strip("'\"")
conc, mx, _ = d.connection_plan(values, int(values.get("CLOUD_RUN_MAX_INSTANCES", "4")))
names = sorted(set(c.plain_env_from(values)) | set(c.secret_names_for(values)))
print(f"{mx} {d.REQUEST_TIMEOUT_SECONDS} {conc}")
print(" ".join(names))
EOF
)
PLAN_RUNTIME=$(echo "$PLAN" | sed -n 1p); WILL=$(echo "$PLAN" | sed -n 2p | norm)
for n in $SWITCHES; do case " $WILL " in *" $n "*) stop "the deploy plan would set $n";; esac; done
LOST=""; for n in $SVC_NAMES $JOB_NAMES; do case " $WILL " in *" $n "*) ;; *) LOST="$LOST $n";; esac; done
[ -z "$LOST" ] || stop "the deploy would drop live env:$LOST"
ADDS=""; for n in $WILL; do case " $SVC_NAMES $JOB_NAMES " in *" $n "*) ;; *) ADDS="$ADDS $n";; esac; done
[ -z "$ADDS" ] || stop "the deploy would add env$ADDS (not live today); a ship through this workflow adds none"
LIVE_RUNTIME=$(gcloud run services describe "$SERVICE" "${G[@]}" --format=json | python3 -c "import json,sys
t = json.load(sys.stdin)['spec']['template']
print(t['metadata'].get('annotations', {}).get('autoscaling.knative.dev/maxScale', ''), t['spec'].get('timeoutSeconds', ''), t['spec'].get('containerConcurrency', ''))")
[ "$PLAN_RUNTIME" = "$LIVE_RUNTIME" ] || stop "the deploy would change the runtime (live $LIVE_RUNTIME, plan $PLAN_RUNTIME)"
echo "switches absent (.env, plan, service, job): $SWITCHES"
echo "env: every live name kept, none added; runtime unchanged ($LIVE_RUNTIME)"
PREV_DIGEST=$(gcloud run revisions describe "$EXPECT_LIVE_REV" "${G[@]}" --format='value(status.imageDigest)' | tr -d '\r')
PREV_JOB_IMAGE=$(gcloud run jobs describe "$JOB" "${G[@]}" --format='value(spec.template.spec.template.spec.containers[0].image)' | tr -d '\r')
[ -n "$PREV_DIGEST" ] && [ -n "$PREV_JOB_IMAGE" ] || stop "could not record the rollback facts"
ROLLBACK="ROLLBACK (recorded before anything changed):
  service:  gcloud run services update-traffic $SERVICE --project $PROJECT --region $REGION --to-revisions=$EXPECT_LIVE_REV=100
  job:      gcloud run jobs update $JOB --project $PROJECT --region $REGION --image $PREV_DIGEST
            (job image before: $PREV_JOB_IMAGE; roll back by digest, :latest moves with the deploy)
  schema:   added schema files stay applied; the rehearsal proved ${LIVE_CODE:0:8} serves on them
  switches: $SWITCHES stay unset on any rollback
  dev/main: were at ${FIX:0:8} before this run (this workflow pushes nothing); undo with one new commit
            whose tree is the old one, never a force push"
echo "$ROLLBACK"

if [ "$DRY" = true ]; then
  echo "== DRY RUN: every check above passed; nothing built, deployed or changed"
  exit 0
fi

echo "== 3) deploy window (starts only by :22 or after :45)"
for _ in $(seq 1 50); do
  m=$((10#$(date +%M)))
  { [ "$m" -le 22 ] || [ "$m" -gt 45 ]; } && break
  echo "$(date +%T) waiting for the window"; sleep 30
done
m=$((10#$(date +%M))); { [ "$m" -le 22 ] || [ "$m" -gt 45 ]; } || stop "no deploy window in 25 minutes; nothing deployed"

echo "== 4) re-check right before the deploy"
check_refs
check_live
echo "still dev = main = ${FIX:0:8}, $EXPECT_LIVE_REV at 100%"

echo "== 5) deploy: image + service (schema files applied on start), then the job"
REST="
$ROLLBACK"
(cd deploy/cloudrun && python3 deploy.py --ci --project "$PROJECT" --region "$REGION") || {
  echo "::error::the service deploy failed"
  echo "PRODUCTION STATE: traffic was NOT switched; $EXPECT_LIVE_REV still serves every request with ${LIVE_CODE:0:8}."
  echo "  The new container runs migrate on start, one transaction per schema file: a failed one rolled back;"
  echo "  if one applied and the container failed later, its tables exist, unused (additive). The job was NOT moved."
  echo "$REST"; exit 1; }
(cd deploy/cloudrun && python3 deploy_job.py --ci --project "$PROJECT" --region "$REGION" --image "${REGION}-docker.pkg.dev/${PROJECT}/legion/server:latest") || {
  echo "::error::the job image update failed: the SERVICE is already on the new revision, the job still runs $PREV_JOB_IMAGE"
  echo "$REST"; exit 1; }

echo "== 6) confirm the new revision, its traffic, env and switches"
NEW_REV=$(gcloud run services describe "$SERVICE" "${G[@]}" --format='value(status.latestReadyRevisionName)' | tr -d '\r')
[ "$NEW_REV" != "$EXPECT_LIVE_REV" ] || { echo "::error::the service is still on $EXPECT_LIVE_REV: no new revision"; echo "$REST"; exit 1; }
[ "$(traffic)" = "$NEW_REV 100" ] || { echo "::error::traffic is not 100% on $NEW_REV: $(traffic | tr '\n' ' ')"; echo "$REST"; exit 1; }
REV_NAMES=$(gcloud run revisions describe "$NEW_REV" "${G[@]}" --format='value(spec.containers[0].env[].name)' | norm)
JOB_NOW=$(gcloud run jobs describe "$JOB" "${G[@]}" --format='value(spec.template.spec.template.spec.containers[0].env[].name)' | norm)
for n in $SVC_NAMES; do case " $REV_NAMES " in *" $n "*) ;; *) echo "::error::env $n is gone from $NEW_REV"; echo "$REST"; exit 1;; esac; done
for n in $JOB_NAMES; do case " $JOB_NOW " in *" $n "*) ;; *) echo "::error::env $n is gone from the job"; echo "$REST"; exit 1;; esac; done
for n in $SWITCHES; do
  case " $REV_NAMES $JOB_NOW " in *" $n "*) echo "::error::$n is set after the deploy"; echo "$REST"; exit 1;; esac
done
NEW_RUNTIME=$(gcloud run services describe "$SERVICE" "${G[@]}" --format=json | python3 -c "import json,sys
t = json.load(sys.stdin)['spec']['template']
print(t['metadata'].get('annotations', {}).get('autoscaling.knative.dev/maxScale', ''), t['spec'].get('timeoutSeconds', ''), t['spec'].get('containerConcurrency', ''))")
[ "$NEW_RUNTIME" = "$LIVE_RUNTIME" ] || { echo "::error::runtime changed: $LIVE_RUNTIME -> $NEW_RUNTIME"; echo "$REST"; exit 1; }
echo "$NEW_REV: 100% of the traffic, env kept, switches absent, runtime unchanged"

if [ -n "$MIGRATIONS" ] && grep -qE '^server/' "$MIGRATIONS"; then
  echo "== 7) the pinned schema files confirmed applied (read only, through the job)"
  APPS=$(grep -oE '^server/[^/]+/migrations/[0-9]{4}_[A-Za-z0-9_]+\.py' "$MIGRATIONS" | cut -d/ -f2 | sort -u | paste -sd, -)
  EXEC=$(gcloud run jobs execute "$JOB" "${G[@]}" --wait --args="manage.py,showmigrations,$APPS" --format='value(metadata.name)') \
    || { echo "::error::the showmigrations job failed"; echo "$REST"; exit 1; }
  OUT=""
  for _ in $(seq 1 10); do
    OUT=$(gcloud logging read "resource.type=\"cloud_run_job\" AND labels.\"run.googleapis.com/execution_name\"=\"$EXEC\"" \
      --project "$PROJECT" --format='value(textPayload)' --freshness=1h || true)
    [ -n "$OUT" ] && break
    sleep 30
  done
  while read -r path _; do
    case "$path" in server/*) ;; *) continue;; esac
    name=$(basename "$path" .py)
    echo "$OUT" | grep -qE "\[X\] +$name" || { echo "::error::$name is not confirmed applied"; echo "$REST"; exit 1; }
    echo "[X] $name"
  done < "$MIGRATIONS"
fi

echo "== 8) probes and the error log"
NEW_REV="$NEW_REV" bash deploy/post_deploy_probes.sh || { echo "$REST"; exit 1; }
echo "== deployed ${FIX:0:8} as $NEW_REV"
echo "$ROLLBACK"
