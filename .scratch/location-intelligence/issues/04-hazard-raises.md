---
map: location-intelligence
ticket: 4
title: "Hazard alerts that speak first"
type: build
status: open
status-detail: ""
blockers: ["02"]
blocked-by: ["[[02-area-info-tool]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Hazard alerts that speak first

## What to build

The **Safety** category's first real content. Three raises, thresholds already settled (decision 5):

| Source | Threshold |
|---|---|
| NWS | **Warnings only, at Severe or Extreme.** Never watches or advisories |
| USGS | **M4.5 within 150 miles** |
| NIFC | **within 25 miles** |

**Watches and advisories are excluded deliberately.** They fire constantly, and a channel that cries
wolf trains Kevin to ignore the one warning that matters.

## The rules that are not negotiable

- **Raise through `ProactiveBus.speakIfAllowed` with `ProactiveCategory.SAFETY`.** Do not build a
  second path. Safety is uncapped by the daily budget and exempt from quiet hours - **and still
  inside the master kill switch**, which has no exemptions (proactive-mode settled decision 2).
- **Every raise carries real `facts`** or the gate refuses it. The facts string is the pre-formatted,
  attributed line from [ticket 02](02-area-info-tool.md).
- **One alert speaks ONCE, ever, keyed on the NWS alert id** (decision 13). A watch upgrading to a
  warning is a new id and does speak. Use the raise history that already exists; do not add a second
  dedup store.
- **Checks every 15 minutes, and only when location changed meaningfully** (decision 12).
- Flip `ProactiveCategory.SAFETY.hasContent`... it is already `true`. Confirm the settings row reads
  correctly once real raises exist.

## The risk this ticket carries, knowingly

`.scratch/proactive-mode/issues/07-scheduling-research.md`: Samsung's sleeping-apps layer puts an app
unused for ~3 days into a restricted bucket - **one alarm a day, no network, while the foreground
service keeps running and everything looks fine.** A voice assistant used daily without its UI being
opened is exactly that profile.

Kevin's call (decision 16): note it and build anyway. **So the first real tornado warning is also the
first test of whether delivery works at all.** Written here so nobody later mistakes silence for
"no warnings".

Mitigation worth taking while building: request the battery-optimisation allowlist, and have Kevin
mark LEGION "never sleeping" on the A25. Neither is a fix a stranger cloning the repo would know to
apply.

## Verification

- Suite green on the threshold logic and the once-per-alert-id rule.
- **On the phone:** a real or simulated NWS alert reaching him, and the master switch silencing it.
