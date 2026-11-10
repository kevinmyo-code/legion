"""Worker workflows ticket 02: the workflow.json format, lint, catalog hash and the matcher.

Invented fixtures only (`test_fixtures/workflows/`). Run from the repo root:

    python -m unittest tools/claude_session/test_workflows.py -v
"""
from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import workflows as WF  # noqa: E402

FIXTURES = HERE / "test_fixtures" / "workflows"
SKILLS = FIXTURES / "skills"


def _sidecar(**over) -> dict:
    base = {"format": 1, "name": "x-flow", "says": "Does a thing.", "aliases": [], "params": [],
            "asks": [], "outputs": ["summary"], "deliver": None, "scope": ["full"], "every": None}
    base.update(over)
    return base


class ParseTest(unittest.TestCase):
    def test_the_two_invented_examples_load(self):
        entries, problems = WF.load_catalog(SKILLS)
        names = [e["name"] for e in entries]
        self.assertEqual(names, ["rates-sheet", "tidy-downloads"])
        rates, tidy = entries
        self.assertEqual(rates["aliases"], ["rates sheet", "make the rates sheet", "exchange rates"])
        self.assertEqual(rates["defaults"], {"currencies": "the usual three"})
        self.assertEqual(rates["outputs"], ["summary", "report"])
        self.assertIsNone(rates["every"])
        self.assertEqual(tidy["asks"], [{"at": "move", "says": "Move the files into dated folders?"}])
        self.assertEqual(tidy["every"], {"hours": 168, "idle_minutes": 10, "force_after_hours": 216,
                                         "retry_after_hours": 6})
        self.assertTrue(Path(tidy["dir"]).is_dir())

    def test_a_skill_without_a_sidecar_is_not_a_workflow_and_not_a_problem(self):
        _entries, problems = WF.load_catalog(SKILLS)
        self.assertNotIn("plain-skill", [f for f, _ in problems])

    def test_each_broken_fixture_is_named_with_its_reason(self):
        problems = dict(WF.lint(SKILLS))
        self.assertIn("kebab-case", problems["bad-name"])
        self.assertIn("drive path", problems["path-in-says"])
        self.assertIn("ship.py", problems["ship-no-ask"])
        self.assertIn("tidy my downloads", problems["untidy-downloads"])
        self.assertEqual(len(problems), 4)

    def test_cli_lint_exits_one_on_problems_and_zero_when_clean(self):
        self.assertEqual(WF.main(["--lint", str(SKILLS)]), 1)
        with tempfile.TemporaryDirectory() as tmp:
            shutil.copytree(SKILLS / "rates-sheet", Path(tmp) / "rates-sheet")
            self.assertEqual(WF.main(["--lint", tmp]), 0)
            self.assertEqual(WF.main(["--lint", str(Path(tmp) / "missing")]), 0)

    def test_private_text_is_refused_in_every_field_the_engine_sees(self):
        bad = [
            _sidecar(says="Mails a report to someone@example.com."),
            _sidecar(aliases=["open D:/stuff"]),
            _sidecar(params=[{"name": "where", "says": "folder like %USERPROFILE%"}]),
            _sidecar(params=[{"name": "budget", "says": "cap", "default": "$40 a week"}]),
            _sidecar(asks=[{"at": "pay", "says": "Pay 120 dollars?"}]),
            _sidecar(says="Reads ~/notes"),
        ]
        for raw in bad:
            with self.subTest(raw=raw), self.assertRaises(WF.Problem):
                WF.parse(raw, "x-flow")

    def test_shape_rules(self):
        cases = {
            "unknown key": _sidecar(sneaky=True),
            "format": _sidecar(format=2),
            "folder": _sidecar(name="y-flow"),
            "says too long": _sidecar(says="x" * 121),
            "too many aliases": _sidecar(aliases=[f"alias {i}" for i in range(9)]),
            "bad output": _sidecar(outputs=["summary", "video"]),
            "deliver": _sidecar(deliver="phone"),
            "empty scope": _sidecar(scope=[]),
            "listed page": _sidecar(scope=["listed"], outputs=["page"]),
            "every too long": _sidecar(every={"hours": 721}),
            "force before due": _sidecar(every={"hours": 24, "force_after_hours": 12}),
            "every key": _sidecar(every={"hours": 24, "minutes": 3}),
            "ask shape": _sidecar(asks=[{"at": "x"}]),
            "param name": _sidecar(params=[{"name": "Bad Name", "says": "x"}]),
        }
        for why, raw in cases.items():
            with self.subTest(why), self.assertRaises(WF.Problem):
                WF.parse(raw, "x-flow")

    def test_send_mail_needs_an_ask_unless_it_is_the_standing_email_self(self):
        with self.assertRaises(WF.Problem):
            WF.parse(_sidecar(), "x-flow", "Then run send_mail.py to the list.")
        WF.parse(_sidecar(deliver="email_self"), "x-flow", "Then run send_mail.py --to kevin.")
        WF.parse(_sidecar(asks=[{"at": "send", "says": "Send it?"}]), "x-flow", "send_mail.py")

    def test_every_fills_its_defaults(self):
        entry = WF.parse(_sidecar(every={"hours": 24}), "x-flow")
        self.assertEqual(entry["every"], {"hours": 24, "idle_minutes": 10, "force_after_hours": 36,
                                          "retry_after_hours": 6})

    def test_summary_is_always_an_output(self):
        self.assertEqual(WF.parse(_sidecar(outputs=["report"]), "x-flow")["outputs"],
                         ["summary", "report"])


class EngineCopyTest(unittest.TestCase):
    def test_the_engine_copy_has_no_dir_no_defaults_and_no_skill_text(self):
        entries, _ = WF.load_catalog(SKILLS)
        copy = WF.catalog_for_engine(entries)
        blob = json.dumps(copy)
        self.assertNotIn("dir", copy[0])
        self.assertNotIn("defaults", copy[0])
        self.assertNotIn("the usual three", blob)  # a default is local text
        self.assertNotIn(str(SKILLS), blob)
        self.assertNotIn("Look up today's rates", blob)  # SKILL.md body
        self.assertEqual(copy[0]["params"], [{"name": "currencies",
                                              "says": "which currencies, like 'euro and yen'",
                                              "required": False}])

    def test_hash_is_stable_over_folder_and_key_order_and_moves_with_content(self):
        entries, _ = WF.load_catalog(SKILLS)
        h = WF.catalog_hash(entries)
        self.assertEqual(h, WF.catalog_hash(list(reversed(entries))))
        self.assertEqual(h, WF.catalog_hash([dict(reversed(list(e.items()))) for e in entries]))
        changed = [dict(entries[0], says="Builds the sheet."), entries[1]]
        self.assertNotEqual(h, WF.catalog_hash(changed))
        self.assertEqual(len(h), 64)
        self.assertNotEqual(WF.catalog_hash([]), h)


class MatchTest(unittest.TestCase):
    """The shared phrase table: the engine's port runs the same file."""

    def test_the_shared_phrase_table(self):
        table = json.loads((FIXTURES / "match_cases.json").read_text(encoding="utf-8"))
        for case in table["cases"]:
            with self.subTest(phrase=case["phrase"]):
                got = WF.match(case["phrase"], table["catalog"], table["machines"])
                if case["expect"] is None:
                    self.assertIsNone(got)
                else:
                    self.assertIsNotNone(got)
                    self.assertEqual(got[0]["name"], case["expect"])
                    self.assertEqual(got[1], case["params"])

    def test_longest_alias_wins(self):
        cat = [{"name": "logs", "aliases": ["logs"]},
               {"name": "deep-logs", "aliases": ["audit my logs deeply"]}]
        self.assertEqual(WF.match("audit my logs deeply now", cat)[0]["name"], "deep-logs")
        self.assertEqual(WF.match("check the logs", cat)[0]["name"], "logs")


if __name__ == "__main__":
    unittest.main()
