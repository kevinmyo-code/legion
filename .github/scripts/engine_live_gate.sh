#!/usr/bin/env bash
# engine_live_gate.sh: the engine's live gate as today's laptop ship scripts run it (ship_engine_*_1121.sh,
# step 1c), for .github/workflows/gates.yml. Runs from the workflow's TRUSTED checkout; the tests run in
# the gated commit's server/ tree on a throwaway Postgres (the job's service container).
#
#   engine_live_gate.sh <server dir> <out dir> <extra checks, comma list or empty> <extra cap USD>
#
# Environment: LEGION_GEMINI_KEY_FILE, DATABASE_URL and the Django test settings (set by the job);
# PYTEST (default: "uv run --no-sync pytest").
#
#  1. The standard gate: tests/realrun_web_cascade.py -k realrun_gate, spend cap 0.09 USD. A pass is
#     pytest exit 0 AND the test's own "GATE PASSED" line (a budget skip exits 0 without it).
#     GATE_RULE fail_twice, heard-back only: when the ONLY failure is one of the two heard-back asserts
#     (the gate's own transcription of the reply audio, gate_money), the WHOLE gate runs once more in a
#     fresh folder with a fresh 0.09 cap; any other first failure stops with no retry.
#  2. One real chip turn: tests/realrun_demo_suggestions.py, under the demo's own budget lowered to 0.40 USD
#     (REALRUN_DEMO_BUDGET_USD); exactly one test passed, none skipped. The cap bounds what a turn may
#     RESERVE, not what it spends: a greeting reserves its worst case up front, about 0.18 USD on a
#     fresh runner with no filler audio cached, so 0.10 refused it with a 429. The greeting may spend
#     most of that if it makes every filler clip, and the chips call and chip turn reserve about 0.09
#     more after it, so 0.40 leaves room. A passing turn still spends about a cent.
#  3. Extra checks a ship names (at most 3): repo files server/tests/realrun_<name>.py AT THE GATED
#     COMMIT (never passed in as code), each with its own folder and the extra cap; a pass is pytest
#     exit 0 AND a line "<WORDS> VERDICT: PASS" (the carry checks print "CARRY VERDICT: PASS").
# Writes <out dir>/summary.txt: one line per step, verdicts and spend estimates only (no model words).
# Raw pytest output stays in <out dir>/raw/ on the runner and is never uploaded.
set -uo pipefail
SERVER=${1:?server dir}; OUT=${2:?out dir}; EXTRAS=${3:-}; EXTRA_CAP=${4:-0.12}
PYTEST=${PYTEST:-uv run --no-sync pytest}
RAW=$OUT/raw
mkdir -p "$RAW"
SUMMARY=$OUT/summary.txt
: > "$SUMMARY"
note() { echo "$*"; echo "$*" >> "$SUMMARY"; }
fail() { note "RESULT: FAIL ($*)"; exit 1; }
spent() { cat "$1/spent.txt" 2>/dev/null || echo "?"; }
# Why a pytest run did not pass, from repo text only (never the model's words, review G1): pytest's
# tally, the first failing source line (`>` lines are the test's own code), the error's class name,
# and a skip's location. Exception messages (`E` lines past the class) and printed output stay here.
why() {
  local t src err skip
  t=$(grep -oE '[0-9]+ (passed|failed|skipped|errors?)' "$1" | tr '\n' ' ' | sed 's/ $//')
  src=$(grep -m1 -E '^>[[:space:]]+' "$1" | sed -E 's/^>[[:space:]]+//' | cut -c1-160)
  err=$(grep -m1 -oE '^E[[:space:]]+[A-Za-z_][A-Za-z0-9_.]*(Error|Exception|Exit|Iteration|Skipped|Failed)\b' "$1" | awk '{print $2}')
  skip=$(grep -m1 -oE '^SKIPPED \[[0-9]+\] [A-Za-z0-9_./-]+:[0-9]+' "$1" | cut -d' ' -f3)
  echo "tally [${t:-none}] at [${src:-none}] error [${err:-none}] skip [${skip:-none}]"
}

[[ "$EXTRA_CAP" =~ ^0\.[0-9]{1,3}$ ]] || fail "extra cap must look like 0.12"
awk -v c="$EXTRA_CAP" 'BEGIN { exit !(c + 0 <= 0.30) }' || fail "extra cap above 0.30 USD"

HEARD_BACK_ASSERTS='assert not stray, (stray, row.get("heard_back"))
assert heard, ("no amount heard in a spending answer", row.get("heard_back"))'

gate_run() {  # $1 folder; returns pytest's exit code
  mkdir -p "$1"
  ( cd "$SERVER" && REALRUN_OUT="$1" REALRUN_BUDGET_USD=0.09 $PYTEST -q -p no:cacheprovider \
      tests/realrun_web_cascade.py -k realrun_gate -s ) > "$1/pytest.txt" 2>&1
}
gate_passed() { [ "$1" = 0 ] && grep -q '^GATE PASSED' "$2/pytest.txt"; }
gate_class() {
  local out=$1/pytest.txt n fails line
  n=$(grep -cE '^>[[:space:]]+assert ' "$out" || true)
  fails=$(grep -oE '^[0-9]+ failed' "$out" | head -1 | cut -d' ' -f1 || true)
  line=$(grep -m1 -E '^>[[:space:]]+assert ' "$out" | sed -E 's/^>[[:space:]]+//' | tr -d '\r' || true)
  if [ "$n" = 1 ] && [ "${fails:-0}" = 1 ] && printf '%s\n' "$HEARD_BACK_ASSERTS" | grep -qxF -- "$line"; then
    echo HEARD_BACK
  else
    echo OTHER
  fi
}

# 1. the standard gate
G1=$RAW/gate; gate_run "$G1"; rc=$?
if gate_passed "$rc" "$G1"; then
  note "gate: PASS first run (spent about \$$(spent "$G1"))"
elif [ "$(gate_class "$G1")" = HEARD_BACK ]; then
  note "gate: first run FAIL on a heard-back assert only; whole gate once more (fail_twice)"
  G2=$RAW/gate-retry; gate_run "$G2"; rc=$?
  gate_passed "$rc" "$G2" || fail "gate failed twice"
  note "gate: PASS on the retry (spent about \$$(spent "$G1") + \$$(spent "$G2"))"
else
  fail "gate: first run FAIL, not a heard-back assert: no retry"
fi

# 2. one real chip turn
( cd "$SERVER" && REALRUN_DEMO_BUDGET_USD=0.40 $PYTEST -q -p no:cacheprovider -rs tests/realrun_demo_suggestions.py -s ) > "$RAW/chip.txt" 2>&1
rc=$?
if [ "$rc" = 0 ] && grep -qE '(^| )1 passed' "$RAW/chip.txt" && ! grep -qE '[0-9]+ skipped' "$RAW/chip.txt"; then
  note "chip turn: PASS"
else
  note "chip turn: $(why "$RAW/chip.txt")"
  fail "chip turn did not pass (exit $rc, or skipped)"
fi

# 3. extra checks named by the ship (repo files at the gated commit)
n=0
IFS=',' read -r -a NAMES <<<"$EXTRAS"
for name in "${NAMES[@]}"; do
  name=$(echo "$name" | tr -d '[:space:]')
  [ -n "$name" ] || continue
  n=$((n + 1))
  [ "$n" -le 3 ] || fail "more than 3 extra checks"
  [[ "$name" =~ ^realrun_[a-z0-9_]+$ ]] || fail "extra check name '$name' must look like realrun_<words>"
  [ -f "$SERVER/tests/$name.py" ] || fail "server/tests/$name.py is not in the gated commit"
  X=$RAW/$name; mkdir -p "$X"
  ( cd "$SERVER" && REALRUN_OUT="$X" REALRUN_BUDGET_USD="$EXTRA_CAP" $PYTEST -q -p no:cacheprovider \
      "tests/$name.py" -s ) > "$X/pytest.txt" 2>&1
  rc=$?
  if [ "$rc" = 0 ] && grep -qE '^[A-Z][A-Z _]* VERDICT: PASS$' "$X/pytest.txt"; then
    note "extra $name: PASS (spent about \$$(spent "$X"), cap \$$EXTRA_CAP)"
  elif [ "$rc" != 0 ]; then
    fail "extra $name: FAIL (a rule broken or a crash; exit $rc)"
  else
    fail "extra $name: NO VERDICT (no 'VERDICT: PASS' line; a budget skip?)"
  fi
done
note "RESULT: PASS"
