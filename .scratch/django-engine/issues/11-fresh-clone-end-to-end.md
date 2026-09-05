---
map: django-engine
ticket: "11"
title: "Fresh clone, end to end: the clone-and-run test in its new shape"
type: test
status: open
blockers: ["10"]
blocked-by: ["[[10-cutover]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Fresh clone, end to end

ADR 0003 (clone-and-run) restated by ADR 0044: a stranger clones, runs the server, makes two users,
points the app at the URL, and it works. This ticket is that sentence performed on a machine that
has never seen LEGION, with nothing copied from Kevin's.

## Script

1. A clean VM or a second laptop. `git clone`, `cd deploy`, `cp .env.example .env`, fill the three
   values, `docker compose up -d`. `GET /healthz` green.
2. `docker compose exec web python manage.py createsuperuser` twice.
3. `docker compose exec web python manage.py migrate` already ran on start; `makemigrations
   --check` clean.
4. Build the APK with `-Pnokey`, sideload, enter the server URL and one user's credentials, sign in.
5. Open the web app in Safari on an iPhone as the other user, add to home screen.
6. One event by voice on the phone; it is on the iPhone within 60 s. One tick on the iPhone; it is
   on the phone within 60 s.
7. `docker compose stop web`. The phone shows yesterday's data and says the server is unreachable,
   in words. A voice-added event lands in the outbox. `docker compose start web`; it drains once.
8. `docker compose exec worker python manage.py backup_nightly`; a backup directory exists.

## Verification

- [ ] Every step above done on hardware, with the device agent's screenshots kept (memory:
      device agents delete their screenshots unless told not to).
- [ ] `deploy/README.md` was sufficient: no step needed knowledge that is not in it. Anything
      that did is added to the README in this ticket.
