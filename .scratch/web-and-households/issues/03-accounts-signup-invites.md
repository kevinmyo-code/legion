---
map: web-and-households
ticket: "03"
title: "Accounts: signup, create a household, invite codes, join, members; session auth for the browser"
type: build
status: open
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
