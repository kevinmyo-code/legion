---
map: books
ticket: "01"
title: "Decision: Books is a HOME launcher section with LEGION's own reader"
type: decision
status: resolved
status-detail: "Kevin 2026-10-19: prototype A, EPUB+PDF+comics, phone folder, phone-only progress, device TTS. ADR 0061"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Decision: Books on HOME

Rulings (Kevin, 2026-10-19), recorded in `memory/library/decisions.md` and ADR 0061:

1. **Placement: prototype A.** A sixth chip, **Books**, beside Games; the chip row becomes two rows
   of three (To-dos, Lists, Ideas / Games, Books, More). Its own sheet. `research/Main.dc.html`.
2. **Formats: EPUB, PDF and comics (CBZ, CBR).**
3. **Source: one folder the user grants** through the system picker (SAF tree, normally
   `/sdcard/Books`), like the ROMs folder. Nothing is uploaded.
4. **Progress, bookmarks and settings stay on the phone.** Nothing reaches the engine.
5. **Read-aloud with the phone's own TTS**, offline, from the current position, with a play/pause
   bar and the spoken sentence highlighted. A voice tool and the Listen button call the same
   controller (ADR 0035).

**Books is not an aspect.** Like Games (ADR 0058) it is a launcher; the six aspects are unchanged.
