---
map: backend-erp
ticket: "02"
title: "Auth: two users now, more later, no roles ever"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Auth: two users now, more later, no roles ever

## Question

Supabase Auth presumably. Decide: sign-in method (email magic link? Google OAuth - the app already
has Google sign-in plumbing for Drive); whether all users see all data (the household model -
recommend yes, it is the current two-adult trust model made explicit) or per-user rows exist (RLS
by household id, users join a household); how a new user joins (invite link?); what happens to
per-device identities (DeviceId) and CompanionProfile personas per user; key storage on Android
(Keystore, same posture as the Gemini key).
