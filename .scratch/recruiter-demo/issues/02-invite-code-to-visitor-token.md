---
map: recruiter-demo
ticket: "02"
title: "Invite code to per-visitor read token, ref tag, per-IP limit, rotation"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-recruiter-role-and-allowlist]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Invite code to per-visitor read token, ref tag, per-IP limit, rotation

## What

Kevin shares ONE invite code with every recruiter (ruling 3). The code is never a `DeviceToken`.

- `POST /api/demo/enter {code, ref?}`: checks the code (stored hashed), throttled per IP, then creates
  a throwaway visitor `User` in the demo household and issues a read-scoped token, role `recruiter`,
  2 h expiry, `ref` stored (from `/try?ref=acme` on the resume link).
- Per-visitor users keep today's per-member throttle meaningful and give the audit log one row per
  visitor, tagged with `ref`.
- `manage.py demo_code rotate` issues a new code and revokes the old; `demo_code off` closes the door.
- Expired visitor users and tokens pruned nightly (with ticket 03's reset).

## Verify

- Test: wrong code, expired token, revoked code all refuse in words.
- Test: per-IP limit on `/enter` holds.
