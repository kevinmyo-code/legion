# Conditions fixtures

Recorded 2026-11-10 from the public endpoints named in `server/conditions/sources.py`, trimmed to a
few rows. No live call is made in tests. Public agency data only; no household location.

- `nws_alerts.json`: three recorded NWS alerts; the second and third are edited copies (event and
  severity changed) so the severe filter has a Minor alert and a Moderate hurricane watch to judge.
- `usgs_iv.json`, `nwps_gauges.json`, `nwps_gauge_BAPT2.json`, `nhc_storms.json`: as recorded, trimmed.
- `firms_viirs.csv`: FIRMS area CSV columns as documented for VIIRS; values made up (no MAP_KEY to
  record with).
- `drivetexas_standin.geojson`: a stand-in; DriveTexas needs a key. Re-record once one exists.
