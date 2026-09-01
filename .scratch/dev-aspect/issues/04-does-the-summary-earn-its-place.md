---
map: dev-aspect
ticket: "04"
title: "Does the prose summary earn its place"
type: grilling
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-seventh-aspect-on-the-engine]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Does the prose summary earn its place

## Question

Kevin's opening idea on 2026-09-01 was *"a summary md etc. that states the project state, whats
left to do."* Charting decision 4 already forbids that prose from answering "what is pending" - that
comes from issues, PRs and ticket frontmatter, which are falsifiable. What is left is narrower: is
there a prose field at all, answering "what IS this project," and does it survive its own rot?

The case against it is this repo's own history. CLAUDE.md was cut from 778 lines on 2026-09-01
because hand-written descriptions of a moving codebase were wrong every time, sixteen lines of it
struck through. `MEMORY.md` was cut the same week. A per-project `summary_text` is the same
artefact with the same failure mode, now read aloud by a voice assistant that sounds certain.

The case for it: "what is this project" genuinely is not derivable from issue titles, and a repo
called `andromeda` tells a listener nothing.

## Decide

1. Does `summary_text` exist? If not, this ticket closes and nothing downstream changes.
2. If it exists, who writes it - Kevin by hand, or an LLM over the README?
3. Section 4 rule 5: anything the source does not state is an estimate and must be labelled one.
   An LLM-written summary is an estimate. Where does the label appear - in the tool description,
   in the spoken answer, or on the detail screen? Section 7's rule is that estimates are labelled
   in BOTH the tool description and any user-facing string.
4. Does it carry `summary_written_at`, and does the assistant say the age out loud when the summary
   is older than some threshold? Pick the threshold.
5. Hard boundary to write into the tool description: the summary may describe what a project IS and
   may never be used to answer what is PENDING, outstanding, next, or left to do.

## Verification

- Whatever is decided, a test asserts the tool description contains the estimate label, in the
  shape of `AriaBrainHonestyClauseTest` - presence, not obedience.
- The memory note `legion-trust-disclosures-are-not-furniture` applies: an estimate disclosure may
  not collapse behind a HelpRow.
