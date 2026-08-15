# BUILD: Google grant plumbing, and stop losing the reason

Type: task
Status: resolved
Blocked by: -

**Unblocked and built 2026-08-13.** The `Blocked by: 11` was over-tight: the resolver, the
`tokenOrReason` plumbing and the Setup screen need no console access at all. Only the on-device
verification needs a live grant, and Kevin has one. Ticket 11 remains open as his console work.

## Question

Nothing to decide. Graduated 2026-08-13 from [ticket 06](06-consent-surface-and-lapse.md).
First build ticket: everything else needs a working grant surface underneath it.

**Ships a live bug fix, independent of the rest of the map.** A lapsed or revoked Drive grant is
currently indistinguishable from never having connected.

1. **`ui/sync/DriveConnectResolver` becomes `GoogleGrantResolver`**: takes a grant identity (Drive /
   Gmail) plus a status code, returns the specific message. Keeps its current shape as a plain JVM
   unit with no GMS or Android types, so the existing unit tests still apply and extend. One
   resolver, not three.
2. **`SyncEngine` records the reason for its last failure** where the UI can read it.
   `DriveAuth.accessTokenOrNull()` keeps its nullable shape and `SyncEngine` keeps its graceful
   cannot-sync-right-now path - what changes is that the reason stops being discarded.
3. **Setup gains a GOOGLE row** opening to three independent lines: Drive (`drive.appdata`),
   Calendar (`READ_CALENDAR`/`WRITE_CALENDAR` runtime permission, **no OAuth**), Gmail
   (`gmail.readonly`). Each shows granted / not granted / needs re-authorising, each with its own
   action. Wording is fixed in ticket 06 point 3 - use it verbatim.
4. **The clone-and-run sentence**, on that screen, driven by `DEVELOPER_ERROR` (status 10). Ticket 06
   point 6 has the wording. Note it must say Calendar still works, because it does.
5. **Incremental consent**: each grant requested at first use of its feature, never bundled.

## Verification

- Unit tests on `GoogleGrantResolver` for every status code it distinguishes, including 10.
- **On the device**: revoke Drive access in the Google account, open the app, and confirm Setup says
  "needs re-authorising" rather than nothing. This is the whole point of the ticket and it cannot be
  verified any other way.
- Re-grant and confirm sync recovers.

## Answer

**Built 2026-08-13. Compile and the full suite verified by the orchestrator directly, not relayed:
`cleanTestDebugUnitTest testDebugUnitTest` re-run from cold, 662 tests, 0 failures, 0 errors.**
(The agent's own green claim was `UP-TO-DATE` and proved nothing on re-run - `memory/MEMORY.md`
records an agent reporting "464/464 green" while the build was failing.)

- `DriveAuth` gains `TokenResult` (`Token`/`NeedsConsent`/`Failed`) and `tokenOrReason`;
  `accessTokenOrNull` keeps its signature and delegates.
- `DriveConnectResolver` -> `GoogleGrantResolver`, still zero Android/GMS imports, now carrying
  `Grant { DRIVE, GMAIL }` and `FailureCategory.NEEDS_CONSENT`. Test file ported, every original
  case kept, 16 cases.
- **All five token sites fixed** - `SyncEngine.syncNow` plus `DatabaseSnapshot`'s three - so a
  lapsed or revoked grant no longer reports as a transient network problem.
- New `GoogleAccessScreen` on `settings/google`, three lines. Drive live-probes on `ON_RESUME`;
  Calendar and Gmail state plainly that they are not set up yet, with no fake actions.

### Carried forward, not claimed

- **DEFERRED, Kevin, on-device**: revoke Drive access in the Google account, open Setup -> Google,
  confirm it says "needs re-authorising" and not "Not granted"; re-grant and confirm recovery. This
  is the ticket's entire point and no one has done it.
- **`reasoned`, unverified**: that `CompanionProfile.isSyncEnabled` correctly separates "never
  connected" from "lapsed/revoked" on the probe. `Outcome.NeedsConsent` alone cannot tell them
  apart, so this second signal is load-bearing for the screen's wording.
- **`reasoned`, unverified**: that a `DriveAuth.authorize` round trip on every screen resume does
  not feel laggy. Watch it on the device; if it does, the fix is to probe once per screen entry
  rather than per resume, not to fall back to a stored flag.
- **Design call made by the executing agent and accepted**: the Drive row opens the existing
  `DriveSyncScreen` rather than duplicating the consent `PendingIntent` round trip. Correct - one
  owner for that flow.
