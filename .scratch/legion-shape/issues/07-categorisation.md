# How does a transaction get a category?

Type: grilling
Status: resolved (2026-08-07, Kevin)
Blocked by: 05

## Question

Kevin's answer (2026-08-06) settled the mechanism: **your rules first, AI for the rest, always
editable.** This ticket specifies it. Categorisation is on the critical path for the budget AND for
meals' macros, and `memory/MEMORY.md` lists it as never built.

1. **The category set.** Fixed list, or freeform? Shared between ledger and meals or separate? A
   budget needs a stable set; "groceries" must mean the same thing in March and April.
2. **Rule format.** Substring match on the merchant string, regex, or something richer? Where stored
   - Room, preferences, or a bundled asset? Kevin's real descriptions look like
   `WM SUPERCENTER #4512 KATY TX` and `KROGER #115 CYPRESS TX`, so the store number varies and the
   chain does not.
3. **When the AI guesses.** One-shot per unknown merchant, or batched? On import or on demand? It
   costs his own Gemini key, so `.scratch/ledger-drive-ingestion/issues/06-llm-spend-gate.md`'s
   spend-gate posture probably applies.
4. **A guess is a reported fact (ticket 02).** How is an unconfirmed category shown, and what does
   confirming it do? Does confirming create a rule automatically, so the same merchant never gets
   guessed twice?
5. **Recategorising.** Changing a category changes past months' budgets. Does it rewrite history or
   apply forward only?
6. **Stability.** The failure mode of AI categorisation is that your budget moves when your spending
   did not. State the rule that prevents it.

---

## Resolution (2026-08-07, Kevin - D14-D19)

**14. Fixed list, not freeform.** A budget needs "groceries" to mean the same thing in March and
April. Freeform drifts within a week and the budget quietly stops meaning anything.
`CarTask.category` is freeform today - that is the precedent deliberately NOT followed here.

**15. Food categories are shared between ledger and meals; everything else is separate.** This is
what makes the deferred grocery-vs-meals cross-check (ticket 09) possible later without a migration.

**16. Rules are substring matches on the UPPERCASED description**, stored in Room, editable.
Verified against Kevin's real rows: `KROGER #115 CYPRESS TX` and `KROGER #122 KATY TX` differ only in
the store number, so `KROGER` matches both. The chain is stable; the store is not.

**17. The AI guesses only for merchants with no rule, batched, behind the existing spend gate**
(`.scratch/ledger-drive-ingestion/issues/06-llm-spend-gate.md`). Never per-import, never silently.

**18. Confirming a guess creates the rule automatically.** This is the whole stability answer: a
merchant is guessed at most once, ever. Without it the same merchant gets a different category on
different runs, and the budget moves when the spending did not.

**19. Recategorising rewrites history.** You are correcting a mistake, not changing your mind - last
month's figure was always wrong and is now right. Accepted cost: a past month's numbers can change
under you.

**A category is a REPORTED fact** (ticket 02), always - whether guessed or hand-picked. It is an
opinion about a transaction, never something the document proved.
