# Build: the AdvisorAgent harness

Type: task
Status: resolved
Blocked by: 11, 13

## Question

Implement the harness decided in [The advisor contract](01-advisor-contract.md): one
`AdvisorAgent` in `advisor/` wrapping `SubAgent`, five briefs.

- **Brief abstraction**: playbook, digest builder, writable-proposal schema. **Playbook and
  writable set are OPTIONAL** - HOME has neither ([The cross-aspect HOME advisor](09-home-advisor.md)).
  A harness that requires both is wrong.
- **One-shot `askTyped`**, not `investigate`: Flash cannot combine structured output with tool
  declarations. One POST carries brief + playbook + digest + goals + advice-log window + question
  and returns prose plus an optional structured proposal.
- **Shared rules live in the HARNESS prompt, never in a persona fragment**
  ([Safety, labelling, and the coach's register](10-safety-and-labelling.md)): candid about facts
  and neutral about Kevin, no manufactured pull (no guilt, streaks, disappointment,
  "don't give up on me"), professional-referral boundaries per playbook, and the estimate label
  rendered from a structural `basis` field (`record`/`estimate`/`playbook`) rather than trusted to
  prose.
- **Persona inheritance**: tone comes from the ACTIVE companion persona
  (`ai/Personas.kt`: alfred/dorothy/custom, via `CompanionProfileStore`). Persona owns tone, the
  harness owns the rules; switching persona must not change what an advisor may say.
- Every exchange writes an `advisor_advice` row; the last ~N per aspect ride the next digest
  (N per ticket 11's ceiling).

**Budget** ([Token and latency budget](11-token-latency-budget.md)): a drafted harness prompt
covering the shared rules measured **448 tokens** - that is the working figure, not a limit, but
the per-question ceiling it feeds is hard: **4,000 tokens per aspect question, 1,500 for HOME.**
The advice-log window of 3 exchanges costs 194 tokens and is affordable.

**UX**: `ask_advisor` is a sub-agent hand-off with real latency, like the existing
`diagnose_codes` tool - which already instructs the model to say something like "digging into it"
before the wait. Apply the same pattern rather than leaving a silent gap.

Verification: unit tests that a brief without a playbook or writable set still runs (HOME); that
harness safety copy is present regardless of persona; that a proposal round-trips through
`proposalJson`; and that a composed prompt per aspect measures under the ceiling.

## Build report

Built 2026-08-13. **Verification re-run by the orchestrator, not relayed.**

`advisor/`: `AdvisorAspect`, `DigestBuilder` (interface only), `DigestText` (shared formatters -
`line`, `withTier` reusing `plan.TrustTier`/`combinedTier()`, `unverified`, `estimate`,
`notLogged`), `AdvisorBrief` (playbook/synthesisNote default null, `writableOps` defaults empty,
so HOME needs no special-casing), `AdvisorAnswer` (+ `FigureBasis` record/estimate/playbook,
parse, fence-stripping), `HarnessPrompt` (RULES + LATENCY_HINT), `AdvisorAgent` (one-shot
`askTyped` via an injectable `subAgentFactory`, pure internal compose helpers, window = 3).

| Step | Result |
|---|---|
| `compileDebugKotlin -Pnokey` | BUILD SUCCESSFUL |
| `testDebugUnitTest --rerun-tasks` | **807 total / 0 failures / 0 errors** (summed from JUnit XML) |
| This ticket's suites | `AdvisorAgentTest` 17, `AdvisorAnswerTest` 11, `DigestTextTest` 9 - all green |
| HOME shape | tested: null playbook + empty writableOps composes without an empty `PLAYBOOK:` header |
| Safety copy | tested: harness rules present regardless of persona clause |

### Finding that corrects the contract - see [The advisor contract](01-advisor-contract.md)
**`askTyped` enforces no output shape.** No `generationConfig`/`responseSchema`/`responseMimeType`
in the request body (I read `SubAgent.kt` myself to confirm, rather than accept the claim).
Structured output is prompt instruction + parser, best-effort. The harness is honest about it
(`ParseFailed`), and hardening is filed as
[Harden structured output](21-harden-structured-output.md) - not folded in silently, because
`SubAgent` is shared with the pantry vision path.

### Claim I checked and found wrong
The agent reported "the parallel ticket 15/16/17 tests already present and green". Tickets 16 and
17 **had not been dispatched** and no such files exist; only ticket 15's playbooks were on disk.
The build is fine - the misattribution was in the narration, not the code - but it is the second
loose count claim from a build agent this session, which is why every number above was re-derived
from the XML rather than read off a report.
