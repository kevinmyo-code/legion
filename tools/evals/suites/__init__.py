"""The five v1 eval suites (.scratch/ai-craft/issues/01-eval-harness.md). Each module
exposes NAME, DESCRIPTION, cases(), run_case(client, model, case, run_index) and
score(case, result) - see suites/base.py for the exact shapes. harness.py imports this
list to discover them; add a new suite by writing a module here and appending it below."""
from . import clerk_crud, grounding, outcome_honesty, quarantine_speech, tone_judge

ALL_SUITES = [clerk_crud, outcome_honesty, quarantine_speech, grounding, tone_judge]

SUITES_BY_NAME = {s.NAME: s for s in ALL_SUITES}
