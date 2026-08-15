# How do you address an existing item by voice?

Type: grilling
Status: resolved
Blocked by: 01

## Question

Creating by voice is easy ("add batteries to the camping list"). **Editing by voice is the hard
half**, and Kevin asked for it explicitly: "editable after creation either by hand or voice."

"Tick off the tent", "scratch the third one", "change the dentist to Thursday" all have to resolve to
exactly one row, out loud, without a screen.

### What must be decided

1. **How an item is named.** By its text, fuzzily matched? By position ("the third one")? Both?
   Position is treacherous - it depends on a sort order the user cannot see while driving.
2. **What happens on an ambiguous match.** Two items contain "battery". The existing ledger tools
   set a precedent worth following: `log_pending_transaction` refuses and names the candidates rather
   than picking. Decide whether that precedent holds here, or whether a note is low-stakes enough to
   guess.
3. **What happens on no match.** Silently create it? Refuse? Ask?
4. **Which list is being talked about.** "Add batteries" with no list named - does it go to a
   default list, the most recently touched one, or does Alfred ask?
5. **The tool surface.** How many tools, and their shapes. Existing precedent to follow, not
   reinvent: `add_car_task` / `list_car_tasks` / `complete_car_task` / `remove_car_task` already
   exist and ticket 10 decides their fate.
6. **Destructive edits.** Deleting an item or a whole list by voice. CLAUDE.md's general posture is
   to confirm before something hard to reverse. Decide where the line is - deleting one item is
   probably not worth a confirmation, deleting a list probably is.
7. **Undo.** `undo_last_log` currently covers body logs only (workouts, bodyweight, meals, sleep), by
   a decision taken 2026-08-07. Decide whether notes join it or get their own undo.

### Approach

Consider `/prototype` for the voice grammar itself - writing out fifteen things Kevin would actually
say to the app, and checking the proposed tool surface can serve all of them, is cheaper than
arguing about it in the abstract.

## Answer

### Addressing an item

**By fuzzy text match only. Never by position.** "Scratch the third one" is not supported: it depends
on a sort order you cannot see while driving, and it silently means something different the moment
an item is added above it.

**Ambiguous match: refuse and name the candidates.** Two items containing "battery" gets "Do you mean
the head torch batteries or the car battery?", not a guess. This follows the precedent already set by
`log_pending_transaction` and `set_category`, and it is the right default even here - a note is
low-stakes to *create*, but silently ticking the wrong item is how a packing list lies to you.

**No match: refuse and offer.** "I don't see that on Camping - add it?" Never silently create on an
edit verb; "tick off the tent" creating a ticked tent is a bug that looks like a feature.

### Which list, when you do not name one

**Most recently used** (Kevin, 2026-08-07), tracked as `ItemList.lastUsedAt`, touched by voice and by
hand alike. Stated cost, accepted: after a gap, "recent" is genuinely ambiguous and a wrong guess
files an item where you will not look.

Two mitigations that cost nothing and are required, not optional:
- **Alfred always says which list he used** ("Added batteries to Camping"), so a wrong guess is
  caught in the same breath rather than discovered at the campsite.
- If no list has ever been used, he asks rather than inventing one.

### Destructive edits

- **Deleting an item: no confirmation.** Low stakes, and confirmation on every removal makes the
  common case tedious.
- **Deleting a list: confirm, always.** Losing a whole packing list to a misheard sentence is exactly
  the hard-to-reverse action CLAUDE.md says to confirm.
- Deletes are soft (`deleted` tombstone), inherited from `CarTask`, so both are recoverable in the
  database even though only one is recoverable in conversation.

### Undo

**Notes do NOT join `undo_last_log`.** That tool covers body logs only - workouts, bodyweight, meals,
sleep - by a decision taken 2026-08-07, and the reasoning transfers directly: undoing a note edit and
undoing a meal have different blast radii and different natural scopes. Notes get their own path, and
for most edits that path is simply saying the opposite ("untick the tent").

### Tool surface

Ticket 10 retires four tools, so this must not spend the saving twice over. Keep the set small and
verb-shaped rather than one tool per field: create a list, add an item, tick, untick, remove, read a
list, read the lists, set a time, set a repeat, skip an occurrence. **Anything that would be a tool
per property is a parameter instead.** `LiveToolbox` already carries 60+ tools and every one is
prompt tokens on every live session, on Kevin's own key.

### Approach note for the build

The ticket suggested `/prototype` for the grammar. Still worth doing, but as a *check* rather than a
discovery: write out fifteen things Kevin would really say and confirm the tool set serves all of
them, before any of it is built.
