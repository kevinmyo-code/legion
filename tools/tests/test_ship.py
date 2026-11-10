# ruff: noqa: E501  (operator-facing messages read better unbroken)
"""deploy/ship.py: the pure parts, and flags.yaml against what the server actually reads.

No gcloud, no git, no network. Run from the repo root:

    python -m pytest tools/tests/test_ship.py -p no:cacheprovider
"""

from __future__ import annotations

import datetime as dt
import importlib.util
import re
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
SERVER = REPO / "server"
CLOUD = REPO / "deploy" / "cloudrun"

spec = importlib.util.spec_from_file_location("ship_under_test", REPO / "deploy" / "ship.py")
ship = importlib.util.module_from_spec(spec)
sys.modules["ship_under_test"] = ship
spec.loader.exec_module(ship)

sys.path.insert(0, str(CLOUD))
import _common  # noqa: E402

FLAGS = ship.load_flags((CLOUD / "flags.yaml").read_text(encoding="utf-8"))


def _server_reads() -> set[str]:
    """Every environment variable name the server reads: os.environ.get("X") / os.environ["X"] /
    os.getenv("X") anywhere under server/ (tests excluded), a NAME_ENV = "X" style constant handed
    to os.environ elsewhere, and ${X} in entrypoint.sh."""
    names: set[str] = set()
    pat = re.compile(r"""(?:environ(?:\.get)?\(|environ\[|getenv\()\s*["']([A-Z][A-Z0-9_]+)["']""")
    const = re.compile(r"""^[A-Z_]*ENV[A-Z_]*\s*=\s*["']([A-Z][A-Z0-9_]+)["']""", re.M)
    for path in SERVER.rglob("*.py"):
        if "tests" in path.parts or ".venv" in path.parts or "node_modules" in path.parts:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        names.update(pat.findall(text))
        names.update(const.findall(text))
    names.update(
        re.findall(r"\$\{([A-Z][A-Z0-9_]+)", (SERVER / "entrypoint.sh").read_text(encoding="utf-8"))
    )
    return names


SERVER_READS = _server_reads()


@pytest.mark.parametrize("name", sorted(FLAGS))
def test_every_flag_is_read_by_the_server(name):
    spec_ = FLAGS[name]
    if name in SERVER_READS:
        return
    # A pure deploy switch (LEGION_FIRMS) mounts a secret; the server reads the SECRET.
    assert spec_ and spec_.get("secret") in SERVER_READS, (
        f"flags.yaml sets {name} but nothing under server/ reads it (settings.py, the owning module "
        f"or entrypoint.sh): a flag that changes nothing"
    )


@pytest.mark.parametrize("name", sorted(FLAGS))
def test_every_flag_reaches_cloud_run(name):
    passed = set(_common.OPTIONAL_PLAIN_ENV_VARS) | set(_common.PLAIN_ENV_VARS)
    assert name in passed, f"{name} is in flags.yaml but _common.py never passes it to Cloud Run"


def test_secret_flags_mount_their_secret():
    for name, spec_ in FLAGS.items():
        if spec_ and spec_.get("secret"):
            assert _common.OPTIONAL_SECRET_ENV_VARS.get(spec_["secret"]) == name


def test_every_passed_switch_is_read_by_the_server():
    """The other direction: each optional name _common.py passes to Cloud Run is read by the
    server, or is a deploy switch that mounts a secret the server reads. A name nothing reads is a
    knob that does nothing."""
    switches = set(_common.OPTIONAL_SECRET_ENV_VARS.values())
    for name in _common.OPTIONAL_PLAIN_ENV_VARS:
        assert name in SERVER_READS or name in switches, f"{name} is passed but nothing reads it"


def test_flags_yaml_rejects_unquoted_booleans():
    with pytest.raises(ship.Stop):
        ship.load_flags("flags:\n  LEGION_X: on\n")


def test_decide_flags_merges_and_refuses_clashes():
    flags = ship.load_flags(
        'flags:\n  A: "on"\n  B: null\n  C: {value: "x", secret: S, readme: R}\n'
    )
    plan = ship.decide_flags({"SITE": "1"}, flags, lambda s: True)
    assert plan.values == {"SITE": "1", "A": "on", "C": "x"}
    assert plan.off == ["B"] and not plan.owed
    plan = ship.decide_flags({}, flags, lambda s: False)
    assert "C" not in plan.values and plan.owed and "S" in plan.owed[0]
    with pytest.raises(ship.Stop):
        ship.decide_flags({"A": "off"}, flags, lambda s: True)
    with pytest.raises(ship.Stop):
        ship.decide_flags({"B": "on"}, flags, lambda s: True)


def test_env_drop():
    assert ship.env_drop({"A", "B"}, {"A"}) == {"B"}
    assert ship.env_drop({"A"}, {"A", "C"}) == set()


def test_refused_window():
    assert ship.in_refused_window(30) and ship.in_refused_window(45) and ship.in_refused_window(40)
    assert not ship.in_refused_window(29) and not ship.in_refused_window(46)


def test_quiet_window_reads_the_real_crontab():
    rows = ship.parse_crontab_text((REPO / "deploy" / "crontab").read_text(encoding="utf-8"))
    tasks = {t for _, _, t in rows}
    assert "push_dispatch" in tasks and "evaluate_watches" in tasks
    # push_dispatch is */5: at hh:04:10 it fires at hh:05.
    due = ship.next_fires(rows, dt.datetime(2026, 11, 10, 9, 4, 10), 150)
    assert any(t == "push_dispatch" for _, t in due)
    # hh:00:20 -> next is push_dispatch at :05, 280 s away: quiet for 150 s.
    assert ship.next_fires(rows, dt.datetime(2026, 11, 10, 9, 0, 20), 150) == []
    # 03:00 backup_nightly only at hour 3.
    due = ship.next_fires(rows, dt.datetime(2026, 11, 10, 2, 59, 30), 60)
    assert {"backup_nightly", "push_dispatch", "heartbeat"} <= {t for _, t in due}


def test_cron_field():
    assert ship._cron_field("*/15", 0, 59) == {0, 15, 30, 45}
    assert ship._cron_field("7,22", 0, 59) == {7, 22}
    assert ship._cron_field("1-3", 0, 23) == {1, 2, 3}


def test_unapplied_reads_showmigrations_plan():
    log = "[X]  watches.0001_initial\n[ ]  open_loops.0002_x\n[X]  core.0001_create_django_schema\n"
    assert ship.unapplied(["watches.0001_initial", "open_loops.0002_x"], log) == [
        "open_loops.0002_x"
    ]


def test_migrations_in_tree_finds_django_migrations():
    found = ship.migrations_in_tree(SERVER)
    assert "core.0001_create_django_schema" in found
    assert all(re.fullmatch(r"\w+\.\d{4}_\w+", m) for m in found)
