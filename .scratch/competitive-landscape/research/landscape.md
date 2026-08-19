# Competitive landscape: who else is building a whole-life assistant

Research date 2026-08-16. Every claim carries the URL of the source that owns it. Claims that
could not be traced to a primary source are marked `unverified` or `secondary`. Web-only research;
nothing here is tested.

## 1. TL;DR verdict

LEGION's voice loop, LLM tool calling, sub-agent fan-out, and "assistant with memory" framing are
commodity - every player in both tiers has some version, and Google ships the same Gemini Live
plumbing LEGION rides on. Three things are genuinely rare, and one is (as far as this research
found) unique. Rare: (a) the no-backend + BYO-key + user's-own-Drive data plane - Home Assistant
is the only serious neighbor, and it stops at the smart home; (b) cross-domain ingestion of car
telemetry + bank statements + grocery receipts under one assistant - nobody else spans those
three, though Alexa+ ("upload documents, emails, photos... for Alexa to remember") and Gemini
Personal Intelligence (Gmail/Photos/YouTube/Search) are closing from the convenience side.
Unique, per this research: the hard reconciliation gate - refusing to STORE LLM-extracted rows
unless they sum exactly to the document's own printed anchor, with quarantine on mismatch and
provenance tags. Industry norm is confidence scores and warning flags (Veryfi returns
"subtotal not matching the sum of line item totals" as a warning, not a rejection:
https://www.veryfi.com/receipt-ocr-api/); nobody found treats reconciliation failure as a
write-barrier. The threat is not that someone copies LEGION; it is that big-tech convenience
(Astra-class Gemini + Personal Intelligence on the same phone) makes the sovereign version feel
unnecessary to everyone except people who chose sovereignty on principle.

## 2. Pro tier

| Player | What it is | Voice | Memory / personal context | Data plane | Status 2026-08 |
|---|---|---|---|---|---|
| Google Astra / Gemini | Research prototype feeding Gemini Live; "universal AI assistant" | Yes, native audio | Multimodal memory, learns preferences; Personal Intelligence connects Gmail/Photos/YouTube/Search | Google cloud | Astra trusted-testers; Personal Intelligence US Pro/Ultra beta since 2026-01-14 |
| OpenAI ChatGPT | Chatbot -> assistant; agent mode, Pulse | Voice mode | Saved memories (2024-02) + auto "dreaming" curation (2026-06) | OpenAI cloud | Shipped broadly |
| Amazon Alexa+ | Gen-AI Alexa rebuild | Yes | "Knows what you've bought/listened/watched"; user-uploaded docs/emails/photos | Amazon cloud (Bedrock) | US/Canada shipped, $19.99/mo or free w/ Prime |
| Apple Siri | "More personalized Siri" | Yes | Promised: personal context + in-app actions | Apple cloud + on-device | Delayed to spring 2026; not verified shipped as of writing |
| Meta AI (Ray-Ban) | Glasses-first assistant | Yes | Live AI sessions; acquired Limitless (pendant + Rewind) 2025-12 | Meta cloud | Shipping at scale; $299-799 hardware line |
| Microsoft Copilot | Work assistant | Yes | Copilot Memory (prefs/facts), stored in Exchange mailbox | Microsoft cloud | GA rolling out from 2026-01 |
| Rabbit r1 | $199 device + rabbitOS agents | Yes | Cloud agents; "proactive rabbit" 2026-07 | Rabbit cloud; BYOK added | Alive, niche, no subscription |
| Humane AI Pin | Wearable assistant | Yes | Cloud memory | Humane cloud | DEAD; HP bought assets $116M, pins bricked 2025-02-28 |
| Limitless / Rewind | Pendant + lifelogging | Capture only | Everything heard/seen | Their cloud | Acquired by Meta 2025-12; Pendant sales halted, Rewind sunset |
| Friend.com | Companion pendant | Yes (2.0 speaker) | Companion memory ($10/mo plan) | Their cloud | Relaunched 2026-07 at $249 |
| Personal AI / Mem | Memory platforms | No/limited | Trained-on-you model / AI notes | Their cloud | Alive; Personal AI pivoted enterprise, Mem 2.0 early 2026 |

### Google: Project Astra + Gemini Live + Personal Intelligence

- Astra per DeepMind's own page: "research prototype... on the way to building a universal AI
  assistant". Capabilities listed: multimodal memory, "learns and retains user preferences",
  tool use over Search/Gmail/Calendar/Maps, screen sharing, interface control, proactive
  responses. Still limited to "trusted testers" with a waitlist.
  https://deepmind.google/models/project-astra/
- The I/O 2025 framing is explicit: Gemini is being extended "to become a world model", Astra
  capabilities land in Gemini Live. https://blog.google/innovation-and-ai/models-and-research/google-deepmind/gemini-universal-ai-assistant/
- Already shipped: Gemini Live camera + screen sharing free to all Android users (2025-05
  rollout; secondary confirmation https://9to5google.com/2025/05/23/gemini-live-free-camera-screen-sharing/,
  first-party usage page https://blog.google/products-and-platforms/products/gemini/gemini-live-android-tips/).
  This is the exact API surface LEGION's voice loop is built on - the loop itself is commodity by
  construction.
- Personal Intelligence (2026-01-14): connects Gmail, Photos, YouTube, Search "in a single tap";
  off by default; "Gemini doesn't train directly on your Gmail inbox or Google Photos library";
  US Pro/Ultra beta, free tier planned.
  https://blog.google/innovation-and-ai/products/gemini-app/personal-intelligence/
- What it does NOT ingest: bank statements as reconciled ledger rows, OBD/car telemetry, receipt
  line items. It infers (their own example: wrong golf inference from photos) rather than
  verifies. Data plane is Google's cloud, account-keyed.

### OpenAI

- Memory: saved memories launched 2024-02 (https://openai.com/index/memory-and-new-controls-for-chatgpt/);
  chat-history reference 2025-04; new "dreaming" architecture announced 2026-06 - background
  curation of memories, memory summary page, factual recall claimed 67.9% -> 82.8%
  (https://openai.com/index/chatgpt-memory-dreaming/; page blocked fetch, contents corroborated
  via secondary coverage https://9to5mac.com/2026/06/04/openai-says-chatgpts-memory-feature-is-getting-smarter-and-coming-to-free-users/).
- Agent ambitions: ChatGPT agent (2025-07-17, virtual computer, browser, forms)
  https://openai.com/index/introducing-chatgpt-agent/; ChatGPT Pulse (2025-09-25, proactive
  overnight briefs from chats + connected calendar, Pro tier first)
  https://openai.com/index/introducing-chatgpt-pulse/.
- Context held: conversations, uploaded files, connected apps (calendar). Not held: car data,
  reconciled finances, receipts. All in OpenAI's cloud. Pulse is proactive-engagement-shaped -
  exactly the mechanic LEGION bans.

### Amazon Alexa+

- Official announcement (2025-02-26, updated 2026-07-21): $19.99/mo, free with Prime; Bedrock
  LLMs; personalized - "knows what you've bought, what you've listened to, the videos you've
  watched"; users can upload "documents, emails, photos, and messages... for Alexa to remember,
  summarize, or take action on". US + Canada, EU early access.
  https://www.aboutamazon.com/news/devices/new-alexa-generative-artificial-intelligence
- Closest pro-tier product to "ingest my documents into my assistant". No reconciliation concept,
  no structured ledger, data in Amazon's cloud, funded by commerce - the assistant that knows
  your purchases is owned by the store.

### Apple

- 2025-03-07 statement to press (no apple.com page found; primary is the quoted statement):
  working on "a more personalized Siri, giving it more awareness of your personal context...
  It's going to take us longer than we thought." Delayed to "the coming year".
  https://www.cnbc.com/2025/03/07/apple-delays-siri-ai-improvements-to-2026.html
- Spring 2026 target per MacRumors (secondary, `unverified` whether it shipped by now):
  https://www.macrumors.com/2025/06/12/apple-intelligence-siri-spring-2026/
- If/when it ships: on-device + Private Cloud Compute personal context across apps. Strongest
  privacy posture of the big five, but closed, Apple-account-keyed, and two years late.

### Meta

- Ray-Ban Meta / Oakley / Meta Glasses line, incl. $799 Ray-Ban Display: https://www.meta.com/ai-glasses/
- Acquired Limitless (pendant lifelogger, ex-Rewind) 2025-12-05; Pendant sales halted, Rewind
  Mac app sunset, users moved to free plan; CEO joining to build toward "personal
  superintelligence for everyone". https://techcrunch.com/2025/12/05/meta-acquires-ai-device-startup-limitless/
  (secondary; Limitless's own announcement was on its site/X).
- 2026 "super sensing" multi-hour background Live AI + facial recognition: `unverified`, reporting
  only. https://www.uploadvr.com/next-gen-ray-ban-meta-2026-super-sensing-facial-recognition-live-ai/
- Meta is buying its way to always-on life capture. Ad-funded; the compulsion-mechanic pressure
  LEGION bans is Meta's core business model.

### Microsoft

- Copilot Memory: remembers preferences/facts, memories stored in the user's Exchange mailbox
  under existing compliance policy. First-party: https://techcommunity.microsoft.com/blog/microsoft365copilotblog/introducing-copilot-memory-a-more-productive-and-personalized-ai-for-the-way-you/4432059
  and https://learn.microsoft.com/en-us/microsoft-365/copilot/copilot-personalization-memory
- Work-graph context (email, docs, meetings), not life context. No car, no personal finance
  ingestion, no receipts.

### Rabbit

- Alive and iterating: rabbitOS 2 (2025-09-08, card UI + "creations" agentic tools,
  https://www.rabbit.tech/newsroom/rabbitos-2-launch); intern general agent (2025-06);
  rabbitOS 2.2 (2026-06-18) put Claude Code sessions on the r1; rabbitOS 2.3 (2026-07-10) added
  a "proactive rabbit" and notably BYOK - Anthropic and OpenAI API key support.
  https://www.rabbit.tech/updates
- BYOK arriving on a consumer device is a small signal that LEGION's key posture is spreading.
  Still Rabbit-cloud-centric, no life-data ingestion.

### Humane, Limitless, Friend - the wearable-cloud cautionary tales

- Humane: HP bought assets for $116M; pins stopped functioning 2025-02-28 noon PST; server data
  deleted. (Secondary reporting; consistent across outlets.)
  https://www.ghacks.net/2025/02/19/humanes-ai-pin-ceases-operations-following-hp-acquisition/
- Limitless: acquired, sales halted, EU/UK/+5 regions cut off with a data-export deadline.
  https://techcrunch.com/2025/12/05/meta-acquires-ai-device-startup-limitless/
- Friend 2.0 relaunched 2026-07 at $249 + $10/mo memory plan (companion, not utility).
  https://techcrunch.com/2026/07/30/friend-the-lonely-ai-wearable-returns-with-a-new-voice-and-a-much-bigger-price-tag/
- Pattern: when the vendor's cloud dies or is sold, the "persistent memory of your life" dies or
  changes hands with it. This is the strongest external argument for LEGION's no-backend rule.

### Personal-memory platforms

- Personal AI (personal.ai): "Memory Stack" + personal language model trained on you; pivoted to
  enterprise/regulated-industry expert cloning. Their cloud. https://www.personal.ai/
- Mem 2.0 (early 2026): AI notes "thought partner", proactive context panel. Their cloud.
  https://get.mem.ai/blog/introducing-mem-2-0
- Neither ingests structured life data (finances, car, food); both hold prose.

## 3. Open-source / amateur tier

| Project | What it is | Arch | BYO key | Data plane | Alive? |
|---|---|---|---|---|---|
| Home Assistant Assist/Voice | Smart-home voice + LLM fallback | Self-hosted hub + $59 Voice PE box | Yes: Google/OpenAI/Anthropic/OpenRouter/Ollama | Fully local possible | Very - flagship OSS effort |
| OVOS / Neon | Mycroft successors | Self-hosted Linux voice OS, Foundation-backed | Plugin-based STT/TTS/LLM | Local-first | Yes, steady releases into 2026 |
| Leon | Personal assistant server | Node/Python self-hosted | Yes | Local | Mid-rebuild ("2.0 developer preview", agentic core) |
| Open Interpreter 01 | Open voice interface to a computer agent | ESP32 + server | Yes | Local/self-host | Pivoted to app, hardware refunded; maintenance doubts |
| Willow | ESP32-S3 voice satellite | Self-hosted inference server | Local models | Local | Slowed; founder died, community stewardship |
| Omi (ex-Friend) | Open wearable lifelogger | Flutter app + FastAPI/Firebase cloud, MIT, 13.2k stars | Not the default; cloud-first quickstart | Firebase (their cloud) despite OSS code | Very active |
| Khoj | "AI second brain" over your docs | Self-hostable RAG + agents | Yes, any LLM | Local or their cloud | Active |
| microsoft/JARVIS | HuggingGPT research code | LLM orchestrating HF models | n/a | n/a | Dormant research artifact |
| sukeesh/Jarvis | Python CLI assistant | Local scripts | n/a | Local | Hobby-tier, ~3.6k stars |

### Notes

- **Home Assistant** is the serious one. Assist runs deterministic intent matching FIRST and
  falls through to an LLM only for what it cannot parse - structurally the same
  "deterministic first, LLM fallback" shape as LEGION's StatementDispatcher, arrived at
  independently for latency/reliability: "Assist will handle commands first. Only questions or
  commands it can't understand will be sent to the AI."
  https://www.home-assistant.io/blog/2025/09/11/ai-in-home-assistant/
  Voice Preview Edition hardware shipped 2024-12-19 ("The era of open voice assistants has
  arrived", https://www.home-assistant.io/blog/2024/12/19/voice-preview-edition-the-era-of-open-voice/;
  product page https://www.home-assistant.io/voice-pe/). Everything can run "without any data
  leaving your home". Domain ceiling: the home. No finances, no car (beyond integrations),
  no receipts, no whole-life memory ambition.
- **OVOS/Neon**: OpenVoiceOS Foundation formalized 2025 (secondary:
  https://www.cnx-software.com/2025/02/24/the-openvoiceos-foundation-aims-to-enable-open-source-privacy-and-customization-for-voice-assistants/),
  first-party history https://blog.openvoiceos.org/posts/2025-05-20-ovos-and-mycroft-a-fork-that-wasnt-meant-to-be.
  Releases continuing into 2026 (PyPI cadence). Voice OS plumbing, not a life-context brain.
- **Leon** (https://github.com/leon-ai/leon): core being rebuilt "around tools, memory, context,
  and agent-style execution"; master is the stable pre-agentic branch. Ambition overlaps
  LEGION's shape (self-hosted personal assistant), delivery repeatedly resets.
- **Open Interpreter 01** (https://github.com/openinterpreter/01): refunded all hardware orders
  and pivoted to an app ("It should have been an app",
  https://changes.openinterpreter.com/log/01-app); main repo has an open "IS THIS PROJECT DEAD?"
  issue (https://github.com/openinterpreter/open-interpreter/issues/1627).
- **Willow** (https://github.com/HeyWillow/willow): continues under community stewardship;
  releases dedicated to founder Kristian Kielhofner's memory. Satellite hardware, not a brain.
- **Omi** (https://github.com/BasedHardware/omi): the most alive open "remember my life" project.
  MIT, 13.2k stars, 33k commits, plugin store, MCP server. But the README's quickstart is
  cloud-first and the backend is their Firebase/Deepgram stack - open code, closed-by-default
  data plane. Ingests conversations/screen, not structured documents.
- **Khoj** (https://github.com/khoj-ai/khoj): self-hostable RAG over PDFs/notes/Notion + agents +
  scheduled automations, any LLM incl. local. Closest OSS analog to "assistant over my
  documents", but retrieval-shaped: it answers over documents, it does not extract, verify, and
  commit structured rows.
- **GitHub "Jarvis" projects**: microsoft/JARVIS is a 2023 HuggingGPT research artifact
  (https://github.com/microsoft/JARVIS); sukeesh/Jarvis is a Linux/macOS CLI helper
  (https://github.com/sukeesh/Jarvis). Neither is a living whole-life assistant; the name is
  common, the ambition is not delivered anywhere in the tier.
- **Car niche**: LLM-over-OBD apps exist (OBDAI https://obdai.app/, OBD2AI
  https://github.com/JaKuBisz/OBD2AI, MECH AI). All are single-domain diagnostics chatbots;
  none join car data to anything else about the owner's life.

## 4. Who comes closest to whole-life context (ranked)

1. **Google (Astra -> Gemini + Personal Intelligence).** Stated goal is literally LEGION's end
   vision: a universal assistant with memory, tool use, and your Google-account life
   (mail, photos, search history) as context. Holds: communications, media, location, search
   intent. Does not hold: verified financial ledger rows, car telemetry, receipt-level food
   data. Data plane: Google's cloud, opt-in connections, no-training-on-inbox claim. Gap vs
   LEGION: inference over your exhaust vs verified ingestion of your records; their memory is
   probabilistic recall, LEGION's is a reconciled database.
2. **Amazon Alexa+.** Only pro player that invites arbitrary document/email/photo upload into
   assistant memory and already holds a purchase history. No verification layer, no car, cloud
   only, commerce-funded.
3. **OpenAI ChatGPT.** Deep conversational memory + agents + proactive Pulse, but context is what
   you tell it or connect; no structured life-data ingestion; their cloud.
4. **Meta.** Highest sensor ambition (all-day glasses + acquired lifelogging team); context is
   ambient audio/video, not records; weakest sovereignty posture of all.
5. **Omi.** Amateur-tier leader for raw life capture (everything heard/seen), zero verification,
   their cloud by default.
6. **Home Assistant.** Best sovereignty, real voice stack, but scope deliberately ends at the
   home. It is LEGION's data-plane sibling, not a whole-life competitor.
7. **Apple.** Would rank higher if the personalized Siri had shipped; as of the 2025-03 statement
   it was a promise, and spring-2026 delivery is `unverified` here.

Nobody found - either tier - ingests bank statements, car telemetry, and grocery receipts under
one assistant. That intersection is empty except for LEGION.

## 5. Structural moats / differences

- **The reconciliation gate is unique as a storage rule.** Commercial extraction APIs do the
  arithmetic (Veryfi flags "subtotal not matching the sum of line item totals") but surface it
  as a warning for downstream human review, and their pitch is accuracy percentage, not refusal.
  https://www.veryfi.com/receipt-ocr-api/ No assistant product found refuses to persist
  LLM-extracted data absent an exact match to a document-stated anchor, quarantines whole
  documents, or tags row provenance (DETERMINISTIC / LLM_RECONCILED / UNRECONCILED). Big-tech
  memory systems ship the opposite posture: OpenAI publishes recall percentages
  (67.9% -> 82.8%) and Google warns its own feature makes wrong inferences you must correct.
  Probabilistic memory is their product; verified memory is LEGION's.
- **Data plane.** Every pro player: their cloud, their account. OSS tier: local possible
  (HA, OVOS, Khoj) but none pairs local-first with a phone-native assistant over life records.
  LEGION's plane (device Room DB + owner's own Drive appDataFolder + owner's own Gemini key,
  zero developer infrastructure) has no exact match anywhere in this survey.
- **Mortality.** Humane bricked, Limitless absorbed and region-cut, Rewind sunset, Friend
  re-priced with a memory subscription. Cloud-tethered life memory has died three times in
  eighteen months. Clone-and-run with no vendor to die is a structural answer, not a feature.
- **Business-model pressure.** Pulse manufactures a daily return visit; Alexa+ is a Prime
  retention asset; Meta is ad-funded; Friend charges monthly for memory. LEGION's ban on
  compulsion mechanics is only credible BECAUSE there is no revenue to optimize - the moat is
  the absence of an incentive, which no funded competitor can copy.
- **BYO key is diffusing** (Rabbit 2.3, HA's provider list, Khoj), so key posture alone will not
  stay differentiating. The combination (BYO key AND no backend AND own-cloud sync AND gate)
  is the defensible bundle.

## 6. Honest threats: what makes LEGION pointless in 18 months

1. **Gemini Personal Intelligence eats the convenience case.** Same phone, same Google account,
   zero setup, free tier planned. If Google adds structured extraction over Gmail'd statements
   and receipts (they own the inbox most statements land in), "good enough, no effort" beats
   "verified, some effort" for almost everyone. LEGION survives only for owners who want the
   verified/sovereign version - which is its actual audience of one, so "pointless" here means
   pointless to generalize, not pointless to run.
2. **Astra-class proactivity ships broadly.** Memory + computer control + camera, always on,
   improving monthly with model releases LEGION also depends on. LEGION's voice layer rides
   Gemini Live; Google can always be a model generation ahead of any BYO-key app on latency,
   duplex behavior, and price - or restrict the Live API tiers a hobby key can reach.
3. **API dependency is the single point of failure.** LEGION has no backend but does have one
   vendor: a Gemini Live pricing change, quota squeeze, or deprecation of the WebSocket STS
   surface strands the core loop. (Reasoned from architecture, not from any announced Google
   plan.)
4. **Alexa+ document memory grows a verification story.** Amazon already ingests uploaded
   documents and knows purchases; adding receipt itemization is adjacent to their retail data.
   Unlikely to add sovereignty, but it erodes the "nobody ingests your records" claim.
5. **An OSS convergence.** Home Assistant's trajectory (Assist + BYO LLM + local pipeline +
   hardware) plus an Omi-like capture layer plus a Khoj-like document brain is all the pieces of
   LEGION with communities behind them. Nobody has glued them, and each stops at its domain
   boundary - but the parts exist, MIT-licensed.
6. **Apple ships and it is good.** A personal-context Siri with on-device processing would be the
   only big-tech offer that competes on privacy rather than convenience. Two years of delay
   suggest low probability inside 18 months; nonzero.

What survives all six: the gate (nobody wants to ship refusal-to-store as UX), the empty
car+money+food intersection, and the no-vendor-to-die property. What does not survive: any claim
that the voice loop, memory-in-general, or proactivity-in-general is special.
