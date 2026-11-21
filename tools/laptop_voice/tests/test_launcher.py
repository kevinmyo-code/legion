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


def test_keep_warm_flag_passes_through_only_when_given_and_is_bounded():
    assert R.keep_warm_args(None) == [], "unset: the service's own default"
    assert R.keep_warm_args(0) == ["--keep-warm-s", "0"], "0 turns it off"
    assert R.keep_warm_args(30) == ["--keep-warm-s", "30"]
    for bad in (-1, 5, 7200):
        with pytest.raises(SystemExit):
            R.keep_warm_args(bad)


def test_tts_codebooks_flag_is_off_by_default_and_bounded():
    assert "LEGION_VOICE_KYUTAI_TTS_NQ" not in R.tuning_env(None, None)
    assert R.tuning_env(None, None, 16) == {"LEGION_VOICE_KYUTAI_TTS_NQ": "16"}
    for bad in (4, 33):
        with pytest.raises(SystemExit):
            R.tuning_env(None, None, bad)
