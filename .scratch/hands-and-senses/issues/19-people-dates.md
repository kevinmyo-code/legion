# The people in Kevin's life: dates and facts he tells it

Type: grilling
Status: open
Blocked by: -

## Question

A JARVIS knows your mother's birthday. LEGION does not, and there is no place to put it:
`CompanionMemory` is the assistant's own persona memory, `ContactsContract` holds phone numbers and
sometimes a birthday field, and the notes/calendar layer holds events but not people.

This is deliberately the SAFE half of the "know about people" space - facts **Kevin states about
his own circle**, not lookups against strangers. See Out of scope on the map for what stays out.

Decide:

1. **Storage, and whether it is new.** `ContactsContract` already has structured birthday/anniversary
   rows and is on-device, syncing with Kevin's Google account for free - does LEGION read/write
   THAT rather than adding a Room table? (Same reasoning that made `CalendarContract` beat a
   mirror in google-account ticket 02.) If a Room table is needed, what does it hold that contacts
   cannot?
2. **What counts as a fact worth keeping.** Birthdays, anniversaries, allergies, coffee order,
   kids' names, "hates surprises". All falsifiable, all stated by Kevin. Where is the line against
   the memory rule (CLAUDE.md §7: memory stays anchored to external falsifiable facts, no invented
   unfalsifiable history)? A fact Kevin states IS anchored - by Kevin. Confirm that reading is
   right, because it governs how much the assistant may remember about people.
3. **Third-party data, said plainly.** These are facts about people who never consented to being in
   an LLM's context. Does that change anything (no export, excluded from any digest sent upward,
   never volunteered to anyone but Kevin)? Note the Drive backup already carries the whole
   database - so a people table rides to Drive with everything else.
4. **How it surfaces.** Pull ("when is Mom's birthday") is obvious. Proactive ("Mom's birthday is
   Thursday") is a raise - and a WELCOME one, unlike a re-engagement ping. Where does it sit
   against the compulsion ban, and does it belong in [the morning brief](08-morning-brief.md)
   rather than as its own alert path?
5. **Capture.** How does a fact get in - only by Kevin saying "remember that Mom's birthday is the
   14th", or may the assistant offer to save something it heard in conversation? The second is a
   memory-write LEGION has never done before; decide deliberately.
6. **Tool budget.** One `people` tool (read + write with an action parameter), or fold into the
   existing notes tools? Write the description.
