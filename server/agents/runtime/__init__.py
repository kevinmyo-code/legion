"""The reusable agent runtime (ADR 0100): the loop, the model seam, the budget, the events.

Imports the standard library only. Nothing here knows about households, tools or prompts: the
caller hands in a spec, a model client, a tool gateway and the callbacks it wants (cost, trace,
cancel). `tests/test_agents_boundary.py` fails on any LEGION import.
"""
