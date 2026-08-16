# Voice and persona: the picker surface and the reconnect

Type: grilling
Status: closed (Kevin, 2026-08-16) - already built
Blocked by: 12

## Question

The plumbing exists: `Voices.kt` curates Gemini Live voices with bundled samples (default
Sulafat), `PersonaTraits.kt` compiles staged choices into a prompt fragment and persists JSON.
Missing: any screen that hosts them, and a defined behavior for changing voice mid-use (Live
config is per-session, so a change means reconnect). Blocked by
[the identity](12-assistant-identity.md) because the picker presents personality ON the core
voice, and its copy depends on what that voice is. Decide:

1. **Where it lives.** Settings rows (SettingsScreen exists), onboarding (`OnboardingFlow` hosts
   the stages conversationally - is the screen the fallback or the primary?), or both:
   onboarding first-run, settings to revisit?
2. **Voice picking UX.** Play the bundled sample per voice, tap to select. Does selection apply
   immediately (tear down + reconnect the live session, Alfred acknowledges in the new voice) or
   on next session? Define the reconnect UX in words - what does the user hear during the swap?
3. **Persona changes mid-relationship.** Changing warmth/humor after weeks of use: silent prompt
   swap next session, or acknowledged ("noted, I'll keep it brief")? Acknowledgment risks
   performing the change; silence risks uncanny discontinuity. Pick one.
4. **Sync.** Voice + persona selections into the Drive appDataFolder like other settings, so both
   phones sound the same? Or per-device deliberately?
5. **Mission-control dependency.** The `mission-control` effort owns every screen's aesthetic.
   Does this surface land inside that map's system (new tickets there) or here? Do not build a
   screen that map immediately re-skins - coordinate, decide which map owns the build.

## Answer

**Closed 2026-08-16. Kevin: "13 > yeah already done."** Verified against the tree before closing,
because two tickets earlier the same day described work that already existed. All `traced`.

The ticket's own premise line - *"Missing: any screen that hosts them"* - is false, and so is the
implication that its five questions are open. Every one is answered in shipped code:

1. **Where it lives.** Both, as the ticket proposed. `ui/CompanionsScreen.kt` hosts the roster and
   `ui/companions/CompanionRows.kt:171-207` the editor (name free-text, persona radio over
   `BUILT_IN_PERSONAS`, voice dropdown over the 30 `CURATED_VOICES`), with
   `ui/companions/VoiceAudition.kt` playing samples. `OnboardingFlow` still hosts the stages
   conversationally.
2. **Voice picking UX / reconnect.** Decided and shipped: **`LiveSessionController.refreshIdleVoice()`**
   (`:180`) rebuilds the idle warm socket so the next line uses the current voice, and is a
   **no-op during an active conversation - it never kills a live turn**. Its KDoc (`:171-179`)
   names the field-test bug it exists for ("default voice after onboarding"), because `prewarm`
   captures `voiceName` at socket start.
3. **Persona change mid-relationship.** **Silent**, and deliberate at the storage layer:
   `CompanionProfileStore.saveProfile` re-materialises immediately when the edited profile is the
   active one, "since a rename/re-voice/re-persona of the ASSISTANT YOU ARE TALKING TO right now
   must take effect without a restart" (`:171-175`).
4. **Sync.** Handled by design - callers bump `updatedAt` rather than the store defaulting it,
   explicitly so a sync-driven upsert of a newer Drive row can pass the REMOTE clock through
   unmodified (`CompanionProfileStore.kt:177-181`).
5. **Mission-control dependency.** Moot; the screens exist and live under that map's system.

**The one sliver not deliberately decided:** question 3 asked whether a persona change is
*acknowledged out loud* or silent. The code is silent, which was an implementation choice rather
than a taste call. Judged defensible and not worth a session. Reopen only if the silent swap
actually reads as uncanny in use.

**Blocked-by [12](12-assistant-identity.md) is also moot** - that ticket closed the same day, its
premise equally false. Freeform persona authoring is back-burnered; if it returns it graduates to
`persona-authoring`, and the picker surface described here is what it would extend.
