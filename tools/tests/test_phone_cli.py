"""phone-verify tickets 01-02: `tools/phone`'s parsing and its refusals, with no phone.

adb is replaced by [FakeAdb], which answers from fixtures under `fixtures/phone/` (shaped like what
the A25 printed on 2026-11-05; no real data in them) and records every command, so a test can assert
that a refused tap sent NOTHING to the phone. Run from the repo root:

    python -m pytest tools/tests/test_phone_cli.py -p no:cacheprovider
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent
FIX = Path(__file__).resolve().parent / "fixtures" / "phone"
sys.path.insert(0, str(TOOLS))

from phone import adb as padb  # noqa: E402
from phone import cli, rails, uitree  # noqa: E402

MAIN = "com.kevin.legion/.ui.MainActivity"
VAULT = "com.kevin.legion/.ui.vault.VaultActivity"


class FakeAdb(padb.Adb):
    def __init__(self, dump: str, focus: str = MAIN, locked: bool = False, awake: bool = True,
                 package: str | None = None):
        super().__init__(serial="FAKE", exe="adb")
        self.dump_xml = (FIX / dump).read_text(encoding="utf-8")
        self.focus = focus
        self.locked = locked
        self.awake = awake
        self.package = package if package is not None else (FIX / "dumpsys_package.txt").read_text()
        self.calls: list[str] = []

    def run(self, args, timeout=30, binary=False, device=True):
        cmd = " ".join(args)
        self.calls.append(cmd)
        if args[:2] == ["exec-out", "cat"]:
            return 0, self.dump_xml
        if args[0] == "shell":
            return 0, self._shell(args[1])
        if args[:2] == ["devices", "-l"]:
            return 0, "List of devices attached\nFAKE device product:a25xsq model:SM_A256U device:a25x\n"
        return 0, ""

    def _shell(self, c: str) -> str:
        if c.startswith("dumpsys activity activities"):
            return (f"  ResumedActivity: ActivityRecord{{1 u0 {self.focus} t1}}\n---\n"
                    f"    isKeyguardShowing={'true' if self.locked else 'false'}\n---\n"
                    f"  mWakefulness={'Awake' if self.awake else 'Asleep'}\n")
        if "uiautomator dump" in c:
            return "UI hierchary dumped to: /sdcard/legion_phone_dump.xml"
        if c.startswith("dumpsys package"):
            return self.package
        if c.startswith("dumpsys battery"):
            return "  AC powered: false\n  USB powered: true\n  level: 52\n"
        return ""

    def taps(self) -> list[str]:
        return [c for c in self.calls if "input tap" in c or "input text" in c]


@pytest.fixture(autouse=True)
def artifacts_in_tmp(tmp_path, monkeypatch):
    """The action log goes to a temp dir in tests, never the real .phone-artifacts/."""
    monkeypatch.setattr(cli, "log_action", lambda *a, **k: None)
    monkeypatch.setattr(cli.time, "sleep", lambda s: None)


def run(argv, fake):
    return cli.main(argv, adb=fake)


# ----------------------------------------------------------------------------------- parsing

def test_parse_dump_flattens_and_keeps_parents():
    nodes = uitree.parse((FIX / "data_privacy.xml").read_text())
    mem = uitree.find(nodes, text="Memory")[0]
    assert not mem.clickable
    row = uitree.tap_target(nodes, mem)
    assert row.clickable and row.bounds == (0, 400, 1080, 560)
    assert row.center == (540, 480)


def test_compact_drops_layout_only_nodes_and_flags_clickables():
    nodes = uitree.parse((FIX / "data_privacy.xml").read_text())
    rows = uitree.compact(nodes)
    assert all(("text" in r or "desc" in r or "tag" in r or "flags" in r) for r in rows)
    back = next(r for r in rows if r.get("desc") == "Back")
    assert back["flags"] == "C"


def test_parse_dump_rejects_a_non_hierarchy():
    with pytest.raises(uitree.DumpError):
        uitree.parse("ERROR: could not get idle state.")


def test_match_exact_contains_prefix_and_tag_forms():
    nodes = uitree.parse((FIX / "data_privacy.xml").read_text())
    assert uitree.find(nodes, text="memory")                       # case-insensitive exact
    assert not uitree.find(nodes, text="Mem")                      # exact, not substring
    assert uitree.find(nodes, text="Mem", contains=True)
    assert uitree.find(nodes, text="What LEGION ^")                # prefix
    assert uitree.find(nodes, tag="purge-row")                     # Compose tag as resource-id
    assert uitree.find(nodes, tag="forget-12")                     # View-style id, prefix stripped


def test_node_at_picks_the_deepest_node():
    nodes = uitree.parse((FIX / "data_privacy.xml").read_text())
    n = uitree.node_at(nodes, 100, 450)
    assert n.text == "Memory"


def test_parse_version_and_build():
    v = padb.parse_version((FIX / "dumpsys_package.txt").read_text())
    assert v.version_code == 263092342
    assert v.version_name == "2026-11-05-c353bcf"
    assert v.build == "fast"
    dbg = padb.parse_version("versionCode=5 x\n    flags=[ DEBUGGABLE HAS_CODE ]\n")
    assert dbg.build == "debug"
    assert padb.parse_version("Unable to find package") is None


def test_parse_devices_focus_battery_screen():
    devs = padb.parse_devices("List of devices attached\nadb-R5-x._adb-tls-connect._tcp device product:a model:SM_A256U\n")
    assert devs[0].model == "SM_A256U" and devs[0].state == "device"
    assert padb.parse_focus("  topResumedActivity=ActivityRecord{12 u0 com.kevin.legion/.ui.MainActivity t9}") == MAIN
    assert padb.parse_focus("  ResumedActivity: ActivityRecord{12 u0 " + VAULT + " t9}") == VAULT
    bat = padb.parse_battery("  AC powered: false\n  USB powered: false\n  level: 52\n"
                             "11-05 Sending ACTION_BATTERY_CHANGED: level:57\n")
    assert bat == {"level": 52, "charging": False}
    assert padb.parse_screen("isKeyguardShowing=true", "mWakefulness=Awake") == {"awake": True, "locked": True}


def test_logcat_filter_keeps_legion_pid_audit_tags_and_legion_crash_only():
    text = (FIX / "logcat.txt").read_text()
    lines = padb.filter_logcat(text, {"4321"})
    joined = "\n".join(lines)
    assert "session_start" in joined and "Service Started" in joined
    assert "LIVE_LATENCY" in joined                   # audit tag from any pid
    assert "noise from another app" not in joined
    assert "FATAL EXCEPTION" in joined and "boom" in joined   # LEGION's crash, header line included
    assert "com.other.app" not in joined
    assert padb.filter_logcat(text, {"4321"}, grep="latency") == [l for l in lines if "LATENCY" in l]


def test_screencap_raw_header_and_blank_fraction():
    w, h = 4, 2
    black = (w).to_bytes(4, "little") + (h).to_bytes(4, "little") + (1).to_bytes(4, "little") + bytes(4)
    black += bytes([0, 0, 0, 255]) * (w * h)
    W, H, px = padb.parse_screencap_raw(black)
    assert (W, H) == (4, 2)
    assert padb.blank_fraction(W, H, px, step=1) == 1.0
    white = black[:16] + bytes([255, 255, 255, 255]) * (w * h)
    assert padb.blank_fraction(*padb.parse_screencap_raw(white), step=1) == 0.0


def test_input_text_escaping():
    assert padb.input_text_arg("what's my plate") == "what\\'s%smy%splate"
    assert padb.input_text_arg("a&b;c") == "a\\&b\\;c"


# ----------------------------------------------------------------------------------- refusals

def test_tap_by_text_taps_the_row_centre():
    fake = FakeAdb("data_privacy.xml")
    assert run(["tap", "--text", "Memory"], fake) == 0
    assert fake.taps() == ["shell input tap 540 480"]


@pytest.mark.parametrize("argv", [
    ["tap", "--text", "Delete the local ledger"],
    ["tap", "--tag", "forget-12"],
    ["tap", "--xy", "300", "770"],          # coordinates over the Delete row are not a way around it
])
def test_world_changing_taps_are_refused_without_kevin(argv):
    fake = FakeAdb("data_privacy.xml")
    assert run(argv, fake) == 3
    assert fake.taps() == []


def test_confirmed_by_kevin_lifts_the_confirmable_refusal():
    fake = FakeAdb("data_privacy.xml")
    assert run(["tap", "--text", "Delete the local ledger", "--confirmed-by-kevin"], fake) == 0
    assert len(fake.taps()) == 1


@pytest.mark.parametrize("argv", [
    ["tap", "--text", "Show"],
    ["tap", "--text", "Edit"],
    ["tap", "--xy", "180", "630"],          # Show, by coordinates
    ["tap", "--text", "Show", "--confirmed-by-kevin"],   # no flag lifts a vault reveal
    ["type", "123"],
    ["type", "123", "--confirmed-by-kevin"],
    ["say", "what is my passport number"],
])
def test_vault_allows_nothing_but_back(argv):
    fake = FakeAdb("vault.xml", focus=VAULT)
    assert run(argv, fake) == 3
    assert fake.taps() == []


def test_vault_back_is_allowed():
    fake = FakeAdb("vault.xml", focus=VAULT)
    assert run(["tap", "--desc", "Back"], fake) == 0
    assert len(fake.taps()) == 1


def test_reveal_tags_are_refused_even_outside_the_vault():
    with pytest.raises(rails.Refusal) as e:
        rails.check_tap(["vault-show"], MAIN, confirmed=True)
    assert e.value.absolute


def test_vault_dump_is_redacted(capsys):
    fake = FakeAdb("vault.xml", focus=VAULT)
    assert run(["dump"], fake) == 0
    out = capsys.readouterr().out
    assert "A1234567B" not in out and "[redacted]" in out
    assert "Fixture label" in out          # labels stay readable


def test_another_app_in_front_is_refused_and_system_settings_absolutely():
    fake = FakeAdb("data_privacy.xml", focus="com.android.vending/.AssetBrowserActivity")
    assert run(["tap", "--text", "Memory"], fake) == 3
    with pytest.raises(rails.Refusal) as e:
        rails.check_tap(["Wi-Fi"], "com.android.settings/.Settings", confirmed=True)
    assert e.value.absolute
    with pytest.raises(rails.Refusal):
        rails.check_tap(["Phone settings"], MAIN, confirmed=True)


def test_locked_or_asleep_phone_is_not_driven():
    assert run(["tap", "--text", "Memory"], FakeAdb("data_privacy.xml", locked=True)) == 1
    assert run(["tap", "--text", "Memory"], FakeAdb("data_privacy.xml", awake=False)) == 1


@pytest.mark.parametrize("words,hit", [
    (["Send"], "send"), (["Pay now"], "pay"), (["Buy"], "buy"), (["Forget"], "forget"),
    (["Install"], "install"), (["Ship it"], "ship"), (["confirm-forget"], "forget"),
    (["Shared with the household"], None), (["Calls"], None), (["Memory"], None), (["Check now"], None),
])
def test_danger_words_are_whole_words(words, hit):
    assert rails.danger_word(words) == hit


def test_no_command_can_uninstall_clear_or_shell_through():
    """The rail for uninstall / pm clear / wipes is that no command exists for them."""
    p = cli.build_parser()
    names = set(p._subparsers._group_actions[0].choices)
    assert not names & {"shell", "uninstall", "clear", "install", "settings", "raw"}


# --------------------------------------------------------------------------------- artifacts

def test_artifacts_never_land_in_the_tracked_repo():
    with pytest.raises(rails.Refusal):
        rails.artifact_path(REPO / "docs" / "shot.png", "x.png")
    with pytest.raises(rails.Refusal):
        rails.artifact_path(REPO / "shot.png", "x.png")
    p = rails.artifact_path("sub/shot.png", "x.png")       # relative: under .phone-artifacts/
    assert p == (REPO / ".phone-artifacts" / "sub" / "shot.png").resolve()


def test_artifacts_outside_the_repo_are_allowed(tmp_path):
    assert rails.artifact_path(tmp_path / "shot.png", "x.png") == (tmp_path / "shot.png").resolve()


def test_phone_artifacts_are_gitignored():
    lines = [l.strip() for l in (REPO / ".gitignore").read_text(encoding="utf-8").splitlines()]
    assert ".phone-artifacts/" in lines
    try:
        r = subprocess.run(["git", "-C", str(REPO), "check-ignore", "-q", ".phone-artifacts/shot.png"],
                           capture_output=True, timeout=20)
    except (OSError, subprocess.TimeoutExpired):
        pytest.skip("git not available")
    assert r.returncode == 0, ".phone-artifacts/ is not ignored by git"


# ------------------------------------------------------------------------------------ version

def test_version_min_reports_older_build(capsys):
    fake = FakeAdb("data_privacy.xml")
    assert run(["version", "--min", "263092342"], fake) == 0
    assert run(["version", "--min", "263092343"], fake) == 2
    assert "OLDER" in capsys.readouterr().out


# --------------------------------------------------------------------------------------- open

class OpeningFake(FakeAdb):
    """Data and privacy on screen; a tap brings the vault to the front, as the real one does."""

    def _shell(self, c):
        if c.startswith("input tap"):
            self.focus = VAULT
            self.dump_xml = (FIX / "vault.xml").read_text(encoding="utf-8")
        return super()._shell(c)


def test_open_a_route_is_one_deep_link_and_no_taps():
    fake = FakeAdb("data_privacy.xml")
    assert run(["open", "settings/data-privacy"], fake) == 0
    assert any("am start -n com.kevin.legion/.ui.MainActivity --es route 'settings/data-privacy'" in c
               for c in fake.calls)
    assert fake.taps() == []


def test_open_vault_links_to_data_and_privacy_then_taps_id_vault_and_checks_focus():
    fake = OpeningFake("data_privacy.xml")
    assert run(["open", "vault"], fake) == 0
    starts = [c for c in fake.calls if "am start" in c]
    assert starts and "route 'settings/data-privacy'" in starts[0]
    assert fake.taps() == ["shell input tap 540 640"]       # the ID vault row's centre
    assert fake.focus == VAULT


def test_open_unknown_screen_fails_without_touching_the_phone():
    fake = FakeAdb("data_privacy.xml")
    assert run(["open", "settings/nope"], fake) == 1
    assert not any("am start" in c for c in fake.calls)


def test_wakefulness_reads_the_word_not_the_number():
    power = "  mWakefulness=1\n  mWakefulness=Awake\n"
    assert padb.parse_screen("", power)["awake"] is True
    assert padb.parse_screen("", "mWakefulness=0\nmWakefulness=Asleep\n")["awake"] is False


def test_vault_back_with_its_icon_desc_twice_is_still_back():
    rails.check_tap(["Back", "Back"], VAULT, confirmed=False)
    with pytest.raises(rails.Refusal):
        rails.check_tap(["Back", "Show"], VAULT, confirmed=False)
    with pytest.raises(rails.Refusal):
        rails.check_tap([], VAULT, confirmed=False)


def test_open_reports_when_the_header_never_shows(capsys):
    fake = FakeAdb("data_privacy.xml")          # Memory's header text is not in this dump
    assert run(["open", "settings/memory", "--timeout", "0"], fake) == 1
    assert "NOT seen" in capsys.readouterr().out
