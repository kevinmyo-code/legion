# Phone vs web assistant parity (inventory, 2026-11-02)

Read-only trace of branch docs/recruiter-demo. Kevin's ruling the same day: *"everything i can do on
the phone save the phone only functions i should be able to do on the web legion page and by extension
the recruiter on the demo mode"*. Counts computed from `tools/voice_guide.py` TOOLBOXES (127 names).

| Class | Count | Meaning |
|---|---|---|
| ON WEB already | 42 | an engine tool covers it for members (some partial) |
| PORTABLE | 46 | could run on the engine; 9 of these are the aspect engine (needs new schema) |
| PHONE-ONLY | 26 | OBD, navigation, calls, music App Remote, volume, voice-note recording, show_app, books TTS... |
| RULED OUT | 7 | mail x5, memory x2 (standing rulings) |
| SUBSUMED | 5 | ask_* dispatchers; the web model reads the tables itself |
| DORMANT | 1 | get_mpg (always refuses) |

## Facts that shape the plan

- Recruiters reach data ONLY through the assistant tool door; every other `/api/*` route refuses them
  (`household/permissions.py`). With ticket 13 (own household copy) recruiters could use the normal
  web screens on their copy instead.
- Recruiter `read_records` aspects: ledger, fleet, pantry, places. `voice_notes` is seeded but fenced
  out (mismatch). Body is excluded and unseeded.
- Partial ON WEB: manage_checklist (no create/untick/remove tools; routes exist in
  `checklists/urls.py`), event skip (route `api/urls.py` event_skips, no tool), set_reminder (storage
  yes, arrival trigger phone-only).

## Top PORTABLE by demo value (server code mostly exists)

1. get_next_service: `brief/compose.py` `_maintenance_lines`, `_latest_odometer`.
2. get_monthly_spend: wrap `SpendView` (`api/urls.py` ledger/spend).
3. get_balance: statements with anchors are seeded.
4. check_recalls: keyless NHTSA.
5. get_grocery_spend.
6. get_sitrep: wrap `BriefLatestView`; demo brief must not pull real news/Canvas.
7. triage_symptom ($ Gemini). 8. show_generated_view ($, needs web render). 9. area_info (keyless,
   demo city). 10. ask_advisor ($, needs advisor table + agent).
Close behind: rename_place (route exists), diagnose_codes with named codes ($).

## Needs new server schema

goals (table exists in legacy models, no viewset/tool), advisor_advice, proactive raises, the aspect
engine (aspects, record types, field defs, records: nothing on the server).

## Stale voice-guide copy found

read_calendar copy says "Google Calendar" but reads the local events table; get_spend copy implies
ledger spend but returns car build-sheet spend.

## Not verified

places delete via delete_record; COLD_START markers reaching server obd_samples; demo `categories` rows.
