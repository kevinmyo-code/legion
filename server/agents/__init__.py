"""Alfred's brain on the engine (engine-brain map, ADR 0095, 0096, 0100).

- `runtime/` and `graph/`: the reusable agent package. They import nothing from LEGION
  (`tests/test_agents_boundary.py` walks every import), so a second deployment could reuse them.
- `legion/`: LEGION's side: the tool gateway over `engine_mcp.tools.run_engine_tool`, the guards,
  the specs, the trace store, the read twins and the turn itself.
- `specs/`: one spec per agent (only the generalist in this stage).
- `evals/`: the eval runner, the public invented cases and the cassettes. `evals/private/` is
  git-ignored: real utterances never enter the repo.
"""
