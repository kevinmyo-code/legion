# What is the app shell, and how does the app start itself?

Type: grilling
Status: open
Blocked by: 02

## Question

`MainActivity.onCreate` calls `setContent` and nothing else. **Nothing anywhere starts
`AriaForegroundService`** - the only `startService` calls in the codebase are from inside the
service's own subsystems. So the Live session, all forty-odd voice tools, OBD, wake word,
proactives, and sync never execute. There is also no key-entry screen, since `FirstRunScreen` did
not port, so a Gemini key can only arrive through `BuildConfig` at build time.

This is the gate on the whole app being usable. Decide:

1. **Ignition.** Where the service starts: `MainActivity.onCreate`, `MidnightApplication`, or
   behind a user toggle. Which permissions must be held first (microphone, notifications, and on
   newer Android a foreground-service type), and what the app does when they are refused.
2. **Key entry.** The screen that takes a BYO Gemini key, validates it with the existing one-token
   ping, and stores it via `KeyVault`. Note the free-tier training disclosure Midnight AI shipped:
   decide whether that carries over, given there is no commercial tier here.
3. **First run.** What a stranger sees on a clean install with no key. Clone-and-run is a hard
   requirement (CLAUDE.md §2), so this path has to be genuinely walkable, not a dev shortcut.
4. **Navigation.** The shell that holds fleet, ledger, pantry, and settings. Shape follows from the
   design-language ticket; this ticket decides structure and back-stack behavior.
5. **Reachability of what exists.** `LedgerImportActivity` and `PantryImportActivity` are
   functional but `exported="false"` with nothing navigating to them. Fold them into the shell or
   replace them.
6. **Onboarding.** `ai/OnboardingFlow.kt` ported but its host UI does not exist and
   `AssistantIdentity` is placeholder copy. Decide whether onboarding is in scope for this pass or
   deferred, and say plainly what a first-run user gets if it is deferred.
