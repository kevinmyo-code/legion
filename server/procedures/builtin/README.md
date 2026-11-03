# Built-in procedures

One YAML file per procedure, named for it (`car_check.yaml` holds `name: car_check`). Loaded
after every `migrate` by `procedures/loader.py`; a changed file becomes a new version. The
shape and the load-time rules are in `procedures/definition.py` (ADR 0070).

Empty until procedures ticket 15 adds `car_check`, `money_check` and `morning_briefing`.
