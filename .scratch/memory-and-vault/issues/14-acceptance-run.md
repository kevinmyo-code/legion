---
map: memory-and-vault
ticket: "14"
title: "Acceptance run"
type: test
status: open
status-detail: ""
blockers: ["08", "09", "10", "11", "12", "13"]
blocked-by: ["[[08-worker-memory-tools]]", "[[09-phone-memory-on-engine]]", "[[10-phone-upload-and-retire]]", "[[11-phone-memory-screen]]", "[[12-phone-id-vault]]", "[[13-phone-vehicle-plate]]"]
open-blockers: 6
ready: false
tags: [ticket]
---
# Acceptance run

1. Engine deploy (migrations `member_memory` 0001, `id_vault` 0001, the `ingest` plate migration,
   `claude_session` token binding), then `install_schedule.py` for `consolidate_memories`.
2. Set the Jeep's plate through the API.
3. Run the import (ticket 04 or 10). The count matches the phone's rows.
4. Phone: "remember my gym is on Main Street", then on the web: "where is my gym?".
5. "Forget that". The row is gone from the database, not hidden.
6. A conversation that reads mail, then an hour later: nothing from it in memory.
7. Mia's account cannot see Kevin's private memories; a shared one she can.
8. Vault: add a passport number, reveal with fingerprint, cancel the prompt and see nothing.
9. Work laptop: a task that asks to recall a memory is refused.
