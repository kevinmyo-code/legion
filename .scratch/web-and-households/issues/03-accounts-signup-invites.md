---
map: web-and-households
ticket: "03"
title: "Accounts: signup, create a household, invite codes, join, members; session auth for the browser"
type: build
status: built
status-detail: >
  Built in full 2026-09-10 (feat/accounts), on top of the session/CSRF slice
  that landed the same morning. Landed: the `Invite` model and migration
  0003 (code, nullable household, creates_household, created_by, max_uses,
  used_count, expires_at, revoked_at, plus a CHECK that an invite either
  joins a household or creates one); POST /api/auth/signup, throttled on its
  own `signup` scope at 5/min, atomic, taking a row lock on the invite so
  max_uses is real; GET /api/auth/invite/<code>, which describes a code
  without spending it for ticket 05's /join screen and shares the signup
  throttle so codes cannot be enumerated across two doors; GET/PATCH
  /api/households/me; POST/GET/DELETE on /api/households/me/invites;
  DELETE /api/households/me/members/<user_id>, which revokes the removed
  person's device tokens AND the invites they minted and refuses to remove
  the last owner in words; GET/DELETE /api/auth/devices; LEGION_OPEN_SIGNUP
  (off by default, and an invite code is still honoured when it is on);
  and `manage.py create_household --name --owner-email [--password] [--id]`.
  52 new tests in tests/test_accounts.py, openapi.yaml and
  frontend/src/api/schema.d.ts regenerated.
  OWED: no run against a real deployment - every claim here is from the
  pytest suite against the remote Postgres, nothing has been exercised
  through a browser, and the /join/<code> screen that consumes the preview
  endpoint is ticket 05 and does not exist yet. GET /api/auth/me still does
  NOT carry a household field: this ticket's own table asked for one and
  tests/test_tenancy.py forbids it, so household display is GET
  /api/households/me instead.
blockers: ["02"]
blocked-by: ["[[02b-rls-belt]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Accounts

## Model (`server/household/models.py`)

```python
class Invite(models.Model):
    code = models.CharField(max_length=12, unique=True)           # secrets.token_urlsafe(9)[:12]
    household = models.ForeignKey(Household, null=True, on_delete=models.CASCADE)  # null until first use when creates_household
    creates_household = models.BooleanField(default=False)
    created_by = models.ForeignKey(User, on_delete=models.CASCADE, related_name="invites")
    max_uses = models.PositiveSmallIntegerField(default=2)
    used_count = models.PositiveSmallIntegerField(default=0)
    expires_at = models.DateTimeField()                            # default now + 14 days
    revoked_at = models.DateTimeField(null=True, blank=True)
```

`creates_household=True` and `household` null: the first signup with this code creates a household
(named by the signer-up) and becomes its owner; the code's `household` is then set and later uses
join it. That is how one code reaches both parents.

## Endpoints (`server/household/urls.py`, all under `/api/auth/` or `/api/households/`)

| Method, path | Auth | Does | Refuses in words |
|---|---|---|---|
| `POST /api/auth/signup` `{email, password, name, invite_code, household_name?, device_name}` | none, throttle `signup` 5/min | Validates code (live, unexpired, uses left); creates user; joins or creates household; issues a device token like login | Bad or spent code (400, names which); email taken (400); `household_name` missing when the code creates one (400). `LEGION_OPEN_SIGNUP=true` makes `invite_code` optional and always creates a household |
| `POST /api/auth/session/login` `{email, password}` | none, throttle `login` | Django session for the browser, sets `csrftoken` | 401 as `LoginView` |
| `POST /api/auth/session/logout` | session | Ends the session | - |
| `GET /api/auth/csrf` | none | Ensures the CSRF cookie exists; body `{}` | - |
| `GET /api/auth/me` | token or session | Existing, plus `household: {id, name, role}` | 403 with no household, as today |
| `GET /api/households/me` | member | Household + members `[{user_id, email, name, role, joined_at}]` | - |
| `PATCH /api/households/me` `{name}` | owner | Rename | 403 member |
| `POST /api/households/me/invites` `{creates_household?, max_uses?, expires_in_days?}` | owner | Mints a code; returns it ONCE with a ready-to-share URL `https://<host>/join/<code>` | 403 member |
| `GET /api/households/me/invites` | owner | Live invites (never the code of a spent one) | - |
| `DELETE /api/households/me/invites/<code>` | owner | Revokes | 404 |
| `DELETE /api/households/me/members/<user_id>` | owner | Removes a member and revokes their device tokens; owner cannot remove themself while the last owner | 400 in words |
| `GET/DELETE /api/auth/devices` | member | List own device tokens; revoke one by id | - |

**As built, 2026-09-10, one row of that table differs and the difference is
deliberate.** `GET /api/auth/me` does NOT carry `household: {id, name, role}`.
`tests/test_tenancy.py::test_no_openapi_component_declares_household_id` is a
standing rule that no OpenAPI component may declare a `household` or
`household_id` property at all, and it caught the first attempt to add one
here. Household display is `GET /api/households/me`, which is the household
resource and may name itself; `/me` returns the same three fields it always
did. Two other rows gained detail rather than changing: `DELETE
/api/households/me/members/<user_id>` also revokes the invites that person
minted (a removed person keeping a live code is the same failure as keeping a
working phone), and `GET /api/auth/invite/<code>` shares the `signup` throttle
scope so a stranger gets five guesses a minute across both doors rather than
five at each.

DRF settings: `DEFAULT_AUTHENTICATION_CLASSES = [DeviceTokenAuthentication, SessionAuthentication]`
(token first so a phone never pays a session lookup). CSRF applies only on the session path, which
is the browser's, and the SPA sends `X-CSRFToken` from the cookie (ticket 04's client middleware).

`manage.py create_household --name --owner-email` and `add_household_member --household` replace
today's single-household command for the compose bootstrap path (django-engine 11).

## Tests

`tests/test_accounts.py`: signup with a join code, with a create code (first and second use land in
the same new household), with a spent code, with an expired code, with open signup on/off; owner vs
member on every owner-only route; removing a member revokes their tokens; session login then a
synced-table read scoped to the right household.

## Done means

All green, `openapi.yaml` regenerated, the phone's `MeView` consumer (`EngineAuth.me`) still parses
the response (additive field, verified by the existing Kotlin test).
