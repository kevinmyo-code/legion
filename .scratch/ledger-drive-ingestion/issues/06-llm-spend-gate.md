# How is LLM spend estimated and approved before a batch?

Type: grilling
Status: open
Blocked by: 05

## Question

Kevin's call: deterministic parsers run free, but if N files fall through to
`LedgerStatementAgent`, show the count and a rough cost and wait for a go-ahead before any Gemini
call. Protects against a sixty-file first sync quietly spending real money on his own key.

Decide:

1. **When the count is known.** Layout recognition happens inside `StatementDispatcher`, which
   currently parses and falls through in one step. Getting a count without doing the work needs a
   cheap recognize-only pass. Does one exist, and what does it cost to add?
2. **The cost model.** Statement PDFs vary hugely in page count. What is the input-token estimate
   per file, and what is the output estimate? Dispatch `analyst` for the arithmetic rather than
   guessing, and state the model and its pricing class explicitly.
3. **Honesty of the number.** CLAUDE.md §4 rule five: anything not stated by a source must be
   surfaced as an estimate. A cost projection is an estimate by definition and must be labelled
   one. Decide the wording.
4. **The approval surface.** A dialog before the batch starts, or a two-phase run where
   deterministic files import immediately and the LLM set waits behind a prompt? The second is
   better UX and more complex.
5. **Persistence of the choice.** Does "yes, run the LLM" stick for the session, for the folder,
   or is it asked every batch? An always-ask that fires on every rescan will train Kevin to click
   through it, which defeats the gate.
6. **Failure accounting.** If an LLM call runs and the result still quarantines, the money is spent
   and nothing is gained. Is that surfaced?
