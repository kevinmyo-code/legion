---
status: accepted
decided: 2026-11-02
decided-by: Kevin
source: "[[decisions#2026-11-02 - Recruiter is a third household role, and it only narrows (Kevin)]]"
tags: [adr]
---

# 68. Recruiter is a third household role, and it only narrows

## Standing

**A `HouseholdMember` may have the role `recruiter`.** It is set only on throwaway visitor users in
the demo household, made by `POST /api/demo/enter`. It is the first role that governs data, and it
only ever takes access away. This amends [[0045-households-are-tenants]]'s "no roles inside a
household except `owner`". `owner` and `member` are unchanged.

## Context

Kevin gives recruiters a link and one shared invite code so they can try LEGION on the live engine.
They must read a fake household and never reach Kevin's data, his accounts or his machines. Tenancy
already keeps them in the demo household. Inside it, the web assistant and the REST API hand every
member everything, which is too much for a stranger.

## Decision

- **Role follows the user.** A session and a token for the same visitor are both recruiter. The
  browser cannot say what it is.
- **Default deny on routes.** `IsHouseholdMember` refuses a recruiter on every route unless the view
  sets `allows_recruiter = True`. Today only the two assistant doors do (`/api/assistant/session`,
  `/api/assistant/tool`). `/mcp` refuses.
- **Default deny on tools.** A recruiter's assistant may call `assistant.surface.RECRUITER_TOOLS`
  only, read tools over ledger, fleet, pantry, notes, dates and places. The same list builds the
  locked Live token's declarations and gates the tool door. A tool added to the registry does not
  reach a recruiter until someone lists it.
- **No writes by any credential.** `household.permissions.write_refusal` refuses a recruiter before
  the token scope is even read. The visitor token is also `read` scope. Either fence alone holds.
- **Visits end.** The visitor token expires in two hours (`DeviceToken.expires_at`). The browser
  session dies with it. `manage.py prune_demo_visitors` deletes expired visitors and keeps the visit
  row with its `ref`.

## Consequences

- The shared code is stored hashed, unlike `household.Invite`. It is shown once by
  `manage.py demo_code rotate`. `demo_code off` closes the door.
- The assistant stays session-only (ADR 0056). The visitor token does not open it; the session
  `/api/demo/enter` sets beside the token does.
- A role that WIDENS access is still a new ruling, not a refactor.
