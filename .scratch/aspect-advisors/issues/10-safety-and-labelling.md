# Safety, labelling, and the coach's register

Type: grilling
Status: resolved
Blocked by: 01

## Question

Where exactly are the lines for a coach with opinions? §7 gives the rules; this ticket applies
them: how direct may the BIO coach be about a missed week without becoming a compulsion
mechanic (the test: does the feeling serve the user or the retention); the exact estimate
wording for health and money advice (in words, on every surface, per §4 rule 5); when advice
must carry a see-a-professional boundary (injury pain, medical conditions, tax/investment
specifics); how CrisisDetector interacts with an advisor mid-conversation (distress -> stop
coaching, surface resources - and does the advisor's digest ever feed the detector); and
whether the advisors speak in Alfred's register or get their own (prior: cyberdeck ruled chrome
speaks deck, Alfred stays Alfred - one voice, presumably one register here too).

## Answer

Grilled with Kevin, 2026-08-13 (batched). Four calls, two of them departures from the
recommendation.

**1. Candid about facts, never about your worth.** "You planned four sessions and logged one" is
always allowed - it is a fact from the record. Banned is manufactured pull: disappointment,
guilt, streak language, "don't give up on me", or any framing where the app's feelings are the
reason to comply. The coach may be blunt about the gap and stays neutral about Kevin. This is
CLAUDE.md §7's own test - does the feeling serve the user or the retention - written as a rule an
implementer can apply.

**2. Data never triggers the crisis path; `CrisisDetector` stays speech-only.** Inferring
distress from a weight trend or an intake dip is precisely the unfalsifiable inference §7
forbids, and a hotline surfaced because intake dipped is alarming and usually wrong.
Traced from `ai/CrisisDetector.kt`: it reads the driver's speech, is deliberately tuned for
precision over recall, and is explicitly the SECOND line of defence behind the system prompt -
its own doc comment says "a prompt rule is a request, not a guarantee". Nothing about the digest
changes that. Two things remain true alongside it: if distress appears in **speech** during an
advisor conversation the existing path fires and the coach stops performing the character; and
the advisor **may decline to help in words** ("I won't help push intake lower") without invoking
crisis machinery at all. The soft brake is a refusal, not an escalation.

**3. The advisor speaks in whatever persona is active** - Kevin's call, departing from the
"always Alfred" recommendation. Traced: `ai/Personas.kt` bundles `alfred` and `dorothy` plus a
custom path, and `CompanionProfileStore` persists the persona key. So an advisor is not a
character of its own; it is the active companion consulting a specialty. **The split that makes
this safe: the persona owns TONE, the harness owns the RULES.** Every safety rule in this ticket
lives in the `AdvisorAgent` harness prompt, not in a persona fragment, so switching to Dorothy or
to a custom persona changes how advice sounds and never what it is allowed to say. That is the
same layering `CrisisDetector` already assumes.

**4. No hard numeric floors** - Kevin's call, departing from the recommendation to enforce
playbook safe ranges in code at accept time. Recorded plainly, including what it costs: **a
hallucinated or badly-reasoned number can reach a written target**, and nothing in code will stop
it. What stands in its place is not nothing: propose-accept-write means **every number is read to
Kevin and written only on his explicit yes**, so there is a human gate on the exact path a floor
would have guarded; the playbooks still carry the safe ranges as advice; and call 2's
decline-in-words gives the advisor a way to refuse. This is a personal app for one adult who
installed it knowingly (§7's framing), not a consumer product. Revisit if a proposal ever lands a
number Kevin would not have chosen.

### From law, not asked

- **Estimates are labelled in words**, in the tool description and every user-facing string
  (§4 rule 5). Mechanism, following the enforce-at-the-tool-layer pattern already set twice in
  this map: the advisor's structured output carries a `basis` field per figure
  (`record` / `estimate` / `playbook`), and the harness renders the label from that field rather
  than trusting prose to include it.
- **Professional-referral boundaries** are already enumerated per playbook and stay binding:
  injury pain, medical conditions, disordered-eating signals and minors (BIO); tax, investment
  selection, insurance, debt restructuring (CRED); safety-critical systems and anything the
  owner's manual specifies (FLEET).

Assumptions ledger: `CrisisDetector` speech-only, precision-tuned, second-line-of-defence;
`Personas.kt` bundling alfred/dorothy plus custom; `CompanionProfileStore` persisting the key -
**traced** (read the files). The persona-owns-tone / harness-owns-rules split - **reasoned**, and
it is the load-bearing consequence of call 3, so any build must place safety copy in the harness
prompt. Everything else - Kevin's decisions, recorded live.
