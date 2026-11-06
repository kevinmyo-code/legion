"""phone-verify ticket 03: `tools/screen_map.py` drift is a hard failure, like `voice_guide.py`.

The real code and copy must agree (that is the check the commit hook runs). Each drift case is then
planted into a COPY of the copy module, never into the app's code, and must be named in the
problems list. Run from the repo root:

    python -m pytest tools/tests/test_screen_map.py -p no:cacheprovider
"""
from __future__ import annotations

import copy as _copy
import sys
import types
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import screen_map as sm  # noqa: E402
import screen_map_copy as real_copy  # noqa: E402


def planted(**changes) -> types.SimpleNamespace:
    ns = types.SimpleNamespace(**{k: _copy.deepcopy(getattr(real_copy, k))
                                  for k in dir(real_copy) if k.isupper()})
    for k, v in changes.items():
        setattr(ns, k, v)
    return ns


def test_the_map_agrees_with_the_code():
    screens, problems = sm.build(real_copy)
    assert problems == []
    assert len(screens) > 40


def test_generated_doc_is_current():
    screens, _ = sm.build(real_copy)
    assert sm.OUT.read_text(encoding="utf-8") == sm.render(screens, real_copy), \
        "docs/screen-map.md is stale: run python tools/screen_map.py"


@pytest.mark.parametrize("sid", [
    "settings/memory", "vault", "money", "money/pantry", "fleet/specs", "settings/updates",
    "matrix/home", "home", "settings/data-privacy",
])
def test_the_screens_kevin_named_are_mapped(sid):
    screens, _ = sm.build(real_copy)
    assert sid in screens


def test_memory_and_vault_reach_paths_go_through_data_and_privacy():
    screens, _ = sm.build(real_copy)
    assert sm.tap_path(screens, "settings/memory")[-2:] == [("text", "Data and privacy"), ("text", "Memory")]
    assert sm.tap_path(screens, "vault")[-1] == ("text", "ID vault")
    assert screens["vault"].deeplink is None          # not exported: taps only
    assert screens["settings/memory"].deeplink == "settings/memory"


def test_a_new_route_with_no_copy_fails_and_names_it():
    routes = dict(real_copy.ROUTES)
    del routes["settings/memory"]
    _, problems = sm.build(planted(ROUTES=routes))
    assert any('"settings/memory"' in p and "no entry" in p for p in problems)


def test_copy_for_a_deleted_route_fails():
    routes = dict(real_copy.ROUTES, **{"settings/ghost": dict(name="Ghost")})
    _, problems = sm.build(planted(ROUTES=routes))
    assert any('"settings/ghost"' in p for p in problems)


def test_a_renamed_selector_fails():
    acts = _copy.deepcopy(real_copy.ACTIVITIES)
    acts[".ui.vault.VaultActivity"]["reach"] = [("text", "ID safe")]
    _, problems = sm.build(planted(ACTIVITIES=acts))
    assert any('"ID safe"' in p for p in problems)


def test_an_unknown_voice_tool_fails():
    routes = _copy.deepcopy(real_copy.ROUTES)
    routes["settings/memory"]["voice"] = ["remember_everything"]
    _, problems = sm.build(planted(ROUTES=routes))
    assert any("remember_everything" in p for p in problems)


def test_a_manifest_activity_with_no_entry_fails():
    ignored = dict(real_copy.IGNORED_ACTIVITIES)
    del ignored[".ui.AssistActivity"]
    _, problems = sm.build(planted(IGNORED_ACTIVITIES=ignored))
    assert any(".ui.AssistActivity" in p for p in problems)


def test_a_matrix_screen_with_no_entry_fails():
    matrix = dict(real_copy.MATRIX)
    del matrix["LEDGER"]
    _, problems = sm.build(planted(MATRIX=matrix))
    assert any("MatrixDest.LEDGER" in p for p in problems)


def test_a_route_with_no_way_in_fails():
    routes = _copy.deepcopy(real_copy.ROUTES)
    for k in ("parent", "reach"):
        routes["bought"].pop(k, None)
    _, problems = sm.build(planted(ROUTES=routes))
    assert any('"bought"' in p and "no way to reach" in p for p in problems)


def test_registration_parsing_ignores_comment_tombstones():
    text = """
        // composable(LegionRoute.NOTES) { NotesScreen() } DELETED
        /* composable(LegionRoute.TODAY) { TodayScreen() } */
        composable(LegionRoute.HOME) { HomeScreen(onOpenFoo = { navController.navigate(LegionRoute.BAR) }) }
        for (r in listOf(LegionRoute.A, LegionRoute.B)) composable(r) { LedgerScreen() }
        val url = "https://example.com" // not a comment start inside the string
    """
    regs = sm.registrations(text)
    assert [r.const for r in regs] == ["HOME", "A", "B"]
    assert regs[0].screen_fn == "HomeScreen"
    assert regs[0].edges == [("onOpenFoo", "BAR")]


def test_route_constants_skip_args_and_resolve_patterns():
    text = 'const val X = "x"\nconst val P_ARG = "id"\nconst val PAT = "x/{$P_ARG}"\n'
    assert sm.route_constants(text) == {"X": "x", "PAT": "x/{id}"}
