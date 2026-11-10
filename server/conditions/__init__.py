"""Conditions around a point: rivers and flooding, weather alerts, tropical storms, road
closures and wildfire detections (round 5, Kevin 2026-11-10). Read only, public agency data.

`sources.py` fetches and parses each agency's feed; `report.py` turns them into the sentences
the `check_conditions` engine tool returns (`engine_mcp/tools.py`). The phone reaches the tool
through `ask_engine` and the severe-alert watcher; the web assistant through the registry.
Nothing here is stored beyond a short per-process cache.
"""
