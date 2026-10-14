# Midnight AI: Ship-Readiness Analysis and Roadmap

> **Stale snapshot (flagged 2026-10-14).** Everything below is the 2026-07-16 picture and has not
> been redone. Changed on `dev` since, from the git log:
> - **The art question is closed differently:** in-app image generation was removed 2026-09-27;
>   art is generated in a Claude session and bundled (`art/seed/`, `ai/SeedArt.kt`). The mascot
>   is **Uncle Moose**, not Zero (2026-09-27 and 2026-10-11). Phase 3 below no longer describes the
>   plan.
> - **Removed:** in-app navigation and Mapbox, the wake word and ambient listening (2026-10-11).
> - **Added:** LEGION engine client and `ask_engine` (2026-09 to 2026-10), two-phone switcher, REAR
>   camera tile, BYO Spotify voice parity and playlist pins, two-pane Settings, a `fast` sideload
>   build, ABI-split APKs.
> - **Direction:** Kevin, quoted in `ai/SeedArt.kt` (2026-09-27): "its a personal app now". Whether the Play launch
>   phases below still apply is Kevin's call; nothing on `dev` has moved them since July.
>
> Current state lives in `memory/MEMORY.md` and `README.md`.

**Snapshot: 2026-07-16.** Supersedes the 2026-07-09 snapshot, which is now actively misleading: three
of its seven phases moved and its single biggest stated risk dissolved. Sources: `memory/MEMORY.md`,
`memory/library/decisions.md` (three decisions dated 2026-07-16), the `.scratch/car-launcher-market/`
wayfinder map, and a fresh scan of `app/src`, the build config and the manifest.

## TL;DR verdict

The app is **code-complete for a v1 launcher**, the **verification gate is substantially cleared**,
and the **existential onboarding problem is gone**. What blocks shipping is no longer engineering. It
is that **we just positioned on being the only art-directed launcher in the category, and the art
does not exist yet.**

Honest distance to ship:
- **As your own daily driver:** effectively there. OBD and voice are confirmed on the Cherokee (2026-07-16).
- **As a public free launcher on Play:** *close, and much closer than the 07-09 snapshot believed.* Needs Zero's art, the canned voice lines, the bundled avatar/wallpaper set, and the Play launch checklist. Does NOT need Sprint 3, the moat, or the AI to be finished.
- **As the paid AI companion:** the upgrade path works and costs nothing to serve, but it only matters to drivers who already installed the launcher.

The single most important change since 07-09: that snapshot said "exactly one feature in the entire
app is confirmed working on the real head unit" and concluded ship distance could not be credibly
estimated. **Kevin drove the Cherokee on 2026-07-16 and reported OBD and voice both working.** The
gate is not fully itemized, but the core is proven and the estimate is no longer a guess.

## What changed on 2026-07-16 (read this before trusting anything below)

Three decisions, all in `memory/library/decisions.md`:

1. **Pricing rewritten to two tiers.** Free install, $10 one-time unlock, driver's own Gemini key. No trial, no subscription, **no backend at all**. `functions/` is dead code. Marginal cost per install is exactly zero, so the project cannot lose money and conversion rate is a vanity metric.
2. **The mascot is Zero.** Androgynous, Motoko-shaped, two presentations sharing one persona, and **NOT the car** (the driver's own avatar is the car, so C1's first-person reframe is the paid payoff).
3. **Positioned as a LAUNCHER**, companion is the upgrade. Lead on the one confirmed market differentiator: nobody else in this category is art-directed.

### The 07-09 snapshot's #4 hard truth is DISSOLVED

It said: *"BYO Gemini key is an existential product-market problem for a paid app. Pay $5, then go
create a Google AI Studio key before the app does anything, most consumers bounce. Borderline-fatal
for public Play."*

That was true of the old model. Under two tiers a new install gets a **complete launcher with no key,
no payment and no account**. The key gates the AI *upgrade*, not the door. Nobody bounces at entry.
Research against Google's own docs (2026-07-16) also confirmed a free-tier key needs **no credit
card** and can open Live sessions at $0. **The thing that made public Play "far" is gone.**

## Where we are: scorecard

| Dimension | 07-09 | Now | Notes |
|---|---|---|---|
| Core features (in code) | ~85% | ~90% | Garage (G1), Drive sync (S1), BLE OBD (O1) landed since |
| **Hardware validation** | **~5%** | **~45%** | OBD + voice + head-unit Drive-sync upload confirmed 07-16. Still unproven: Companion Badge, mixtapes, launcher takeover, Lights Out backlight, 2-device sync, BLE, the phone target |
| **Art direction** | n/a | **0%** | **New dimension, and now the critical path.** Zero does not exist: ref sheet uncommissioned, no art generated, no bundled avatar set. We are selling looks we have not made |
| Observability | 0% | wired, unconfirmed | Real Firebase 07-10, Crashlytics release-only. No test report confirmed landing |
| The moat (4 pillars) | ~50% | ~50%, demoted | Unchanged in code AND demoted by decision 3. See Phase 5 |
| Launch readiness (Play) | ~2% | ~5% | R8 still off. **No privacy policy URL and no domain** |
| Product-market risk | **high** | **low** | The BYO-key wall moved off the front door |
| Test coverage | narrow but sound | unchanged | Parsers, OBD sim, agent protocol, Room migration, SyncMerge/SyncCodec. No UI or voice-core tests |

## The hard truths, re-ranked

1. **We are positioned on art that does not exist.** Decision 3 makes the look the entire wedge. The illustrator ref sheet is still parked and uncommissioned ($300 to $600), no Zero art has been generated, and the free tier's bundled avatar and wallpaper set is the first thing every install sees. This is the only blocker with a human lead time.
2. **Do not commission that art yet.** Map ticket 05 decides whether there is ONE authored aesthetic or a theme system, and the era is quietly unsettled: Zero went cyberpunk and the settings garage went Tokyo Drift, which cohere with each other and leave §12's late-Showa city-pop as the outlier. **Nobody decided that drift.** We got lucky once, the ref sheet happened to be uncommissioned when Yoko died, which is the only reason that rename was free. Do not rely on luck twice.
3. **The moat's data layer is still dead code, and now it is also lower priority.** Verified 2026-07-16: `ChassisQuirkDao`, `OilAnalysisDao` and `ForesightNoteDao` have **zero callers** outside `data/local`, and there is no WorkManager or AlarmManager anywhere. (`assets/` now exists, holding `dtc_descriptions_seed.json`, so that part of the 07-09 note is stale.)
4. **B30 is worth more than Sprint 3.** OBD history is invisible: `odbSampleDao()` has zero UI callers, so a year of 30-second telemetry is reachable only by asking out loud. It is a paid feature, a competitive gap (OBDAI charges $4.99/mo for a shallower scan-session history), and the moat made *visible*. It surfaces data we already collect; Sprint 3 has to invent new data. Cheaper, higher value.
5. **Privacy policy has no home.** Play requires one at a real URL. There is no domain: `mooseapp.com` was dead and shipping inside the Build Card QR until 2026-07-16. New Data Safety wrinkle: a free-tier Gemini key grants Google training and human-review rights over the driver's conversations, which must be disclosed rather than papered over.
6. **One head unit is still not device coverage.** Unchanged from 07-09. Hundreds of cheap AOSP 8-10 variants, animator scale 0, GL ES 2 GPUs, broken keymaster.
7. **A competitor is converging and shipping faster.** Car Nebula (launcher + OBD + an AI voice assistant) pushed three releases in 48 hours and took roughly half its lifetime downloads in the last 30 days. Tiny (~4,300 installs) and regionally aimed, with no evidence of automatic PID trending, so the compounding-history moat holds. It is a tempo signal, not a threat.

## Roadmap

Oriented to a **free public launcher on Play** with a $10 AI upgrade. Phases 0 to 2 are done or
substantially done; the ordering below is what is actually left.

### Phase 0: Hygiene (DONE 2026-07-16)
- ~~Delete the `com.example.aiapp` stale template dirs.~~ Done.
- ~~Delete `functions/`, `LiveTokenClient`, `ImageProxyClient`, `EntitlementManager`'s broker path, the App Check surface.~~ **Done (`53bd2ac`).** Pure deletion: every broker call sat in an `if (!hasKey())` branch in front of a direct-to-Google call that already worked. `RuntimeMode` is now `BYO_KEY | AI_PAUSED`; `EntitlementManager` is a local zero-network key check. Crashlytics and `sync/` survive untouched. Side effect: no App Check means **release builds sideload again**.
- Left flagged: `firebase-auth`, `firebase-appcheck-playintegrity`, `firebase-firestore`, `firebase-functions` are now unreferenced. Separate commit.

### Phase 1: Observability (DONE 2026-07-10)
Real Firebase (`midnight-ai-c7421`), Crashlytics gated to release builds, analytics deliberately
dropped. **Open:** confirm a test report actually lands, and check `google-services.json` for a stale
`.personal` flavor entry (the flavors were removed 2026-07-12).

### Phase 2: The verification drive (SUBSTANTIALLY CLEARED 2026-07-16)
Kevin drove the Cherokee: "all obd and voice and stuff works fine." **Not itemized**, so B13-B17,
B8/F4, B1/B2, C1's five canonical voice tests, M1/M2/M3, the Companion Badge and the Lights Out
backlight drop all stay open until confirmed individually. Ask before marking any closed. Still
untested: the killer moment end to end, the two-device sync round trip, BLE, and the phone target.

### Phase 3: Ticket 05, then the art (THE CRITICAL PATH)
1. **Ticket 05: one authored aesthetic, or a theme system?** A conversation, cheap, and it unblocks tickets 08 (how far customization goes), 09 (prototype Cruise variants) and 11 (settings as a garage). Reframed by decision 3: if we position on looks in a category where nobody has any, having ONE look IS the wedge and a theme system means having no look. Settle the era here too.
2. **Commission Zero's art**, once 05 says what to draw. Longest lead time in the whole plan.
3. **Generate and bundle** the free tier's Zero avatar/wallpaper set and the canned voice lines (two variants, generated once on Kevin's key, shipped in `res/raw` beside the eight existing `voice_sample_*.wav`).

### Phase 4: B30 and the fix pass
- ~~**B30: an OBD history / manage-OBD surface.**~~ **Done 2026-07-16 (`ed70790`).** One sheet: LIVE/HISTORY/CODES/MANAGE, off Cruise's CODES tab. Free sees its last drive in full, $10 adds the rest. Not on-device verified (LIVE poll vs A2DP, chart perf on a real dataset).
- B29 (CODES tab flicker: a failed DTC read resolves to 0, same as "no codes"), B31 (no persisted `lastSyncAt`).
- Standing gaps from 07-09, still open: B5 rolling-road generation, `playViaBrowser`/transport unguarded main-thread crash site, recap cover PNG orphaned on delete, `controlMusic` false-success ambiguity, dead SPEAK buttons, photo-picker SAF fallback.
- The "I can't explain it yet" paywall entry, plus the mute toggle and the API-key spend readout (repurpose the now-dormant `VoiceUsageMeter`; must be labelled an ESTIMATE, Google does not expose per-key spend).

### Phase 5: Launch prep
- **Privacy policy at a real URL.** Needs a domain. Hard blocker, no workaround.
- Play Console: merchant account, the `unlock_byo` one-time product (NOT `sub_monthly`, ever), Data Safety (including the free-tier training disclosure), IARC content rating, listing with **launcher** keywords per decision 3, closed then open testing.
- Play App Signing + a real upload key. Turn on R8 (`isMinifyEnabled = false` today) and test.
- Trademark sanity on "Midnight AI" and on "Zero".
- Multi-device pass: 2 to 3 other cheap head units.

### Phase 6: The moat (Sprint 3)
Demoted by decision 3, not cancelled. This is depth and retention, not positioning.
- Chassis-quirk index: author the bundled YAML into `assets/`, a loader calling `ChassisQuirkDao.upsertAll`, a `QuirkAgent`.
- Oil-analysis entry form + a voice insert tool.
- Foresight nightly job: needs a scheduler (none exists), then Gemini-as-reasoner over `obd_samples` writing `foresight_notes`. This is the "she notices" magic and it is currently absent.

### Phase 7: Charm (Sprint 5, non-blocking)
Custom fonts, UI sound catalog, ritual micro-animations. **Note the inversion:** the canned voice
lines used to live here and are now Phase 3 critical path, because they are the free tier.

## The one thing

**Settle ticket 05, then buy the art.** Everything else is either done, cheap, or waits on it. The
engineering risk that dominated the 07-09 snapshot is largely retired: the drive happened, the
onboarding wall is gone, and the backend that would have cost money to run no longer exists. What is
left is that we have chosen to compete on being the only beautiful thing in a category of generic
dashboards, and we have not drawn it yet.
