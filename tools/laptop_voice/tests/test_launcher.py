"""The launcher's tuning flags become the child's environment (numbers only), and bad values stop it.
Nothing is started: only run.tuning_env is called."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from laptop_voice import run as R  # noqa: E402


def test_no_flags_no_tuning():
    assert R.tuning_env(None, None) == {}


def test_flags_become_numbers_in_the_environment():
    env = R.tuning_env(0.75, 0.4)
    assert env == {"LEGION_VOICE_KYUTAI_PAUSE": "0.75", "LEGION_VOICE_KYUTAI_MAYBE": "0.4"}


@pytest.mark.parametrize("eot,maybe", [(0.2, None), (None, 0.6), (None, 0.05), (0.5, 0.5)])
def test_out_of_range_values_stop_the_launcher(eot, maybe):
    with pytest.raises(SystemExit):
        R.tuning_env(eot, maybe)
