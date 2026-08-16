# Clear DTCs: fleet's first write to the car

Type: grilling
Status: open
Blocked by: -

## Question

LEGION reads codes (`ObdBluetoothManager.getDtcCodes()`, Mode 03 at `ObdBluetoothManager.kt:470`)
but cannot clear them. Clearing is OBD Mode 04, one `sendCommand("04")` on plumbing that already
exists. The command is trivial; the decisions are not, because this is the app's first write to
the vehicle and Mode 04 is destructive three ways: it erases stored codes, erases freeze frame,
and resets readiness monitors and learned fuel trims. Reset monitors mean a failed emissions
inspection until full drive cycles complete.

Decide:

1. **The confirm turn.** Standing preference: destructive actions need an explicit confirm. What
   does Alfred say before clearing - does he always recite the codes about to be lost and the
   readiness warning, or only warn on the first use? Is "clear my codes" ever accepted from a
   one-shot, or only inside a live session?
2. **Snapshot before erase.** After Mode 04 the ECU forgets everything, so LEGION must latch the
   codes and freeze frame into its own history FIRST. Which table - the existing DTC-event shape
   the recap layer reads, or a new `cleared_at` marker on existing rows? Does the maintenance log
   get an entry ("codes cleared at N miles")?
3. **Surfaces.** Voice-only, or also a button on the fleet UI next to where codes render? The
   recall checker shipped both; is that the pattern?
4. **Failure honesty.** Mode 04 can fail silently on some ECUs (the code returns after key cycle
   because the fault is still present). Does LEGION re-read codes immediately after clearing and
   report what it sees, rather than reporting the send succeeded? (The fleet-maintenance map's
   core defect class was the silent no-op - the assistant reporting a change it could not have
   made. Same trap here.)

This ticket carries the build spec once resolved - it is small enough that it does not graduate a
build effort.
