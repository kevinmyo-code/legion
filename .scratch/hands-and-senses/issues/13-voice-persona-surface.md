# Voice and persona: the picker surface and the reconnect

Type: grilling
Status: open
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
