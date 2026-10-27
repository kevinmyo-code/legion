---
status: accepted
decided: 2026-10-27
decided-by: Kevin
supersedes: [0047-switching-companion-is-a-handover]
source: "[[decisions#2026-10-27 - Phone: Alfred is the only persona (Kevin)]]"
tags: [adr]
---

# 63. The phone has one persona, Alfred

## Standing

**The phone app has exactly one assistant persona, `ALFRED` in `ai/Personas.kt`.** There is no
roster, no picker and no `switch_companion` voice tool. Alfred's name and voice stay editable on the
Companion screen (`settings/companions`).

## Context

The phone carried four built-in registers (Alfred, Dorothy, Kratos, Marcus), a roster screen to pick
between them, a voice tool to hand the conversation from one to another, and a Marcus-only
Meditations reader and tool. Kevin ruled that the phone has one assistant.

## Decision

- `personaFor(key)` returns `ALFRED` for every key, so a stored `dorothy`, `kratos` or `marcus` key
  answers in Alfred's register. Nothing is migrated in Room: `companion_profiles` and its sync
  contract are unchanged, and no row is deleted (CLAUDE.md §5, additive-only).
- When this device's active profile is not an Alfred row and the synced roster holds one, the active
  selection moves to that Alfred row the next time the active profile is materialised
  (`CompanionProfileStore.materializeActive`, at app start and after every sync). With no Alfred row,
  the active profile stays and still answers as Alfred.
- The Companion screen edits the active profile's name and voice and writes `persona = "alfred"` on
  save. On an install with no profile it creates one from Alfred's defaults.
- Meditations is deleted: the reader, the `consult_meditations` tool and the bundled text.

## Consequences

- **ADR 0047 is superseded.** With one persona there is nothing to hand over to, so the socket
  rebuild and spoken handover it describes are gone with the voice tool and the tap-to-switch.
- **Editing Alfred's voice does not rebuild a warm socket**, the same as editing a profile did
  before; the next socket picks it up.
- **The name is still not hardcoded** (CLAUDE.md §1). A profile can be Alfred's register under any
  name the household gives it.
