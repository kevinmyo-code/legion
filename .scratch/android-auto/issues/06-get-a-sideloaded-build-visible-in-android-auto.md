# Get a sideloaded build visible in Android Auto

Type: task
Status: open
Blocked by: 02

## Question

Nothing on this map can be verified on the head unit until a sideloaded LEGION build actually appears
in Android Auto. This ticket does that work, and it is HITL: the toggles are on Kevin's phone and the
head unit is in Kevin's car.

Not a decision. It unblocks every on-unit verification step the map later depends on, and CLAUDE.md
L11 binds those steps because the rig exists (settled decision 6).

Do, in order:

1. Take ticket 02's answer and add the **minimum manifest surface** to make LEGION visible as a media
   app: the `MediaBrowserService`/`MediaLibraryService` declaration, the
   `com.google.android.gms.car.application` metadata and the `automotive_app_desc` resource. A stub
   that serves an empty browse root is enough - **this is a visibility probe, not the real surface.**
   The browse tree's contents are ticket 08's decision and must not be pre-empted here.
2. On the OPPO A17k: enable Android Auto **developer mode** and the **unknown sources** toggle
   (exact path per ticket 02's findings, which may differ from the folklore).
3. Install the debug build. **Verify the install by sha256**, not by trusting "Success" - see
   `memory/MEMORY.md`, this cost a day's data once.
4. Plug into the head unit. Confirm LEGION appears in the media source list.
5. Record what actually happened for the tickets downstream:
   - Which toggles were needed, and their exact locations on this device
   - Whether the app appeared immediately or needed an Android Auto restart
   - Whether **wired**, **wireless**, or both were tested
   - The Android Auto app version and the head unit's make/model
   - Anything the head unit refused or rendered oddly

If the app does not appear, that is a finding, not a failure - capture the symptom precisely and
raise it, because ticket 02's answer is then wrong or incomplete and the media door (settled decision
2) is in doubt.

The answer records what was done and the facts later tickets depend on, per the wayfinder task-ticket
contract.
