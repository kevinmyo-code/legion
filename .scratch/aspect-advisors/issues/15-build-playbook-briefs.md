---
map: aspect-advisors
ticket: 15
title: "Build: ship the four playbooks as briefs"
type: task
status: resolved
status-detail: ""
blockers: ["11"]
blocked-by: ["[[11-token-latency-budget]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: ship the four playbooks as briefs

## Question

Turn the four research drafts into shipped briefs: `research/bio-playbook.md`,
`log-playbook.md`, `fleet-playbook.md`, `cred-playbook.md` become constants (or bundled assets -
**bundled, never fetched at runtime**, CLAUDE.md §7) that the harness composes into a prompt.

**Trim each to the measured ceiling: 2,500 tokens per playbook**
([Token and latency budget](11-token-latency-budget.md)). Measured sizes, Sources stripped:
BIO 2,397, CRED 2,240, LOG 1,994 - all under. **FLEET is 2,909 and must lose ~409 tokens (~16%)**;
the analyst named the 17-row interval table and the seasonal-care / DIY-vs-shop prose as the
candidates. The cut is a judgement call about what a coach needs in the prompt versus what was
useful research - record what was dropped on this ticket so the trim is reviewable.

**Do NOT ship the `## Sources` section to the model.** It is dev-facing licensing documentation,
costs 500-700 tokens per aspect, and buys zero coaching value (measured). Keep it in the research
files, strip it from the shipped brief.

Preserve verbatim, in every playbook: the estimate-phrasing requirements and the
professional-referral boundaries (BIO: pain, medical conditions, disordered-eating signals,
minors, PEDs. CRED: tax, investment selection, insurance, debt restructuring. FLEET:
safety-critical systems and anything the owner's manual specifies). These are the parts a trim
must never reach.

No HOME playbook - HOME gets a synthesis brief only, written on ticket 14 or 17.

Verification: total prompt size per aspect measured against the ceiling, not estimated.

## Build report

Built 2026-08-13. Four standalone `object XPlaybook { const val TEXT = ... }` files in
`advisor/playbooks/`, no dependency on the harness package. `## Sources` stripped from all four.

**Measured with `countTokens` (free endpoint, no billed call):**

| Aspect | Tokens | Ceiling | Margin |
|---|---|---|---|
| BIO | 2,078 | 2,500 | 422 |
| LOG | 1,731 | 2,500 | 769 |
| CRED | 1,846 | 2,500 | 654 |
| **FLEET** | **2,497** | 2,500 | **3** |

**FLEET trimmed 2,909 -> 2,497 (~412 tokens, ~14%)**: interval-table Notes folded into one line
per item (every figure and the interference-engine timing-belt warning kept verbatim); seasonal
and DIY-vs-shop prose condensed to lists (every action item and dollar range survives, only
connective justification cut); the DTC cross-cutting closing paragraph tightened with all four
rules intact. **Untouched by design:** the identity/estimate framing, the three DTC triage tiers
including the flashing-MIL stop rule, and the hard-deferral list.

### Risk worth naming: FLEET has a 3-token margin
That is effectively zero headroom - any future edit to `FleetPlaybook.kt` will breach the ceiling.
Mitigation in place: `PlaybookKeywordsTest` carries a chars/4 ceiling tripwire plus
referral-boundary keyword assertions, so neither a size breach nor a silent deletion of the safety
content passes CI. **Anyone editing FLEET must re-measure with `countTokens`, not trust the
tripwire's estimate.** If FLEET needs to grow, trim the interval table further rather than the
safety sections.

Verification (orchestrator re-run): `compileDebugKotlin` and `testDebugUnitTest --rerun-tasks`
both BUILD SUCCESSFUL, **807 tests / 0 failures**; `PlaybookKeywordsTest` 6/6 green.

Kotlin gotcha caught by the compiler, not review: raw triple-quoted strings still interpolate
`$identifier`, so a literal `$X` or `$A/month` needs `${'$'}` escaping to stay `const val`.
