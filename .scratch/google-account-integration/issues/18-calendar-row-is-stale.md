# BUILD: the Calendar row says "Not set up yet" while calendar is working

Type: task
Status: resolved
Blocked by: -

## Question

Nothing to decide. Seen on-device 2026-08-13 on the Setup -> GOOGLE screen.

**The defect.** The Calendar line renders the static text **"Not set up yet"** while, on the same
device at the same moment:

- `READ_CALENDAR` and `WRITE_CALENDAR` are both `granted=true`,
- the Notes stream is rendering four real Google events, and
- Today's agenda has already queried the provider successfully.

The screen contradicts the app's own behaviour. [Ticket 12](12-google-grant-plumbing.md) built the
Calendar and Gmail lines as deliberate placeholders with no fake actions, which was right at the
time; [ticket 15](15-gmail-tools.md) then made the Gmail line live and **the Calendar line was never
revisited**, because ticket 13 wired calendar permission into the agenda surface instead of this
screen.

**The fix.** The Calendar line reports the real runtime-permission state - granted / not granted -
and offers the grant when it is missing, mirroring how the Drive and Gmail lines probe live.

**Note what it must NOT do.** Calendar uses **no OAuth scope at all**
([ticket 02](02-calendar-api-choice.md)), so this line is reporting an Android runtime permission,
not a Google grant. It has no consent round trip, no token, and nothing to re-authorise. The screen
should not imply otherwise - and this is the one line on it that a stranger's build can actually
satisfy, per [ticket 06](06-consent-surface-and-lapse.md) point 6.

## Verification

On the device: with calendar permission granted, the row says so. Revoke it in Android settings and
confirm the row flips and offers the grant back.

## Answer

**Fixed and verified on the device 2026-08-13.** The line now reads the real runtime-permission
state: **"Allowed on this device"** when `READ_CALENDAR` is granted, **"Not allowed - tap to allow"**
otherwise, with the tap requesting `READ_CALENDAR` and `WRITE_CALENDAR` together.

Two details that are deliberate, not incidental:

- **It reads the permission directly instead of probing Play Services.** Calendar has no OAuth scope
  at all (ticket 02), so there is no token, no consent round trip, and nothing to "re-authorise".
  Probing would have implied a Google grant that does not exist.
- **The meaning line says so in words**: "This one uses a phone permission, not a Google sign-in."
  That keeps the screen honest about the one grant a stranger's build can actually satisfy
  (ticket 06 point 6).
- Refreshed on `ON_RESUME`, so revoking in Android settings while this screen sits in the back stack
  flips it back - the case this ticket's verification section asks for.

Both permissions are requested together because a voice write runs off `AriaForegroundService`,
which has no Activity to raise a dialog from (ticket 14's accepted design call).
