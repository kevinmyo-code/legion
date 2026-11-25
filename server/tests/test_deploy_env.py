"""`deploy/cloudrun/_common.py`'s env-list builder, from the server's side.

Both `deploy.py` and `deploy_job.py` write Cloud Run's environment with
`--env-vars-file`, which REPLACES the whole set, and the file holds exactly
what `plain_env_from()` returns. So a name the server reads but that module
does not list is a line in `.env` that silently never reaches Cloud Run - the
shape that left `LEGION_BOOTSTRAP_HOUSEHOLD_ID` off the service while the job
only kept it by a ship script's hand-written read-back.

No network, no gcloud, no secret and no value out of a real `.env`: every
fixture here is a made-up uuid.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path

from household.tenancy import BOOTSTRAP_ID_ENV

MADE_UP_ID = "00000000-0000-4000-8000-0000000000aa"


def _cloudrun_common():
    path = Path(__file__).resolve().parents[2] / "deploy" / "cloudrun" / "_common.py"
    spec = importlib.util.spec_from_file_location("cloudrun_common_env", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_the_bootstrap_household_id_reaches_cloud_run_when_env_sets_it():
    """Kevin, 2026-11-24: the id is set permanently on the service AND the job.
    Listed, so one `.env` line reaches both deploys instead of the job alone."""
    module = _cloudrun_common()

    assert BOOTSTRAP_ID_ENV in module.OPTIONAL_PLAIN_ENV_VARS
    assert module.plain_env_from({BOOTSTRAP_ID_ENV: MADE_UP_ID})[BOOTSTRAP_ID_ENV] == MADE_UP_ID


def test_the_bootstrap_household_id_is_omitted_when_env_does_not_set_it():
    """Optional, not required: a clone with no line ships no name at all, so
    `resolve_default_household()` falls to its own rule rather than reading a
    blank id. A required name would have shipped an empty string."""
    module = _cloudrun_common()

    assert BOOTSTRAP_ID_ENV not in module.plain_env_from({})
    assert BOOTSTRAP_ID_ENV not in module.plain_env_from({BOOTSTRAP_ID_ENV: ""})
    assert BOOTSTRAP_ID_ENV not in module.PLAIN_ENV_VARS
    assert BOOTSTRAP_ID_ENV not in module.DEFAULTED_PLAIN_ENV_VARS


def test_the_bootstrap_household_id_is_not_a_secret_reference():
    """It names a row, it is not a credential: it rides the plain env-vars file,
    never `--set-secrets`, which would need a Secret Manager secret to exist."""
    module = _cloudrun_common()

    assert BOOTSTRAP_ID_ENV not in module.SECRET_ENV_VARS
    assert BOOTSTRAP_ID_ENV not in module.OPTIONAL_SECRET_ENV_VARS
