# Built-in procedures

One YAML file per procedure, named for it (`car_check.yaml` holds `name: car_check`). Loaded
after every `migrate` by `procedures/loader.py`; a changed file becomes a new version. The
shape and the load-time rules are in `procedures/definition.py` (ADR 0070).

The first three (procedures tickets 09 and 15): `car_check`, `money_check`,
`morning_briefing`. Each file's header comment says its steps and why they are in that order.
Their `compute` functions are in `procedures/steps.py`; their tests in
`tests/test_procedures_builtins.py`.
