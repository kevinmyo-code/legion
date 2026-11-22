#!/usr/bin/env bash
# post_deploy_probes.sh: the laptop engine ship's checks after a deploy (ship_engine_*_1121.sh steps 5
# and 6), for .github/workflows/ship.yml. Read only: GETs, requests that must be refused before any paid
# call, two signed-in reads that must find the dark routes switched off, and one log read.
#
#   ENGINE_URL=... SERVICE=legion REGION=us-south1 PROJECT=... NEW_REV=<revision> SWITCHES="<names>" \
#     [LEGION_ENGINE_TOKEN=<owner device token>] post_deploy_probes.sh
#
# Not carried from the laptop scripts (ship-specific, one-off): a pinned file served byte for byte (the
# garage model), an owner read of a pinned route, EXTRA_PROBES lines. A ship that needs them checks by hand.
set -euo pipefail
: "${ENGINE_URL:?}" "${SERVICE:?}" "${REGION:?}" "${PROJECT:?}" "${NEW_REV:?}"
SWITCHES=${SWITCHES:-}
U=${ENGINE_URL%/}
fail() { echo "::error::$1"; exit 1; }
code() { curl -sS -o /dev/null -w '%{http_code}' "$@" || echo 000; }
want() {  # want <label> <got> <allowed codes, space separated>
  case " $3 " in *" $2 "*) echo "ok $1: $2";; *) fail "$1 answered $2, not one of: $3";; esac
}
has_switch() { case " $SWITCHES " in *" $1 "*) return 0;; *) return 1;; esac; }

echo "== health, /try and its bundle, the service worker"
curl -fsS "$U/health" >/dev/null || fail "health check failed"
echo "ok /health"
TRY_HTML=$(curl -fsS "$U/try") || fail "/try did not answer 200"
MAIN_JS=$(printf '%s' "$TRY_HTML" | grep -o 'src="/assets/index-[^"]*\.js"' | head -1 | sed 's/^src="//;s/"$//')
[ -n "$MAIN_JS" ] || fail "/try names no /assets/index-*.js script"
curl -fsS -o /dev/null -w "ok /try serves $MAIN_JS: HTTP %{http_code}, %{size_download} bytes\n" "$U$MAIN_JS" || fail "the main script did not answer 200"
SW=$(curl -fsS "$U/sw.js") || fail "/sw.js did not answer 200"
if grep -qE '\.(wasm|onnx|glb)|model-stage' <<<"$SW"; then fail "the live service worker precaches .wasm, .onnx, .glb or the 3D chunk"; fi
echo "ok service worker: no .wasm, .onnx, .glb or 3D chunk in its precache"

echo "== no-session probes (each refused before any paid call)"
J='Content-Type: application/json'
Z=00000000-0000-4000-8000-000000000000
want "POST /api/assistant/turn" "$(code -X POST -F 'text=ship check' "$U/api/assistant/turn")" "401 403"
want "POST /api/assistant/turn/cancel" "$(code -X POST -H "$J" -d "{\"turn_id\":\"$Z\"}" "$U/api/assistant/turn/cancel")" "401 403"
want "POST /api/demo/chips" "$(code -X POST -H "$J" -d '{"offer":"ship-check"}' "$U/api/demo/chips")" "401 403"
want "POST /api/assistant/robotic" "$(code -X POST -H "$J" -d "{\"conversation_id\":\"$Z\",\"turn\":0}" "$U/api/assistant/robotic")" "401 403"
want "POST /api/agents/turn" "$(code -X POST -H "$J" -d '{"transcript":"ship check"}' "$U/api/agents/turn")" "401 403 404"
want "POST /api/agents/turn/<id>/cancel" "$(code -X POST -H "$J" -d '{}' "$U/api/agents/turn/$Z/cancel")" "401 403 404"
want "POST /api/agents/turn/stream" "$(code -X POST --data-binary '' "$U/api/agents/turn/stream")" "401 403 404"
want "POST /api/agents/turn/<id>/tool_result" "$(code -X POST -H "$J" -d '{}' "$U/api/agents/turn/$Z/tool_result")" "401 403"
want "GET /api/agents/turn/<id>" "$(code "$U/api/agents/turn/$Z")" "401 403"
want "POST /api/agents/turn/<id>/go" "$(code -X POST -H "$J" -d '{}' "$U/api/agents/turn/$Z/go")" "401 403"
want "POST /api/agents/turn/<id>/take" "$(code -X POST -H "$J" -d '{}' "$U/api/agents/turn/$Z/take")" "401 403"
want "GET /api/demo/media/<x>" "$(code "$U/api/demo/media/ship-check.glb")" "401 403"
HU=$U/api/auth/head-units
if has_switch LEGION_HEADUNIT_SIGNIN; then HU_OK="404"; else HU_OK="401 403 404"; fi
want "GET head-units" "$(code "$HU")" "$HU_OK"
want "POST head-units" "$(code -X POST -H "$J" -d '{"label":"ship check"}' "$HU")" "$HU_OK"
want "DELETE head-units/<id>" "$(code -X DELETE "$HU/$Z")" "$HU_OK"
want "POST head-units/revoke-all" "$(code -X POST -H "$J" -d '{}' "$HU/revoke-all")" "$HU_OK"
want "POST head-units/<id>/rotate" "$(code -X POST -H "$J" -d '{}' "$HU/$Z/rotate")" "$HU_OK"

if [ -n "${LEGION_ENGINE_TOKEN:-}" ]; then
  echo "== signed-in dark checks (the owner token; the routes must answer switched off)"
  A="Authorization: Token $LEGION_ENGINE_TOKEN"
  if has_switch LEGION_BOARD; then
    bc=$(code -H "$A" "$U/api/board")
    case "$bc" in
      404) echo "ok GET /api/board signed in: 404 (LEGION_BOARD off)";;
      401|403) echo "NOTE: /api/board refused the release token ($bc); dark rests on the names-only switch check";;
      *) fail "GET /api/board signed in answered $bc, not 404: the board is not dark";;
    esac
  fi
  if has_switch LEGION_AGENTS_TURN; then
    B=$(mktemp)
    c=$(curl -sS -o "$B" -w '%{http_code}' -H "$A" "$U/api/agents/turn/$Z" || echo 000)
    if [ "$c" = 404 ] && grep -qF 'LEGION_AGENTS_TURN is not on' "$B"; then
      echo "ok GET /api/agents/turn/<id> as the owner: 404 switched off"
    elif [ "$c" = 401 ] || [ "$c" = 403 ]; then
      echo "NOTE: the agents door refused the release token ($c); dark rests on the anonymous probes and the names-only switch check"
    else
      fail "GET /api/agents/turn/<id> as the owner answered $c without the switched-off words: not dark"
    fi
    rm -f "$B"
  fi
else
  echo "NOTE: no owner token in this job: the signed-in dark checks did not run"
fi

echo "== error-level log lines on $NEW_REV"
sleep 60
ERRS=$(gcloud logging read "resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"$SERVICE\" AND resource.labels.revision_name=\"$NEW_REV\" AND severity>=ERROR" \
  --project "$PROJECT" --format='value(timestamp)' --freshness=1h) || fail "could not read the revision's log"
N=$(printf '%s\n' "$ERRS" | grep -c . || true)
[ "$N" = 0 ] || fail "$NEW_REV has $N error-level log lines since it started; read them (rollback text above) before going on"
echo "ok log: 0 error-level lines on $NEW_REV"
echo "post-deploy probes: all passed"
