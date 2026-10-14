# STRATEGY.md

Nightrunner market context, competitor landscape, distribution playbook. Extracted from MOOSE_MARKET_ANALYSIS_2026-07-01 with pricing/photo-diagnosis recs stripped (superseded by CLAUDE.md §2 and §8). Business + marketing agents reference this. Not auto-loaded.

Last valid: 2026-07-01 research. Re-verify Spotify quota + Gemini pricing before any launch commitment.

---

## 1. The Wedge (verified unoccupied)

No Android head-unit-native AI companion with OBD integration and per-car memory exists as of mid-2026.

- **ChatGPT on Apple CarPlay** (launched March 2026, iOS 26.4): voice-only, no vehicle control, no OBD, no memory. General LLM bolted onto CarPlay.
- **Gemini on Android Auto** (rolling 2025 to 2026): no OBD, no per-car history.
- **DriveSpeak** (iOS/CarPlay): voice-first, no Android, no OBD.

**None run on aftermarket Chinese double-DIN Android head units.** That hardware context is Nightrunner's unclaimed territory.

---

## 2. Competitor Teardown

| App | Hardware | Software | Model | Notes |
|---|---|---|---|---|
| Carly | ~€80-95 | ~€50-90/yr per brand | Hardware + sub | ~1.8M downloads. Brand-siloed (BMW/VAG). TÜV Nord endorsed. |
| OBDeleven | ~€40-50 | €80-150/yr PRO or credits | Hardware + sub/credits | VAG-heavy. Credits model. |
| FIXD | ~$20 | Free / $8.99mo / $99/yr | Freemium + hardware | Full-system scan, repair cost estimates, mechanic hotline. |
| BlueDriver | ~$90 | Free | Hardware only | Strong ABS/SRS/trans. No recurring. |
| Torque Pro | none | ~$5 one-time | Paid Android app | 1M+ downloads. Generic gauge cluster. Budget floor. |
| DashCommand | none | unclear | One-time/freemium | iOS-primary. No verified 2026 pricing. |

**Observation:** every serious OBD app requires proprietary hardware OR is a generic gauge cluster with no character or persistence. None offer AI reasoning over time or per-car memory. None are companions. They are scan tools.

---

## 3. Market Sizing (directional)

| Signal | Number |
|---|---|
| Aftermarket head unit market | ~$20B (2025); double-DIN = 55% |
| OBD dongle market | $1.4-2.8B; ~10-12% CAGR |
| Classic car market | ~$37.5B global; under-40 collectors growing |
| Android head-unit competitors with AI + OBD + per-car memory | **0** |

**Caveats:** ATOTO/Joying/Eonon don't publish unit shipments. Classic car figures are auction-weighted. Use these as momentum signals. Real TAM answer is cheaper via forum counts: r/projectcar alone = 174k members.

---

## 4. SWOT

### Strengths
- Unoccupied category. First-mover on head-unit AI + OBD + memory.
- **Compounding moat.** Per-car history gets more valuable every month. Competitors can copy features, not two years of logged services and memories.
- **Aesthetic is a moat, not decoration.** Carly cannot ship a cassette deck with a city-pop avatar. Off-brand for them, whole brand for Nightrunner.
- Solo-dev economics. BYO-key = near-zero marginal cost per user.
- Cruise screen is inherently filmable. The marketing artifact is inside the product.

### Weaknesses
- Nothing hardware-validated beyond voice loop + OBD codes on one Cherokee. Every YouTuber demo risk lives here.
- Play Store install still requires the $5 gate + BYO Gemini key onboarding. That flow is undesigned end-to-end.
- Gemini dependency. Pricing, deprecation, key management are Google's decisions.
- BYO-key onboarding filters out a large fraction of otherwise-ideal users. "Get an API key from Google AI Studio" is a step most people won't take.

### Opportunities
- **Logbook export share card = organic acquisition loop.** QR footer at car meets, in video descriptions.
- **Head unit OEM partnership long-shot.** ATOTO has no software differentiation. "Comes with Nightrunner" is a conceivable 2027 conversation.
- **Resale story.** A car with a complete Nightrunner logbook is worth more. No OBD app makes this pitch.
- **B-roll flywheel is unclaimed.** No precedent for an app going viral in car communities. That's an opportunity, not a warning.

### Threats
- Google could ship Gemini on AOSP head units. Mitigation: no OBD grounding, no memory, no character, no incentive to serve $200 hardware. Speed matters.
- Platform rug-pulls (Spotify already happened in Feb 2026: 25-user dev cap cut to 5).
- **Subscription math is brutal at the median.** RevenueCat 2026 (n=115k apps): median = $492/mo, 57.7% never reach $1k total, top 10% capture 94.5% of all subscription revenue. Only the flywheel gets you to the top decile. Nightrunner's $5 paid-app model sidesteps this, but launch traction still needs the flywheel.

---

## 5. The Stardew Playbook

### The Numbers (verified)
- **Stardew Valley:** 4.5yr solo dev, $14.99 one-time, zero paid marketing. 1M copies month 1 (Feb 2016). 41M copies / ~$518M gross by Dec 2024. Version 1.6 alone drove ~10M copies in 2024.
- **Balatro** (solo, LocalThunk): Feb 2024. Expected "maybe 10 copies." 1M copies month 1. 5M by Jan 2025. $9M+ mobile IAP in 11 months.
- **Vampire Survivors** (primarily solo, poncle): ~$57M gross Steam alone. ~2M copies in first month at $3-5.

**Zero paid marketing, all three.** Flywheel: demonstrable first-session hook + streamer/creator pick-up + visible solo human + no feature bloat.

### The Four Elements (with Nightrunner's equivalent)

**1. Demonstrable first-session hook. Content creates itself.**
- Stardew: the farm montage. Balatro: "one more hand." VS: "just one more run."
- **Nightrunner:** the first night drive. Lights Out, cassette spinning, live OBD, companion chimes in about coolant temp in character. Cinematic B-roll exists inside the product.
- Every engineering hour routes through: "does this make the night drive more reliable, more beautiful, or more shareable?"

**2. Fan forum presence a year before launch.**
- Barone posted in Harvest Moon forums 1yr pre-launch. Found people starving for exactly his game before a line of marketing copy existed.
- **Nightrunner:** post NOW as "I'm building an AI companion for my '98 XJ." That IS a project-car post. The Cherokee is the dev blog protagonist. The clock starts when you post.

**3. Dev is visibly one human who cares.**
- Barone's Twitter/Reddit presence WAS the brand. Solo dev is not the weakness in this playbook. It's the marketing.
- **Nightrunner story:** one person, one car, four years, the app that knows itself.

**4. Free named updates as retention/sales flywheel.**
- Stardew 1.6 drove ~10M copies in 2024, 8 years after launch. Each free update = press cycle + forum posts + re-engagement spike.
- **Nightrunner:** ship 1.0 lean (voice + OBD + logbook + Cruise). Then named free updates:
  - "The Chassis Quirks Update" (Sprint 3)
  - "The Foresight Update" (Sprint 3)
  - "The Build Card Update" (Sprint 4)
  - "The Nightrunner Wrapped Update" (Sprint 6)
- Each one justifies the $5 forever and keeps the community posting.

### One Honest Disanalogy
Stardew: zero marginal cost per player, frictionless Steam distribution.
Nightrunner: BYO Gemini key = user's marginal cost, sideload/Play install friction. Playbook still applies. It just has two extra bosses (key onboarding + install funnel).

---

## 6. Distribution Channels

### YouTube (mid-tier for engagement, not vanity reach)

| Channel | Subs | Angle |
|---|---|---|
| Dustin Williams | 741k | JDM builds, EG Civic, Skylines, 280Z |
| SAMMIT | 690k | JDM lifestyle, drifting |
| Noriyaro | 690k | Japan-based, drift events, tuners |
| ALBOmedia | 493k | JDM enthusiast lifestyle, Japan |
| Larry Chen | 477k | Photography, JDM culture |

**Saved for post-traction press wave:** Donut Media (9.26M), Hoonigan (5.89M). Too broad for launch.

**Strategy:** seed 5-10 channels in 50k-700k range where engagement is high and audience is specific. Cruise screen needs to look great on camera BEFORE the first pitch email. Pitch is "use our dash footage as B-roll." QR in video description drops to install flow.

### Reddit

| Community | Size |
|---|---|
| r/cars | 7.4M |
| r/projectcar | 174k |
| r/JDM | 39.6k |
| r/E46 | ~50-100k (verify) |
| r/Jeep | ~300-500k (verify) |
| r/XJCherokee | verify directly |

### Forums (higher-intent, lower-volume, longer-lived)
- XJ: Jeep Cherokee Talk, XJ Owners Club
- BMW: E46Fanatics
- Post build-log updates. Not launch announcements.

---

## 7. The Install Funnel (undesigned, critical)

When a YouTuber's viewer clicks the QR in the video description, what happens in the next five minutes?

```
Landing page  ->  Play Store $5 purchase  ->  install  ->  first-run key gate  ->  first conversation
```

Failure modes at each step:
- **Landing page:** if it's just a Play badge, no story, low conversion. Needs the killer-moment video + one-screen "what this is."
- **$5 gate:** Play handles it. Non-issue.
- **First-run key gate:** hardest step. "Go to Google AI Studio and create an API key" will lose 70%+ of otherwise-interested users. Make dramatically easier: deeplink to AI Studio, guided 30s video inline, copy frames it as "your key, your data, your car, one-time setup."
- **First conversation:** must land the killer moment fast. If the first thing they hear is generic small talk, it dies.

**This funnel is the flywheel-or-die surface.** Design it before creator outreach.

---

## 8. Three Things If You Only Do Three

1. **Hardware validation sprint.** Until the 60-second night-drive demo is bulletproof on the real head unit, no creator outreach. A YouTuber's first stutter is the whole video. (See CLAUDE.md §14 pending list.)
2. **Design the install funnel end-to-end.** Landing page + BYO-key onboarding + first-conversation hook. Ship the guided video for AI Studio key creation.
3. **Start forum presence NOW.** Build-log updates on r/projectcar and XJ/E46 forums. The Stardew flywheel takes a year of being a known human before launch day. Clock only starts when you post.

---

## 9. Historical Notes (killed since research)

Kept for context on why current CLAUDE.md guardrails exist. Do not act on these.

- **Spotify commercial-quota bet (killed 2026-07-01 same day as research).** Feb 2026 policy change: dev mode = 5 manually-allowlisted users, extended quota = 250k MAU + registered business entity. play_music / play_vibe / make_playlist would 403 for everyone past the allowlist. Music path was then MediaSession/AVRCP only. (Since 2026-07-21 the app also has an optional BYO Spotify App Remote: the driver registers their own Spotify dev app, so no shared client ID hits the allowlist cap. See `app/build.gradle.kts` and the drift flags under CLAUDE.md §8.)
- **Hosted $5-8/mo tier (killed 2026-07-01).** Gemini Live audio ~$0.037/min. 5-min/day user = ~$5.60/mo raw cost. Hosted was zero-margin or underwater. See CLAUDE.md §2.
- **$79 one-time recommendation (superseded 2026-07-01).** Analysis recommended $79 BYO-key. Kevin priced at $5 to widen the top of funnel and rely on volume + name-update flywheel. See CLAUDE.md §2.
- **Photo diagnosis Wave 3 promotion (deferred 2026-07-02).** Research called it the strongest unshipped moat feature. Deferred to phone port because head unit has no camera. See CLAUDE.md §8.

---

## 10. Sources (2026-07-01 research batch)

Re-verify before citing publicly:
- developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security
- developer.spotify.com/documentation/web-api/concepts/quota-modes
- ai.google.dev/gemini-api/docs/pricing
- revenuecat.com/state-of-subscription-apps/ (2026, n=115k apps)
- gamingonlinux.com (Stardew Valley 41M sales, Dec 2024)
- dlcompare.com (Balatro 5M copies, Jan 2025)
- gamalytic.com (Vampire Survivors Steam stats)
- theinsightpartners.com (Car Audio Head Units Market)
- marketresearchfuture.com (OBD Dongle Market 2035, Classic Car Market 2035)
- socialblade.com (YouTube subscriber counts)
- gummysearch.com, subbed.org (subreddit member counts)
- skanyx.com (Carly, OBDeleven, Torque Pro pricing 2026)
- obdadvisor.com (FIXD vs BlueDriver 2026)

---

## 11. When to Re-run Research

- Before any launch commitment (verify Spotify quota + Gemini pricing haven't shifted again).
- Before pitching first YouTuber (verify their subs + engagement rate).
- Before pricing changes (median subscription math from RevenueCat updates yearly).
- If Google announces Gemini on AOSP head units (threat check).
- If a new AI-car-companion competitor surfaces (defensive check).
``