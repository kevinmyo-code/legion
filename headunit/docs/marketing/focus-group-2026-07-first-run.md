# Focus Group — Nightrunner First-Run Funnel
**Session date:** 2026-07-04
**Moderator:** Nightrunner Marketing Agent
**Topic:** First-run funnel — Play Store purchase through Cruise screen landing
**Participants:** DEZ (24, Houston TX / 240SX), MARK (41, Fort Collins CO / XJ), YUKI (29, LA / NB Miata)

---

## Part 1 — Transcript

---

**MODERATOR:** You each just paid $5 on Play Store and opened a car-companion app called Nightrunner. Walk me through what happens in your head as the first screen loads. Go.

**DEZ:** Okay so the first thing I see is ナイトランナー in katakana, just that, on a black screen. Honestly? That's clean. That's a vibe. I'm already filming this for a reaction clip in my head.

**YUKI:** Same — the cold open is doing work. That's the one moment where I'm like, okay this might be an adult product. The aesthetic is *there*.

**MARK:** I'll give it that. Font's legible, there's intention behind it. I'm past the logo in two seconds though, I want to know what the app actually does.

**MODERATOR:** The logo fades and you hit a card that says: "Nightrunner needs a Gemini API key to think." There's a text field, a link to AI Studio, and a note that your key stays on this device encrypted. YUKI — first?

**YUKI:** Okay that's where I stop. "Gemini API key." I don't know what that is. Literally, what is a Gemini key, why is that my problem, and why did I pay five dollars and now there's *homework*?

**DEZ:** I mean, I know what an API key is, but even I'm like — I just bought this thing. This is step one? Before I see anything?

**YUKI:** Right! I haven't seen the app yet. I have no idea if I even like it. You're asking me to go get something from a service I might not have an account for, before I've seen what I'm buying. That's a wall.

**MARK:** I actually don't hate the concept. BYO key means no subscription — that's the deal I signed up for and I appreciate it. But the *execution* here has to be really good or it's just "congrats on your purchase, now go do IT work."

**DEZ:** What's the AI Studio link look like? Is it a link I can actually tap and come back from, or do I get dumped out of the app?

**MODERATOR:** It opens a browser, takes you to Google's AI Studio. You get the key, come back, paste it.

**DEZ:** I can work with that. Browser comes back, paste, done. But I'm also on a head unit right now. I don't have a keyboard that's fun to type a long API key into.

**YUKI:** Oh god. I didn't even think about that. You're on a car touchscreen. The key is like forty characters. How do you paste on a head unit?

**MARK:** That's a legitimate UX question. If the head unit has a clipboard it probably works. But if this is first-run and the car is parked and they're doing setup from a phone... you'd QR-code that or share-sheet it somehow.

**YUKI:** There's no mention of doing setup from a phone though. It's just "paste it here."

**MODERATOR:** Let's say YUKI clicks the AI Studio link. She gets there, creates a key. She comes back and pastes it. It validates — one-token ping — and then you get the permission prompt: mic, location, Bluetooth. What's the read?

**MARK:** I'm fine with that sequence. Permissions after key validation makes more sense than permissions first. Logical. I just want to know what each permission is FOR. "Microphone — to talk to Kaze. Location — for speed display and place memory. Bluetooth — to read your car's OBD data." Just say it.

**DEZ:** Yeah the Android default permission dialog is ugly and says nothing. At least put a screen before it that explains the WHY so I'm not just tapping Allow Allow Allow.

**YUKI:** I'm going to Allow all of them because I'm already in, but I agree, even just a line under each permission.

**MARK:** My wife uses the Jeep too. She's going to hit that permission screen and feel like the app is asking to surveille her. "Why does a car app need my location?" I need that to be pre-explained.

**MODERATOR:** Permissions done. Now a woman's voice — Kaze, city-pop Showa character — introduces herself and asks you to tell her about the car. The spoken intro IS the onboarding. No wizard. MARK, how does that land with you?

**MARK:** That's actually smart design, IF the voice sounds good. If it sounds like a phone tree I'm muting her and never turning it back on. What's Sulafat sound like?

**DEZ:** It's a Google TTS voice. It's... fine? Like it's not bad. Not Siri bad.

**YUKI:** I want to love this but I'm nervous about it. If the voice is mediocre, the whole character illusion breaks right there. You've set up this cool Showa city-pop mascot with the katakana opener and now she sounds like a navigation system.

**DEZ:** To be real, for the content angle this is huge. If she sounds good and the Cruise screen looks as dope as the logo — that's a thumbnail right there. But if she sounds robotic, my viewers are going to clown it.

**MARK:** The character concept is fine. I'm more interested in whether she actually knows my car. You said it pulls OBD data — great, but OBD on a fresh install, the Bluetooth isn't paired yet. What does she say? "I don't see a car yet"?

**MODERATOR:** Good. Let's go to the Cruise screen landing. Clock, cassette widget, avatar portrait, telemetry strip. The avatar is a placeholder dot right now — no mascot art yet.

**YUKI:** Oh no.

**DEZ:** Yeah that's... that's going to kill the vibe immediately. You've got this beautiful katakana opener and then the character is a gray dot. That's a let-down moment.

**YUKI:** That's the first impression of the product's visual identity — the thing I'm going to screenshot and put on Instagram, or not — and it's a dot. A dot is not Showa city-pop. A dot is a bug.

**MARK:** I care less about the mascot art specifically, but even I can see that's mismatched brand promise. The logo screen sets an expectation. A placeholder breaks it.

**DEZ:** How hard is it to ship a temporary illustration? Even one vibe-correct placeholder beats a dot. Draw something, run it through AI, anything.

**YUKI:** It doesn't have to be the final polished character. It has to NOT be a gray dot. Even a rough sketch that matches the aesthetic is a thousand times better.

**MODERATOR:** Now I'm going to read you six status strings that appear when something's wrong. React in character — right register or wrong? First: "NO API KEY - ADD IT IN SETUP."

**DEZ:** Too much yelling. All-caps reads as an error state from 2009. I'd expect something like "No key found — Tap to set up."

**YUKI:** "ADD IT IN SETUP" — where is Setup? I don't know where Setup is yet. That string tells me something's wrong but not what to do. It needs a destination, not a direction.

**MARK:** The information is correct, the tone is wrong. You want it to sound like the car is telling you something, not like a Windows dialog box.

**MODERATOR:** "NO SIGNAL OUT HERE."

**DEZ:** This one I actually like. "Out here" has character. Feels like the car is aware it's in a dead zone.

**YUKI:** Yeah this one has a voice. It's the only one that sounds like Kaze said it, not a system error.

**MARK:** Acceptable. I'd maybe want to know if it means cellular signal, GPS signal, or OBD signal — "No signal" covers three different things in a car app.

**MODERATOR:** "KEY PROBLEM - CHECK SETUP."

**YUKI:** Vague. "Key problem" — which key? The API key? My physical car key? "Check Setup" — I just opened this app for the first time, I don't know where Setup is.

**DEZ:** This is the one where I uninstall. If I paste a key and I get "KEY PROBLEM" with no explanation, I'm going to Google "nightrunner app key problem" and find nothing and be done.

**MARK:** At minimum it needs to distinguish: key is invalid vs. key is expired vs. key has no quota. Those are three different user actions.

**MODERATOR:** "KEY RATE-LIMITED - TRY AGAIN SOON."

**MARK:** This is actually the most useful one. It tells me exactly what's wrong and what to do. No action needed from me, just wait.

**DEZ:** Yeah this one's fine. "Soon" is vague but like, what are you gonna say, "try again in 47 minutes"? Fine.

**YUKI:** It's clear. I know what rate-limited means from Instagram, Spotify, everything. This one lands.

**MODERATOR:** "CONNECTION LOST - TAP TO RETRY."

**DEZ:** This is good. Actionable. Tells me what happened and what to do.

**YUKI:** The action is right there in the string. I can follow it. Good.

**MARK:** Correct and clear. No issues.

**MODERATOR:** "ON A CALL."

**DEZ:** Simple, obvious, done.

**YUKI:** Yeah this is fine. Though — is this telling ME I'm on a call, like I forgot? Or is it telling me Kaze is paused because of a call?

**MARK:** Good question. I read it as "AI is muted because you're on a call." But Yuki's right — subject is ambiguous. "Paused — you're on a call" would be clearer.

**MODERATOR:** Back to the BYO key problem. YUKI, where precisely do you rage-quit?

**YUKI:** The instant the card says "API key" and there's a text box with no explanation of what I'm looking at. If I don't understand what I'm pasting and why, I'm not pasting anything. I don't know if this is a password. I don't know if I'm giving a random company my Google credentials. That's the quit point. Step one.

**DEZ:** For me it's the head unit keyboard. If I'm in the car trying to paste a 40-character string on a resistive touchscreen, I will close this app and watch a YouTube video about a different app. The QR/phone-handoff setup flow is not optional, it's mandatory.

**MARK:** I don't quit on the key step — I get it, it's BYO-key, that's the trade. My quit point is if the OBD data is wrong or missing and the app still speaks confidently. If Kaze says "your coolant temp looks good" and the OBD isn't connected yet, I've lost trust and I'm not getting it back.

**MODERATOR:** Last question. If you had to fix ONE thing in this funnel before launch, what is it?

**DEZ:** Ship ANY mascot art. Not a dot. The app opens on a promise of aesthetic and delivers a dot. Everything else is fixable in an update. The dot is a first-impression kill.

**YUKI:** The key explanation. Before you show the text box, show me one screen — ten words, a visual — that explains what this is, why I need it, and why it's safe. Something like: "Nightrunner uses Google's free AI. You get your own key — it never leaves your phone." Then the text box. Night and day difference.

**MARK:** Permission pre-screen. Tell me what each permission does for me and my wife's use case before Android asks. "We need Bluetooth to read your car's data. Location stays on-device." That's trust. Trust converts hesitation to Allow.

---

## Part 2 — Consumer Problems, Ranked

**Ranking method:** P = personas affected (max 3), S = severity score 1–5 (5 = likely quit/uninstall, 1 = mild friction). Score = P x S.

| # | Problem | Personas | P | S | Score |
|---|---------|----------|---|---|-------|
| 1 | API key step with no plain-language explanation — user doesn't know what it is, why they need it, or whether it's safe | DEZ, MARK, YUKI | 3 | 5 | 15 |
| 2 | Placeholder dot avatar on Cruise screen — breaks the brand promise set by the katakana opener | DEZ, MARK, YUKI | 3 | 5 | 15 |
| 3 | No permission pre-screen — Android dialog with no context, trust problem especially for secondary users (spouses, passengers) | DEZ, MARK, YUKI | 3 | 4 | 12 |
| 4 | Head-unit keyboard friction for 40-char API key paste — no phone-handoff / QR path mentioned | DEZ, YUKI | 2 | 5 | 10 |
| 5 | Error strings in all-caps system-dialog register ("NO API KEY - ADD IT IN SETUP," "KEY PROBLEM - CHECK SETUP") — cold, no voice, no destination | DEZ, MARK, YUKI | 3 | 3 | 9 |
| 6 | "KEY PROBLEM" is too vague — doesn't distinguish invalid / expired / quota-exhausted; no remediation path | DEZ, MARK | 2 | 4 | 8 |
| 7 | "NO SIGNAL OUT HERE" ambiguous signal type — cellular vs. GPS vs. OBD | MARK | 1 | 3 | 3 |
| 8 | "ON A CALL" — subject ambiguous (user on call vs. AI paused) | YUKI, MARK | 2 | 2 | 4 |
| 9 | OBD not paired at first launch — Kaze may speak confidently about car data she doesn't have yet | MARK | 1 | 4 | 4 |
| 10 | Voice quality anxiety — if Sulafat sounds like a navigation system, character illusion breaks at the most important moment | DEZ, YUKI | 2 | 3 | 6 |

---

## Part 3 — Recommendations

### Copy Changes

**C-1 — API key card headline** (addresses problem #1)
Replace: "Nightrunner needs a Gemini API key to think."
Replace with a two-layer treatment:
- Above the text field: "Nightrunner runs on Google's free AI — you bring your own key."
- Subtext (small, secondary color): "Your key is stored encrypted on this device only. We never see it."
- CTA below the AI Studio link: "Get a free key — takes about 2 minutes."
Target: zero ambiguity about what this is, why it's safe, and that "free" is part of the deal.

**C-2 — "KEY PROBLEM - CHECK SETUP"** (addresses problem #6)
Replace with three distinct strings:
- Invalid/unrecognized: "Key not recognized — double-check it in Setup"
- Quota exhausted: "API quota used up — check your Google AI Studio dashboard"
- Expired/revoked: "Key was rejected by Google — you may need a new one in AI Studio"
Each needs a tappable destination or a next-step action embedded in the string.

**C-3 — "NO API KEY - ADD IT IN SETUP"** (addresses problem #5)
Replace: "No key yet — tap here to add one" (make the string itself the tap target, routes to FirstRunScreen key card or ControlPanel Setup entry).

**C-4 — "ON A CALL"** (addresses problem #8)
Replace: "Paused — call in progress" (clarifies Kaze is paused, not reminding user of obvious fact).

**C-5 — "NO SIGNAL OUT HERE"**
Keep the register — it's the one string that already sounds like Kaze. Optionally prefix with signal type on the rare case it matters: "No data signal out here" vs. "No GPS out here" vs. "OBD not connected." Use the character-voice version as default.

**C-6 — Kaze's intro speech** (addresses problem #9)
If OBD is not yet paired at first launch, Kaze's opening line should acknowledge it: "I can't feel the car yet — we'll connect OBD together." This prevents false confidence and sets correct expectation.

---

### UX Changes

**U-1 — API key explainer screen before the text field** (addresses problem #1, highest priority)
Insert one screen before the key-paste card. Not a long tutorial — just:
- Visual of a key icon or simple illustration
- Three bullet points, plain language: what Gemini AI is, what the key does, why it stays local
- "Continue" goes to the paste card
This single screen converts YUKI from rage-quit to proceed.

**U-2 — Phone-handoff / QR setup path for head-unit keyboard problem** (addresses problem #4)
On the key-paste card, add secondary option below the text field: "Setting up from a car screen? Scan to continue on your phone." QR links to a lightweight web page or deep-link that lets user paste the key on their phone and have it pushed/shared back. Alternatively, accept the key via a share-sheet or NFC tap. This is a head-unit-first product — the 40-character paste is not acceptable as the only path.

**U-3 — Permission pre-screen before Android system dialog** (addresses problem #3)
Insert one screen before the system permission prompts. Three rows, one per permission, each with:
- Icon, permission name, one-line plain-language reason
- Example: "Microphone — so you can talk to Kaze hands-free"
- Example: "Bluetooth — to read live data from your car's OBD port"
- Example: "Location — for speed display and place memory, stays on-device"
Addresses MARK's spousal-user trust concern. Also converts the cold system dialog into a branded moment.

**U-4 — Placeholder avatar before mascot art ships** (addresses problem #2, critical for launch)
The gray dot is not acceptable for launch. Before final Kaze illustrations arrive, ship any of:
- An AI-generated placeholder in the Showa city-pop register (generated via AvatarStudio or external tool, clearly temporary)
- A silhouette / shadow treatment with Kaze's proportions in amber/magenta palette
- A stylized "K" lettermark in AriaColors that reads as intentional branding, not a broken image
The katakana opener sets a high aesthetic expectation. The dot kills it. This is a day-one blocker.

**U-5 — Kaze voice sample accessible before committing to setup** (addresses problem #10)
On the key-paste card or the explainer screen, offer a "Hear Kaze" preview — a short pre-recorded clip (Kaze saying the app name or a single welcome phrase) so users hear the voice register before they've invested in the setup process. Reduces voice-quality anxiety for YUKI and DEZ.

---

### Positioning Changes

**P-1 — Lead with what BYO key GIVES you, not what it asks of you** (addresses problem #1)
Current framing: "Nightrunner needs a Gemini API key." — This is a demand.
Reframe across all funnel surfaces: "You use your own Google AI — so Nightrunner never sees your data, never charges you monthly, and runs on Google's infrastructure." BYO key is the *advantage*, not the admission fee. Play Store description, screenshots, and the in-app explainer should all use this frame.

**P-2 — Play Store listing must pre-empt the key shock** (addresses problems #1 and #4)
The Play Store listing should explicitly say, in the description and/or screenshots: "Requires a free Google Gemini API key (2-minute setup, link provided in app)." This way no one hits the key screen without warning. YUKI's rage-quit is much less likely if she knew before paying $5.

**P-3 — "The car that knows itself" framing should appear on the key-paste card** (addresses problem #1)
The one-line product positioning should be visible during the hardest UX moment — the key paste. Reminds the user what they're unlocking. "This is how Nightrunner knows your car." — keeps them emotionally engaged through the friction.

---

## Honesty Footer

These findings are produced by three simulated personas constructed from demographic archetypes and product-category priors. They are directional signal intended to surface likely friction points cheaply, before spending real-user research budget.

**They are NOT a substitute for real user research.**

Findings most in need of real-human validation:

1. **YUKI's API-key quit point** — the explainer-screen recommendation (U-1) is high-confidence directionally but the exact copy and visual treatment need testing with actual non-technical users. The threshold between "this makes sense now" and "still confused" is not knowable from a simulation.
2. **Head-unit keyboard problem** (U-2) — the severity depends entirely on what head units people are actually running. Some Android head units have decent clipboard support. The QR/phone-handoff path is high-effort to build; real user reports on whether they set up in the car or at a desk first would inform whether to prioritize this pre-launch or post-launch.
3. **Voice quality break point** (U-5) — DEZ and YUKI's anxiety about Sulafat's voice quality is directional. Only a real listen test with the target audience on actual hardware can confirm whether Sulafat clears the threshold. A five-person Discord listen session would answer this for near-zero cost.
4. **Placeholder art severity** — all three personas reacted strongly but this is a simulation of people who understand design. Real JDM/enthusiast users may be more forgiving of a temporary placeholder IF the rest of the product impresses. Test before pulling the launch date over it; but ship with something better than a dot if at all possible.
