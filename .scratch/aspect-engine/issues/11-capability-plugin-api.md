---
map: aspect-engine
ticket: "11"
title: "The capability plugin API, and the gate's new home"
type: grilling
status: open
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-engine-schema]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The capability plugin API, and the gate's new home

## Question

Charter decision 2: native code attaches to aspects as plugins. Specify the API:

1. **What a plugin registers.** Bespoke voice tools (verbs), native widgets, native screens
   (ingestion UIs, overrides per ticket 10), background workers (OBD session, watched folders),
   and writes into the record store through the same single door as everything else.
2. **The reconciliation gate's new home.** The gate becomes engine infrastructure a plugin
   ingestion path MUST pass through: reconcile-against-stated-anchor, quarantine, provenance
   tagging, the rule-7 provisional path. Design it so a new ingestion plugin cannot skip it by
   accident - the gate is the door, not a convention.
3. **Binding.** A plugin declares the record types it needs (fleet plugin needs cars/services/
   fuel with specific fields). Are those record types locked against user edits, partially
   editable (add fields yes, delete plugin-required fields no), or fully editable with the plugin
   degrading gracefully? Recommend partially editable with declared required fields.
4. **Lifecycle.** Delete an aspect a plugin is attached to: detach and disable the plugin, keep
   the data? Reinstallable defaults (recreate the fleet aspect fresh)?
5. **Inventory.** Enumerate the actual plugins v1 ships: OBD/fleet, ledger parsers + gate, pantry
   vision, music, comms, weather, location/places, Google Calendar import (feeds ticket 05),
   Gmail read-through. For each: tools, widgets, screens, workers it registers.
