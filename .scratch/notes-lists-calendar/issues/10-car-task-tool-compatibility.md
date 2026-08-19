---
map: notes-lists-calendar
ticket: 10
title: "Do the car-task voice tools survive absorption?"
type: grilling
status: resolved
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-entity-model-and-cartask-migration]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Do the car-task voice tools survive absorption?

## Question

Four voice tools exist today and work: `add_car_task`, `list_car_tasks`, `complete_car_task`,
`remove_car_task`. Charting decision 1 dissolves the table underneath them into a general list model.
Decide what happens to the tools.

### What must be decided

1. **Do they survive, get replaced, or get aliased?** Keeping them means Alfred has two ways to add a
   tickable thing and has to choose correctly. Removing them means "add an oil change to the car
   list" has to route through the generic tools and land in the right list.
2. **Where car tasks live afterwards.** One list called "Car"? Three lists matching the old
   `category` values (maintenance, project, wishlist)? Depends on ticket 01's answer on `category`.
3. **Whether the fleet aspect keeps a car-shaped view of them.** `FleetScreen` and the fleet domain
   currently surface car tasks. After absorption they are just items in a list. Decide whether Fleet
   keeps showing them, and how it queries them without reaching across domains awkwardly.
4. **The tool-count budget.** `LiveToolbox` already registers over sixty tools, and this map will add
   more for lists, items, events and reminders. Every tool is prompt tokens on every single live
   session, on Kevin's own key. Decide whether absorption is a chance to *reduce* the count rather
   than grow it.

### Why this is its own ticket

It is the one place where "absorb" has a user-visible cost rather than being a tidy-up. The tools
work today. Kevin uses them. Getting this wrong means a working voice command stops working, which is
worse than the duplication absorption was meant to fix.

## Answer

**Retire all four. The generic list tools replace them** (Kevin, 2026-08-07).

`add_car_task`, `list_car_tasks`, `complete_car_task` and `remove_car_task` go. "Add an oil change"
routes through the generic add-item tool to the **Car** list.

### Why this is safe, and it was checked

**Car tasks appear nowhere in `ui/`.** A grep across the whole UI package returns zero references -
they have always been voice-only. The ticket's own question 3 ("does Fleet keep showing them?")
rested on a false premise: `FleetScreen` never showed them. So retiring the tools breaks nothing on
screen, and the fleet aspect needs no query into the notes domain at all.

### Where they land

One list named **"Car"** (ticket 01). The `maintenance` / `project` / `wishlist` enum is dropped
entirely - see ticket 01 for the accepted cost.

### The tool budget, which is the real point

`LiveToolbox` already registers over sixty tools, and **every one is prompt tokens on every single
live session, on Kevin's own key.** Ticket 05's tool set must therefore be net-neutral or better
against the four retired here. Absorption exists to shrink the surface; if this map ends with more
tools than it started with, absorption did not happen, it just moved.

### The one real risk

A voice command that works today stops working. Mitigation is not a compatibility shim - it is that
the generic tools must handle "add an oil change" *at least* as well as `add_car_task` did, and the
`/prototype` check ticket 05 calls for must include the car phrasings specifically, not just camping
ones.
