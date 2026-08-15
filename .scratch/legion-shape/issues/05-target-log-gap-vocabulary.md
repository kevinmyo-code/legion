# What exactly is a target, a log entry, and a gap?

Type: grilling
Status: resolved (2026-08-07, Kevin)
Blocked by: (none - this is the frontier)

## Question

Ticket 03 decided plan-versus-actual is one idea in four coats, sharing vocabulary and rules but not
storage. This ticket writes that vocabulary down precisely enough that four domains can implement it
without drifting.

Decide, using `/domain-modeling`:

1. **What is a target?** Does it have a period (a month, a week, open-ended)? Can two targets for the
   same thing overlap in time? What happens to a target when the period ends - archived, rolled over,
   or nothing? Is a target itself a *reported* fact (ticket 02) or is it outside the tiers entirely,
   being an intention rather than a claim about the world?
2. **What is a log entry?** Every domain already has one (a `LedgerTransaction`, a `ServiceRecord`, a
   future workout set). What is the minimum they must share so the gap can be computed the same way -
   a timestamp, a tier tag, a quantity, a category key?
3. **What is a gap?** Remaining (budget), adherence (workouts), overdue (maintenance) and distance
   from target (macros) are four different-feeling answers. Are they one computation with different
   display, or genuinely different?
4. **What does a gap MEAN when the actuals are reported, not proven?** Ticket 02 says it must be
   labelled. Decide the exact rule: does one reported actual taint the whole gap, or is the gap
   reported in proportion? State it once so four domains cannot diverge.
5. **Where does the vocabulary live in code?** A shared `plan/` package with interfaces, a set of
   naming conventions with no shared code, or a documented convention in CLAUDE.md only. Ticket 03
   rejected a generic engine - say concretely what "shared vocabulary, separate storage" is.
6. **What about domains with no target?** Kevin kept music, places, weather and the garage. They are
   not records and have no plan. Say where they sit rather than leaving them undefined.

Note the failure mode runs both ways: too abstract and it fits no domain, too loose and ticket 03's
whole argument (solve the reported-gap question once) is lost.

---

## Resolution (2026-08-07, Kevin - D1-D8)

**1. A target has a period.** One active target per thing per period. Budgets monthly, workout plans
weekly, maintenance open-ended.

**2. Periods copy forward, nothing is deleted.** Last month's grocery budget becomes this month's
until changed. **Leftovers do not roll over** - an unspent 40 does not raise next month's ceiling.

**3. A target is OUTSIDE both trust tiers.** It is an intention, not a claim about the world, so it
cannot be proven or reported - you cannot be wrong about what you intended. This is why ticket 02's
two tiers stay two and do not become three.

**4. Every log entry shares exactly four things:** `when`, `which tier`, `how much`, `what it is
about`. Nothing else is mandated - each domain adds whatever else it needs.

**5. The gap is ONE computation: target minus actual.** Remaining (budget), adherence (workouts),
overdue (maintenance) and distance-from-target (macros) are four *displays* of that subtraction, in
different units. Not four algorithms.

**6. One reported actual makes the WHOLE gap reported.** No proportions, no "mostly proven". Strict
and simple, deliberately: a proportional rule is a second thing to get wrong in four places, which is
what ticket 03 exists to prevent. Accepted cost: a grocery budget reads "reported" all month because
of one pending card row.

**7. The shared code is a `plan/` package holding ONLY the words** - the gap type and the tier type.
**No storage, no interfaces domains must implement, no base classes.** Ticket 03 killed the generic
engine; this is the residue. If `plan/` ever grows a DAO, this decision has been violated.

**8. Music, weather, garage and places are TOOLS, not records.** They act; they do not keep a record.
No target, no gap, no tier. Kevin kept all four on 2026-08-06 - this is where they live, and it is
why they need no further design work under this map.
