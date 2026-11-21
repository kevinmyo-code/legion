#!/usr/bin/env bash
# post_deploy_probes.sh: the laptop engine ship's checks after a deploy (steps 5 and 6 of
# ship_engine_template.sh), for .github/workflows/ship.yml (cloud-builds plan step 7, 2026-11-20).
# Read only: GETs, credential-less POSTs that must be refused before any paid call, and one log read.
#
#   ENGINE_URL=... SERVICE=legion REGION=us-south1 PROJECT=... MUST_BE_UNSET="..." post_deploy_probes.sh
#
# Checks: /health; /try answers and names its main script, which answers; /sw.js precaches no .wasm,
# .onnx, .glb or 3D chunk; the web door, the agents door and the head-unit routes refuse a caller with no
# session (head units: 404 while LEGION_HEADUNIT_SIGNIN is listed in MUST_BE_UNSET, its switch running
# before authentication); then, after 60 s, 0 error-level log lines on the serving revision.
set -euo pipefail
: "${ENGINE_URL:?}" "${SERVICE:?}" "${REGION:?}" "${PROJECT:?}"
MUST_BE_UNSET=${MUST_BE_UNSET:-}
U=${ENGINE_URL%/}
fail() { echo "::error::$1"; exit 1; }
code() { curl -sS -o /dev/null -w '%{http_code}' "$@" || echo 000; }
want() {  # want <label> <got> <allowed codes, space separated>
  case " $3 " in *" $2 "*) echo "ok $1: $2";; *) fail "$1 answered $2, not one of: $3";; esac
}

echo "== health, /try and its bundle, the service worker"
curl -fsS "$U/health" || fail "health check failed"
echo
TRY_HTML=$(curl -fsS "$U/try") || fail "/try did not answer 200"
MAIN_JS=$(printf '%s' "$TRY_HTML" | grep -o 'src="/assets/index-[^"]*\.js"' | head -1 | sed 's/^src="//;s/"$//')
[ -n "$MAIN_JS" ] || fail "/try names no /assets/index-*.js script"
curl -fsS -o /dev/null -w "/try serves $MAIN_JS: HTTP %{http_code}, %{size_download} bytes\n" "$U$MAIN_JS" || fail "the main script did not answer 200"
SW=$(curl -fsS "$U/sw.js") || fail "/sw.js did not answer 200"
if printf '%s' "$SW" | grep -qE '\.(wasm|onnx|glb)|model-stage'; then fail "the live service worker precaches .wasm, .onnx, .glb or the 3D chunk"; fi
echo "service worker: no .wasm, .onnx, .glb or 3D chunk in its precache"

echo "== no-session probes (no credentials; each must be refused before any paid call)"
J='Content-Type: application/json'
Z=00000000-0000-4000-8000-000000000000
want "POST /api/assistant/turn" "$(code -X POST -F 'text=ship check' "$U/api/assistant/turn")" "401 403"
want "POST /api/assistant/turn/cancel" "$(code -X POST -H "$J" -d "{\"turn_id\":\"$Z\"}" "$U/api/assistant/turn/cancel")" "401 403"
want "POST /api/demo/chips" "$(code -X POST -H "$J" -d '{"offer":"ship-check"}' "$U/api/demo/chips")" "401 403"
want "POST /api/assistant/robotic" "$(code -X POST -H "$J" -d "{\"conversation_id\":\"$Z\",\"turn\":0}" "$U/api/assistant/robotic")" "401 403"
want "POST /api/agents/turn" "$(code -X POST -H "$J" -d '{"transcript":"ship check"}' "$U/api/agents/turn")" "401 403 404"
want "POST /api/agents/turn/<id>/cancel" "$(code -X POST -H "$J" -d '{}' "$U/api/agents/turn/$Z/cancel")" "401 403 404"
want "POST /api/agents/turn/stream" "$(code -X POST --data-binary '' "$U/api/agents/turn/stream")" "401 403 404"
HU=$U/api/auth/head-units
case " $MUST_BE_UNSET " in
  *" LEGION_HEADUNIT_SIGNIN "*) HU_OK="404";;
  *) HU_OK="401 403 404";;
esac
want "GET head-units" "$(code "$HU")" "$HU_OK"
want "POST head-units" "$(code -X POST -H "$J" -d '{"label":"ship check"}' "$HU")" "$HU_OK"
want "DELETE head-units/<id>" "$(code -X DELETE "$HU/$Z")" "$HU_OK"
want "POST head-units/revoke-all" "$(code -X POST -H "$J" -d '{}' "$HU/revoke-all")" "$HU_OK"
want "POST head-units/<id>/rotate" "$(code -X POST -H "$J" -d '{}' "$HU/$Z/rotate")" "$HU_OK"

echo "== error-level log lines on the serving revision"
REV=$(gcloud run services describe "$SERVICE" --region "$REGION" --project "$PROJECT" --format='value(status.latestReadyRevisionName)')
[ -n "$REV" ] || fail "could not read the serving revision"
sleep 60
ERRS=$(gcloud logging read "resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"$SERVICE\" AND resource.labels.revision_name=\"$REV\" AND severity>=ERROR" \
  --project "$PROJECT" --format='value(timestamp)' --freshness=1h) || fail "could not read the revision's log"
N=$(printf '%s\n' "$ERRS" | grep -c . || true)
[ "$N" = 0 ] || fail "$REV has $N error-level log lines since it started; read them (rollback text above) before going on"
echo "log: 0 error-level lines on $REV"
echo "post-deploy probes: all passed"
