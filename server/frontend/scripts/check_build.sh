#!/usr/bin/env bash
# The web production build, exactly as the engine image runs it, and the checks the laptop ship scripts
# ran on its output (step 1b of the 11-19 and 11-20 ships). Used by .github/workflows/frontend.yml and
# runnable by hand from the repo root:
#
#     bash server/frontend/scripts/check_build.sh
#
# It builds the COMMITTED tree (git archive HEAD), not the working copy, in a temporary folder shaped like
# the image's first stage (server/Dockerfile): /frontend holds server/frontend, /tests/fixtures holds the
# one fixture the build reads, and nothing else (no repo-root shared/), so a build that only works with
# the rest of the repo around it fails here as it would in the image. `npm ci` needs about 2 GB of memory.
#
# Checks, each with its own failure message:
#   1. `npm ci && npm run build` (tsc -b, then vite build) succeeds.
#   2. The service worker exists and precaches no .wasm, .onnx or .glb file.
#   3. The build output holds no .wasm/.onnx/.glb beyond onnxruntime-web's own ort-wasm-simd-threaded-*.wasm.
#   4. The /try 3D viewer (components/demo/model-stage.ts) is exactly one lazy chunk, model-stage-*.js,
#      that instantiates no WebAssembly and is NOT in the service worker precache.
#   5. three.js (WebGLRenderer) is in neither the main chunk nor any precached chunk.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel)
CTX=$(mktemp -d)
trap 'rm -rf "$CTX"' EXIT
fail() { echo "check_build: FAIL: $*" >&2; exit 1; }

mkdir -p "$CTX/frontend" "$CTX/tests/fixtures"
git -C "$ROOT" archive HEAD server/frontend | tar -x -C "$CTX" --strip-components=1
git -C "$ROOT" show HEAD:server/tests/fixtures/recurrence_vectors.json > "$CTX/tests/fixtures/recurrence_vectors.json"
[ ! -e "$CTX/../shared" ] || fail "$CTX/../shared exists, so this build would not match the image's"

echo "check_build: npm ci && npm run build in an image-shaped folder (commit $(git -C "$ROOT" rev-parse --short HEAD))"
(cd "$CTX/frontend" && npm ci --no-audit --no-fund >/dev/null && npm run build >/dev/null) \
  || fail "1. the web app does not build the way the engine image builds it (npm ci && npm run build)"
echo "ok 1: the production build succeeds"

OUT="$CTX/static/app"
SW="$OUT/sw.js"
[ -f "$SW" ] || fail "2. the build wrote no service worker at static/app/sw.js"
! grep -qE '\.(wasm|onnx|glb)' "$SW" || fail "2. the service worker precaches a .wasm, .onnx or .glb file"
echo "ok 2: the service worker precaches no .wasm/.onnx/.glb"

ASSETS="$OUT/assets"
EXTRA=$(ls "$ASSETS" | grep -E '\.(wasm|onnx|glb)$' | grep -vE '^ort-wasm-simd-threaded-[A-Za-z0-9_-]+\.wasm$' || true)
[ -z "$EXTRA" ] || fail "3. the build output holds a new .wasm/.onnx/.glb: $EXTRA"
echo "ok 3: no new .wasm/.onnx/.glb in the build output"

STAGE=$(ls "$ASSETS" | grep -E '^model-stage-.*\.js$' || true)
[ "$(printf '%s\n' "$STAGE" | grep -c .)" = 1 ] || fail "4. expected exactly one lazy model-stage chunk, found: ${STAGE:-none}"
! grep -q 'WebAssembly' "$ASSETS/$STAGE" || fail "4. the 3D chunk $STAGE instantiates WebAssembly (a Draco or meshopt decoder?)"
! grep -q "$STAGE" "$SW" || fail "4. the 3D chunk $STAGE is in the service worker precache"
echo "ok 4: the 3D chunk $STAGE ($(wc -c < "$ASSETS/$STAGE") bytes) is lazy and not precached"

MAIN=$(grep -o 'src="/assets/index-[^"]*\.js"' "$OUT/index.html" | head -1 | sed 's#^src="/assets/##;s/"$//')
[ -n "$MAIN" ] || fail "5. index.html names no /assets/index-*.js main chunk"
! grep -q 'WebGLRenderer' "$ASSETS/$MAIN" || fail "5. three.js (WebGLRenderer) is in the main chunk $MAIN"
for f in $(grep -oE 'assets/[A-Za-z0-9._-]+\.js' "$SW" | sort -u); do
  ! grep -q 'WebGLRenderer' "$OUT/$f" || fail "5. the precached $f carries three.js (WebGLRenderer)"
done
echo "ok 5: no three.js in the main chunk $MAIN ($(wc -c < "$ASSETS/$MAIN") bytes) or any precached chunk"
echo "check_build: all 5 checks passed"
