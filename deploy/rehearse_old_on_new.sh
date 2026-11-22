#!/usr/bin/env bash
# rehearse_old_on_new.sh <LIVE_CODE> <FIX>: the CI port of the laptop ship's rehearsal (2026-11-21), for
# .github/workflows/ship.yml. Any number of ADDED schema files; each app reverses to its newest migration at
# LIVE_CODE, or zero for an app new since LIVE_CODE.
#
# One question, on a THROWAWAY Postgres (the job's service container, never prod): can the code that is live
# now (LIVE_CODE) keep serving while the NEW schema (FIX's schema files applied on top of LIVE_CODE's)
# exists? That is what a traffic rollback leaves behind, since schema changes stay applied. Prints PASS, or
# the step that failed and whether that step is the REHEARSAL's own setup (exit 3) or the PRODUCT (exit 1).
#
#   1 setup    worktrees at LIVE_CODE and FIX, three databases
#   2 setup    LIVE_CODE's migrations, then FIX's on top (as production meets them), on all three
#   3 product  FIX: makemigrations --check (no schema file missing)
#   4 product  LIVE_CODE: manage.py check --database default, on the new schema
#   5 setup    the legacy test schema replayed from FIX's tests/conftest.py django_db_setup and a check member
#   6 product  LIVE_CODE deletes the member on the new schema
#   7 product  LIVE_CODE's tests for the changed apps, tenancy and visibility, on the new schema, EXCEPT the
#              tests in EXCLUDED (each named with its reason)
#   8 product  each app's schema files reverse (newest app first) and re-apply
#
# Environment: PGURL (a server where databases may be created, e.g. postgres://postgres:postgres@localhost:5432),
# PY_OLD (LIVE_CODE's own environment: its requirements.txt) and PY_NEW (FIX's), both python executables.
# The laptop version's SEED_PY / SEED_COUNT_SQL (code run against the database) are NOT carried here: a
# dispatch input must never be code. A ship that needs seeded rows rehearses on the laptop.
set -euo pipefail
exec </dev/null
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8 GIT_TERMINAL_PROMPT=0
LIVE_CODE=${1:-}; FIX=${2:-}
[[ "$LIVE_CODE" =~ ^[0-9a-f]{40}$ ]] && [[ "$FIX" =~ ^[0-9a-f]{40}$ ]] || { echo "usage: rehearse_old_on_new.sh <LIVE_CODE full sha> <FIX full sha>"; exit 2; }
: "${PGURL:?PGURL must name a throwaway Postgres server}" "${PY_OLD:?}" "${PY_NEW:?}"

EXCLUDED='tests/test_tenancy.py::test_every_tenant_table_that_exists_here_has_the_column | table inventory: compares every public table with a household column against the OLD code'"'"'s TENANT_TABLES, which cannot name tables a newer schema file adds
tests/test_tenancy.py::test_no_public_table_outside_the_list_carries_the_column | table inventory: same comparison, the other direction; any new table with a household column fails it by construction'

REPO=$(git rev-parse --show-toplevel)
BOOT_ID=11111111-1111-4111-8111-111111111111
DB=oldnew
RDB=rehearsal_test_oldnew
WORK=$(mktemp -d)
OLDWT=$WORK/old; NEWWT=$WORK/new
finish() {
  rc=$?
  git -C "$REPO" worktree remove --force "$OLDWT" >/dev/null 2>&1 || true
  git -C "$REPO" worktree remove --force "$NEWWT" >/dev/null 2>&1 || true
  return $rc
}
trap finish EXIT
setup_fail() { echo "REHEARSAL SETUP FAILED at step $1: $2. This says nothing about the product."; exit 3; }
product_fail() { echo "PRODUCT FAILED at step $1: $2"; exit 1; }
sql() { psql "$PGURL/$1" -v ON_ERROR_STOP=1 -tAc "$2"; }

echo "== rehearse_old_on_new LIVE_CODE ${LIVE_CODE:0:8} FIX ${FIX:0:8} $(date -u +%FT%TZ)"
for s in "$LIVE_CODE" "$FIX"; do git -C "$REPO" cat-file -e "$s^{commit}" 2>/dev/null || setup_fail 1 "$s is not in this checkout"; done
git -C "$REPO" merge-base --is-ancestor "$LIVE_CODE" "$FIX" || setup_fail 1 "LIVE_CODE is not an ancestor of FIX"
MIGS=$(git -C "$REPO" diff --no-renames --name-status "$LIVE_CODE" "$FIX" -- '*/migrations/*')
echo "schema files LIVE_CODE..FIX: ${MIGS:-none}"
MIG=$(echo "$MIGS" | awk '$1=="A" && $2 ~ /^server\/[^\/]+\/migrations\/[0-9][^\/]*\.py$/ {print $2}')
[ -z "$(echo "$MIGS" | awk 'NF && $1!="A"')" ] || setup_fail 1 "a schema file is modified or deleted, not added: $MIGS"
APPS=""; declare -A PREV_OF=()
for m in $MIG; do
  app=$(echo "$m" | cut -d/ -f2)
  if [ -z "${PREV_OF[$app]:-}" ]; then
    prev=$(git -C "$REPO" ls-tree --name-only "$LIVE_CODE" "server/$app/migrations/" | sed -n 's#.*/\([0-9][0-9][0-9][0-9]_[^/]*\)\.py$#\1#p' | LC_ALL=C sort | tail -1)
    PREV_OF[$app]=${prev:-zero}
    APPS="$APPS $app"
  fi
  echo "schema change: $app $(basename "$m" .py) (reverse target ${PREV_OF[$app]})"
done
APPS=$(echo $APPS)
for d in "$DB" "test_$DB" "$RDB"; do
  sql postgres "drop database if exists $d" >/dev/null && sql postgres "create database $d" >/dev/null || setup_fail 1 "create database $d"
done
git -C "$REPO" worktree add -q --detach "$OLDWT" "$LIVE_CODE" || setup_fail 1 "worktree at LIVE_CODE"
git -C "$REPO" worktree add -q --detach "$NEWWT" "$FIX" || setup_fail 1 "worktree at FIX"
dj() {  # dj <worktree> <db> <manage.py args...>: LIVE_CODE's tree runs on PY_OLD, FIX's on PY_NEW
  local wt=$1 db=$2 py; shift 2
  if [ "$wt" = "$OLDWT" ]; then py=$PY_OLD; else py=$PY_NEW; fi
  (export SECRET_KEY=ci-only-not-a-real-secret-key-0000000000 DJANGO_DEBUG=false ALLOWED_HOSTS=localhost \
     MEDIA_ROOT=$(mktemp -d) DATABASE_URL=$PGURL/$db LEGION_BOOTSTRAP_HOUSEHOLD_ID=$BOOT_ID
   cd "$wt/server" && "$py" manage.py "$@")
}
echo "ok 1: worktrees, databases $DB, test_$DB, $RDB"

for d in "$DB" "test_$DB" "$RDB"; do
  dj "$OLDWT" "$d" migrate -v0 || setup_fail 2 "LIVE_CODE's migrations on $d"
  dj "$NEWWT" "$d" migrate -v0 || setup_fail 2 "FIX's migrations on top, on $d"
done
echo "ok 2: LIVE_CODE's schema, then FIX's on top, on all three databases"

dj "$NEWWT" "$DB" makemigrations --check --dry-run -v0 || product_fail 3 "FIX's models need a schema file FIX lacks (makemigrations --check)"
echo "ok 3: makemigrations --check clean at FIX"

dj "$OLDWT" "$DB" check --database default || product_fail 4 "LIVE_CODE's manage.py check --database default fails on the new schema"
echo "ok 4: LIVE_CODE's manage.py check --database default passes on the new schema"

LEGACY='import ast, contextlib, pathlib
import tests.conftest as c
tree = ast.parse(pathlib.Path(c.__file__).read_text(encoding="utf-8"))
fn = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "django_db_setup")
body = fn.body[1:] if isinstance(fn.body[0], ast.Expr) and isinstance(fn.body[0].value, ast.Constant) else fn.body
class _Blocker:
    def unblock(self):
        return contextlib.nullcontext()
ns = dict(vars(c))
ns["django_db_blocker"] = _Blocker()
exec(compile(ast.Module(body=body, type_ignores=[]), c.__file__ + ":django_db_setup", "exec"), ns)
from django.db import connection
with connection.cursor() as cur:
    cur.execute("select to_regclass(%s), to_regclass(%s)", ["public.events", "public.vehicles"])
    ev, ve = cur.fetchone()
assert ev and ve, ("legacy tables missing after the replay", ev, ve)
print("legacy test schema applied from", c.__file__)'
dj "$NEWWT" "$RDB" shell -c "$LEGACY" || setup_fail 5 "replaying FIX's tests/conftest.py django_db_setup on $RDB"
MEMBER='from household.models import Household, HouseholdMember, User
h = Household.objects.get(id="'"$BOOT_ID"'")
u = User.objects.create_user(email="ship-check@example.com", password="ship-check-not-a-real-password-0")
HouseholdMember.objects.create(user=u, household=h, role=HouseholdMember.OWNER)'
dj "$NEWWT" "$RDB" shell -c "$MEMBER" || setup_fail 5 "creating the check member with FIX's code"
echo "ok 5: legacy test schema, a member"

DEL='from household.models import User
print("deleted", User.objects.filter(email="ship-check@example.com").delete())'
dj "$OLDWT" "$RDB" shell -c "$DEL" || product_fail 6 "LIVE_CODE could not delete a member on the new schema"
echo "ok 6: LIVE_CODE deleted the member on the new schema"

OLDFILES=$(cd "$OLDWT/server" && for app in $APPS; do ls tests/test_${app}_*.py 2>/dev/null || true; done; ls tests/test_tenancy.py tests/test_visibility.py)
OLDFILES=$(echo $OLDFILES)
DESEL=()
echo "not applicable to old-code-on-new-schema (deselected, by construction):"
while IFS='|' read -r tid why; do
  tid=$(echo "$tid" | xargs); [ -n "$tid" ] || continue
  echo "  $tid :$why"
  DESEL+=(--deselect "$tid")
done <<<"$EXCLUDED"
echo "old-code pytest on the new schema (test_$DB reused, as built in step 2): $OLDFILES"
# shellcheck disable=SC2086
(cd "$OLDWT/server" && SECRET_KEY=ci-only-not-a-real-secret-key-0000000000 DJANGO_DEBUG=false \
   ALLOWED_HOSTS=localhost,127.0.0.1,testserver MEDIA_ROOT=$(mktemp -d) DATABASE_URL=$PGURL/$DB \
   LEGION_BOOTSTRAP_HOUSEHOLD_ID=$BOOT_ID "$PY_OLD" -m pytest -q -p no:cacheprovider $OLDFILES "${DESEL[@]}" --reuse-db -p no:randomly) \
  || product_fail 7 "LIVE_CODE's tests fail on the new schema (other than the excluded inventory tests)"
echo "ok 7: LIVE_CODE's tests pass on the new schema (excluded: $(echo "$EXCLUDED" | grep -c .))"

if [ -n "$APPS" ]; then
  for app in $(echo $APPS | tr ' ' '\n' | tac); do
    dj "$NEWWT" "$DB" migrate -v0 "$app" "${PREV_OF[$app]}" || product_fail 8 "$app does not reverse to ${PREV_OF[$app]}"
    echo "  reversed $app to ${PREV_OF[$app]}"
  done
  dj "$NEWWT" "$DB" migrate -v0 || product_fail 8 "the schema files do not re-apply after the reverse"
  echo "ok 8: $APPS reverse to their previous migration and re-apply"
else
  echo "ok 8: no schema file to reverse"
fi
echo "PASS: LIVE_CODE ${LIVE_CODE:0:8} keeps serving on FIX ${FIX:0:8}'s schema (steps 1-8)"
