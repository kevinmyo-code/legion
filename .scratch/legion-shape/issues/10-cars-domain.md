# What is the cars domain, under the new shape?

Type: grilling
Status: resolved (2026-08-07, Kevin)
Blocked by: 05

## Question

The only domain with substantial existing code (31 source files) and the only one already wired for
voice writing. Kevin: *"i just want to read obd data and log maintenance and keep track of upcoming
stuff."* That is narrower than what is built.

1. **The target is a maintenance schedule.** `MaintenanceController` and `MaintenanceItem` exist.
   Does the existing schedule already satisfy ticket 05's "target", or does it need reshaping?
2. **"Upcoming stuff" is the gap.** What is overdue, what is due soon, by mileage or by date. Decide
   which of those the gap is expressed in when a car has both.
3. **Reading OBD is not plan-versus-actual at all.** It is a live sensor read, not a record with a
   target. Say where it sits - ticket 05 question 6 covers domains with no target and this is the
   clearest case.
4. **What of the existing fleet code is now out of scope?** Recaps, yearly wrapped, drive logs, build
   sheets, telemetry charts. All built, none named by Kevin. Keep, or park? Note nothing was cut on
   2026-08-06, so parking is not deleting.
5. **Odometer.** Every mileage-based schedule needs a current reading, and `set_odometer` exists but
   depends on Kevin saying so - a reported fact feeding a target. Ticket 02's rule applies directly.

---

## Resolution (2026-08-07, Kevin - D29-D32)

**29. `MaintenanceItem` already IS a target, unchanged.** `intervalMiles`/`intervalMonths` is the
plan; `lastDoneMileage`/`lastDoneDate` is the actual. Cars had plan-versus-actual built before this
map existed and nobody had named it. **No reshaping needed** - the cheapest domain in the set.

**30. The gap is whichever comes first, miles or date.** That is what a real service schedule means,
and both columns already exist.

**31. OBD is a LOG WITH NO TARGET.** That is allowed and worth saying: ticket 05 D8 covers tools that
keep no record, but `OdbSample` genuinely records - it just has nothing to aim at. The gap machinery
does not apply to it; it is history, not adherence.

**32. Recaps, yearly wrapped, drive logs, build sheets and telemetry charts are PARKED.** Built,
working, not deleted, and not receiving attention. Kevin cut nothing on 2026-08-06, so parking is the
only lever ordering has.
