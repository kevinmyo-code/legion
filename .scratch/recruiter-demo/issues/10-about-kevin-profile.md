---
map: recruiter-demo
ticket: "10"
title: "about_kevin: recruiters ask about Kevin himself"
type: decision
status: open
status-detail: "Kevin writes the profile"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# about_kevin: recruiters ask about Kevin himself

## What (Kevin, 2026-11-02)

*"personal questions can be answered here too. like ask about my work experience, basically things a
recruiter might want to ask me. where i live, what nationality etc etc"*

- One read tool, `about_kevin`, recruiter role only. Returns a profile Kevin wrote and approved:
  experience, skills, education, location, nationality / work authorisation, availability, what he
  is looking for, how to reach him.
- Answers ONLY from that profile. Anything not in it: "Kevin hasn't shared that here; ask him
  directly." Never inferred from household data, which the role cannot read anyway.
- Profile stored in the engine DB (admin-editable), NOT in this repo: the repo is public, and Kevin
  should change a line without a commit.
- Quanex lines follow ticket 09's rules.

## Decide (Kevin)

1. Which fields, and his wording for each (work authorisation / visa; salary: answer or deflect?).
2. Location: city, metro, or "Houston area, open to relocation"?
