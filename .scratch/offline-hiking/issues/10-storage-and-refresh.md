---
map: offline-hiking
ticket: 10
title: "Decision: storage budget, Wi-Fi only, and refresh cadence for offline areas"
type: decision
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-mapbox-offline-facts]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Decision: storage budget, Wi-Fi only, and refresh cadence for offline areas

## Question

How much of the A25's storage may offline areas take, over which networks do they download, and
how often must they be refreshed?

## Facts (research 01)

- The tile store has no size limit by default; `TileStoreOptions.DISK_QUOTA` sets one in bytes. Over
  quota it refuses new data. The ambient cache is separate (1 GB default).
- 750 tile packs per device. A 10 km x 10 km park is about 7 packs per tileset, so the cap does not
  bind for parks (reasoned).
- No published MB per km². Order of magnitude for one park with Outdoors plus DEM: **tens of MB,
  probably under 150 MB** (reasoned, unmeasured; `estimateTileRegion` gives the real figure
  before a download).
- No automatic refresh. Product Terms 2.8.1 caps on-device caching at 30 days; whether that binds
  offline regions is not stated.
- `removeTileRegion` may not free space at once; setting the quota to zero does.

## Options and recommendation

| Question | Options | Recommendation |
|---|---|---|
| Total budget | 500 MB / 1 GB / 2 GB / no cap | **1 GB** quota on the tile store; the regions screen shows used / free and each area's size. |
| Per download | Any size / warn over N MB | Show the estimate before every download; warn over 300 MB. |
| Network | Wi-Fi only / any | **Wi-Fi only by default**, a "use mobile data" switch per download (`networkRestriction`). |
| Refresh | Manual / prompt / automatic on Wi-Fi + charging | **Prompt**: an area older than 25 days shows "refresh before your next hike" and a one-tap refresh. Automatic refresh only if Kevin wants it (a background download of an area he already chose; still his choice, so it stays inside ticket 04's exception). |
| Expiry | Delete at 30 days / keep and warn | **Keep and warn**; never delete an area automatically, because deleting a map the morning of a hike with no signal is the worse failure. The warning says the terms' 30-day line plainly. |
| Delete | Per area | One tap per area; frees space via the quota trick when needed. |

## Kevin must say

- The budget (1 GB recommended).
- Automatic refresh on Wi-Fi while charging: yes or no.
- Keep-and-warn past 30 days: accept the terms reading (refresh is the safe path; keeping is a
  small, stated risk for a personal app).
