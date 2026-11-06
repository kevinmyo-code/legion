---
map: fat-loss
ticket: "03"
title: "Engine: the body profile"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-kevins-rulings]]"]
tags: [ticket]
---
# Engine: the body profile

Build item A. One row per member, `server/body_coach/` model `BodyProfile`, table `body_profiles`.

- Fields: `household`, `owner_user` (the member it describes; unique per household and user),
  `height_mm` (int), `sex` (`male` | `female`; Mifflin-St Jeor needs it), `birth_year` (int),
  `activity_level` (`sedentary` 1.2, `light` 1.375, `moderate` 1.55, `very` 1.725),
  `target_rate_bp` (basis points of bodyweight per week, default 75, clamped 25 to 100),
  `protein_tenths_g_per_kg` (default 20, 16 to 22), `weekly_line_on` (default true, ruling 7),
  `created_at`, `updated_at`.
- Member-private per ADR 0052: read and written only by its own member, through
  `household.tenancy`; another member's row is a 404. Covered by visibility tests and the tenancy
  test. Additive migration.
- `GET/PUT /api/body/coach/profile/`. `PUT` is partial; `height_cm` on the wire (or `height_in`),
  stored as mm. `missing` lists what the calculator still needs.
- Hands path: the web `/body` form (ticket 09). Voice: `set_body_profile` engine tool (ticket 08).
- Synthetic people in every test.
