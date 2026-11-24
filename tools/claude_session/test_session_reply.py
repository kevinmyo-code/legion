"""The reply to a message Kevin sent from his phone (claude-hub slice S2).

Made-up transcripts in a temp folder, a fake session list and a fake summariser. Never the real
`claude`, never a real transcript, never a real session. The entry shapes are the ones a probe
observed on Claude Code 2.1.330 against a throwaway session made for the probe (2026-11-23); the
probe's own file is not committed, because it holds paths and session ids.

    python -m unittest discover -s tools/claude_session -p "test_*.py" -v
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import courier_hook as CH  # noqa: E402
import session_reply as sr  # noqa: E402
import sessions as S  # noqa: E402
import test_sessions as T  # noqa: E402
from common import Paths, read_json, write_json_atomic  # noqa: E402
from engine_client import EngineClient  # noqa: E402
from fake_engine import TOKEN, FakeEngine  # noqa: E402

SID = "44444444-aaaa-4bbb-8ccc-000000000004"
MSG = "have a look at CI and tell me which job is red"
CANARY = "canary pear quince: this must not leave the laptop"


# ------------------------------------------------------------------ transcript fixtures

def peer_entry(text: str) -> dict:
    """The shape the probe saw: a `user` entry, `isMeta`, `turnOrigin: peer`, the exact text
    inside a `cross-session-message` wrapper."""
    body = ("Another Claude session sent a message:\n"
            '<cross-session-message from="uds:\\\\.\\pipe\\LOCAL\\cc-msg-0123" '
            'from-name="courier-7" from-mode="prompting">\n'
            f"{text}\n</cross-session-message>\n\nThis came from another Claude session, not "
            "typed by your user.")
    return {"type": "user", "isMeta": True, "isSidechain": False, "turnOrigin": "peer",
            "origin": {"kind": "peer", "from": "uds:\\\\.\\pipe\\LOCAL\\cc-msg-0123"},
            "message": {"role": "user", "content": body}}


def said(text: str, stop: str = "end_turn") -> dict:
    return {"type": "assistant", "isSidechain": False,
            "message": {"role": "assistant", "stop_reason": stop,
                        "content": [{"type": "text", "text": text}]}}


def used_a_tool() -> dict:
    return {"type": "assistant", "isSidechain": False,
            "message": {"role": "assistant", "stop_reason": "tool_use",
                        "content": [{"type": "tool_use", "name": "Read", "input": {}}]}}


def tool_result() -> dict:
    return {"type": "user", "message": {"role": "user",
                                        "content": [{"type": "tool_result", "content": "ok"}]}}


def typed(text: str) -> dict:
    return {"type": "user", "isSidechain": False, "promptSource": "user",
            "message": {"role": "user", "content": text}}


class TestReadingATranscript(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "s.jsonl"
        self.addCleanup(self.tmp.cleanup)

    def write(self, *entries: dict) -> None:
        with open(self.path, "a", encoding="utf-8") as f:
            for e in entries:
                f.write(json.dumps(e) + "\n")

    def test_only_what_came_after_the_offset_is_read(self):
        self.write(said("an older turn nobody asked about"))
        offset = self.path.stat().st_size
        self.write(peer_entry(MSG), said("the android job is red"))
        entries, new = sr.read_entries(self.path, offset)
        self.assertEqual([e["type"] for e in entries], ["user", "assistant"])
        self.assertEqual(new, self.path.stat().st_size)
        self.assertEqual(sr.read_entries(self.path, new), ([], new))

    def test_a_half_written_line_waits_for_the_next_poll(self):
        self.write(peer_entry(MSG))
        offset = self.path.stat().st_size
        with open(self.path, "a", encoding="utf-8") as f:
            f.write('{"type": "assist')  # still being written
        entries, new = sr.read_entries(self.path, offset)
        self.assertEqual(entries, [])
        self.assertEqual(new, offset)

    def test_a_replaced_transcript_starts_again(self):
        self.write(peer_entry(MSG))
        entries, _new = sr.read_entries(self.path, 10_000_000)
        self.assertEqual(len(entries), 1)


class TestTheAnchorAndTheTurnEnd(unittest.TestCase):
    def test_the_delivered_message_is_the_anchor(self):
        anchored, text, ended = sr.scan([peer_entry(MSG), said("the android job is red")],
                                        MSG, False)
        self.assertTrue(anchored)
        self.assertEqual(text, "the android job is red")
        self.assertTrue(ended)

    def test_an_ordinary_turn_quoting_the_message_is_not_the_anchor(self):
        """Matching on the text alone would anchor on Kevin's own words in that session."""
        anchored, _text, ended = sr.scan([typed(MSG), said("sure")], MSG, False)
        self.assertFalse(anchored)
        self.assertFalse(ended)

    def test_a_peer_message_that_merely_quotes_the_words_is_not_the_anchor(self):
        """The wrapper has to be matched too: another session could repeat Kevin's own words."""
        bare = {"type": "user", "isMeta": True, "turnOrigin": "peer",
                "origin": {"kind": "peer", "from": "uds:x"},
                "message": {"role": "user", "content": f"by the way, he said {MSG}"}}
        anchored, _t, _e = sr.scan([bare, said("sure")], MSG, False)
        self.assertFalse(anchored)

    def test_the_wrapper_typed_by_hand_is_not_the_anchor(self):
        """Where it came from has to be checked too: Kevin could paste the wrapper himself."""
        pasted = dict(peer_entry(MSG))
        pasted.pop("turnOrigin")
        pasted.pop("origin")
        pasted["isMeta"] = False
        anchored, _t, _e = sr.scan([pasted, said("sure")], MSG, False)
        self.assertFalse(anchored)

    def test_another_sessions_peer_message_is_not_the_anchor(self):
        anchored, _t, _e = sr.scan([peer_entry("a different message entirely")], MSG, False)
        self.assertFalse(anchored)

    def test_a_longer_peer_message_that_contains_his_words_is_not_the_anchor(self):
        """The anchor is the delivered message, not any message holding its words.

        A substring test anchors on the first peer message whose body merely CONTAINS the
        pinned text, and the first `end_turn` after it is then that other message's answer.
        Kevin would be shown someone else's reply as the reply to his (S2 review, finding 2).
        """
        anchored, text, ended = sr.scan(
            [peer_entry("please " + MSG + " now"), said("that other message is answered here")],
            MSG, False)
        self.assertFalse(anchored)
        self.assertIsNone(text)
        self.assertFalse(ended)

    def test_a_message_whose_words_appear_in_the_wrappers_own_boilerplate_is_not_the_anchor(self):
        """The wrapper says "Another Claude session sent a message", so a substring test makes
        ANY peer message the anchor for a message like this one (S2 review, finding 2)."""
        anchored, text, _e = sr.scan(
            [peer_entry("a different message entirely"), said("not his answer")],
            "Claude session", False)
        self.assertFalse(anchored)
        self.assertIsNone(text)

    def test_the_message_is_still_the_anchor_when_it_holds_wrapper_shaped_text(self):
        """Equality must not go the other way and refuse a real delivery. His own words may
        hold angle brackets and even the wrapper's own name."""
        odd = "fix <cross-session-message> handling in the courier & say what changed"
        anchored, text, ended = sr.scan([peer_entry(odd), said("done, one line in sessions.py")],
                                        odd, False)
        self.assertTrue(anchored and ended)
        self.assertEqual(text, "done, one line in sessions.py")

    def test_the_reply_is_the_last_text_of_the_turn_not_the_first(self):
        anchored, text, ended = sr.scan(
            [peer_entry(MSG), said("let me look", stop="tool_use"), used_a_tool(), tool_result(),
             said("the android job is red; the rest are green")], MSG, False)
        self.assertTrue(anchored and ended)
        self.assertEqual(text, "the android job is red; the rest are green")

    def test_a_turn_still_running_has_not_ended(self):
        anchored, text, ended = sr.scan(
            [peer_entry(MSG), said("let me look", stop="tool_use"), used_a_tool()], MSG, False)
        self.assertTrue(anchored)
        self.assertFalse(ended)
        self.assertEqual(text, "let me look")

    def test_a_turn_kevin_typed_himself_is_never_reported_as_the_reply(self):
        anchored, text, ended = sr.scan(
            [peer_entry(MSG), typed("actually never mind"), said("right, dropping it")],
            MSG, False)
        self.assertTrue(anchored and ended)
        self.assertIsNone(text)

    def test_an_already_anchored_poll_reads_from_the_first_entry(self):
        anchored, text, ended = sr.scan([said("the android job is red")], MSG, True)
        self.assertTrue(anchored and ended)
        self.assertEqual(text, "the android job is red")

    def test_nothing_before_the_delivered_message_is_ever_read(self):
        _a, text, _e = sr.scan([said("an older unrelated answer"), peer_entry(MSG)], MSG, False)
        self.assertIsNone(text)

    def test_a_secret_in_the_turn_is_scrubbed(self):
        _a, text, _e = sr.scan([peer_entry(MSG), said("the token is abc123 so it failed")],
                               MSG, False)
        self.assertNotIn("abc123", text)


class TestTheGuards(T.Base):
    def plan(self, **over) -> dict:
        p = {"task": 41, "session_id": SID, "name": "garden", "folder": "garden", "text": MSG,
             "at": 0}
        p.update(over)
        return p

    def transcript(self, sid: str, folder: str = "proj") -> Path:
        d = self.projects / folder
        d.mkdir(parents=True, exist_ok=True)
        f = d / f"{sid}.jsonl"
        f.write_text("", encoding="utf-8")
        return f

    def test_a_watchable_session_is_watched(self):
        want = self.transcript(SID)
        path, why = sr.may_watch(self.paths, self.cfg, self.plan(), self.projects)
        self.assertEqual(path, want)
        self.assertEqual(why, "")

    def test_a_summaries_only_machine_never_reads_a_transcript(self):
        self.transcript(SID)
        path, why = sr.may_watch(self.paths, {"scope": "listed", "name": "work laptop"},
                                 self.plan(), self.projects)
        self.assertIsNone(path)
        self.assertIn("summaries only", why)

    def test_a_session_on_the_no_read_list_is_never_read(self):
        self.transcript(SID)
        for cfg in ({"scope": "full", "sessions_deny": ["garden"]},
                    {"scope": "full", "sessions_deny": ["GARDEN"]}):
            with self.subTest(cfg=cfg):
                path, why = sr.may_watch(self.paths, cfg, self.plan(), self.projects)
                self.assertIsNone(path)
                self.assertIn("no-read list", why)

    def test_the_workers_own_session_is_never_read(self):
        own = S._encoded(self.paths.root)
        self.transcript(SID, folder=own)
        path, why = sr.may_watch(self.paths, self.cfg, self.plan(), self.projects)
        self.assertIsNone(path)
        self.assertIn("worker's own", why)

    def test_a_session_with_no_transcript_here_is_not_watched(self):
        path, why = sr.may_watch(self.paths, self.cfg, self.plan(), self.projects)
        self.assertIsNone(path)
        self.assertIn("not on this laptop", why)


# ------------------------------------------------------------------ the worker's half

class FakeSummariser:
    """What a bounded `claude -p` would have given back, without one."""
    made: list[str] = []

    def __init__(self, text, cfg, paths, base_env, now, out="CI is red on the android job only."):
        FakeSummariser.made.append(text)
        self.out = out

    def done(self, now):
        return True

    def summary(self):
        return self.out


class NoSummary(FakeSummariser):
    def __init__(self, *a, **k):
        super().__init__(*a, out="")


class WorkerBase(T.Base):
    def setUp(self):
        super().setUp()
        FakeSummariser.made = []
        self.now = 1000.0
        self.engine = FakeEngine()
        self.addCleanup(self.engine.close)
        cfg = {**self.cfg, "engine_url": self.engine.url, "token": TOKEN, "keep_awake": False,
               "claude_cmd": [sys.executable, str(HERE / "fake_claude.py")],
               "workflow_reports": str(self.root / "reports"),
               "task_log": str(self.root / "task-log.jsonl"),
               "agent_rules": str(self.root / "AGENT-RULES.md")}
        write_json_atomic(self.paths.config, cfg)
        self.w = T.W.Worker(self.paths, cfg, power=None, clock=lambda: self.now,
                            today=lambda: "2026-11-23", drives=lambda: ["C:\\"], base_env={},
                            engine=EngineClient(cfg["engine_url"], cfg["token"], timeout=3),
                            projects=self.projects, summariser=FakeSummariser)
        self.card = {"id": 41, "parent": None, "status": "needs_confirmation", "kind": "ask",
                     "request": "tell garden to check CI", "started_at": 0, "turns": 0,
                     "send_plan_sha": None, "question": "", "summary": "", "found": []}
        self.yes = {"id": 42, "parent": 41, "status": "running", "kind": "ask", "confirms": True,
                    "request": "yes", "started_at": self.now, "turns": 0, "found": []}
        self.w.state["tasks"] = {"41": self.card, "42": self.yes}

    def pin(self, delivered=True, **over) -> dict:
        plan = {"task": 41, "session_id": SID, "name": "garden", "folder": "garden", "text": MSG,
                "at": 0}
        plan["sha256"] = S.plan_sha(plan)
        plan.update(over)
        if delivered:
            plan["delivered"] = True
        write_json_atomic(S.plan_path(self.paths, 41), plan)
        self.card["send_plan_sha"] = plan["sha256"]
        return plan

    def transcript(self, *entries: dict) -> Path:
        d = self.projects / "proj"
        d.mkdir(parents=True, exist_ok=True)
        f = d / f"{SID}.jsonl"
        with open(f, "a", encoding="utf-8") as fh:
            for e in entries:
                fh.write(json.dumps(e) + "\n")
        return f

    def report(self, status="done", summary="Delivered to garden's inbox.") -> None:
        write_json_atomic(self.paths.reports / "42.json",
                          {"task": 42, "status": status, "summary": summary})

    def outbox(self) -> list[dict]:
        return list(self.w.state["outbox"])


class TestHoldingTheTaskOpen(WorkerBase):
    def test_a_delivered_message_is_held_open_not_finished(self):
        self.pin()
        self.transcript()
        self.report()
        self.w.drain_reports(self.now)
        self.assertEqual(self.yes["status"], "running")
        self.assertEqual(self.outbox(), [])
        self.assertEqual(self.yes["reply_watch"]["deadline"], self.now + sr.WINDOW_S)

    def test_the_window_is_ten_minutes(self):
        self.assertEqual(sr.WINDOW_S, 10 * 60.0)

    def test_an_ordinary_task_is_finished_at_once(self):
        self.w.state["tasks"]["42"] = {**self.yes, "confirms": False, "parent": None}
        self.report(summary="the log says the build is fine")
        self.w.drain_reports(self.now)
        self.assertEqual(self.w.state["tasks"]["42"]["status"], "done")
        self.assertEqual(len(self.outbox()), 1)

    def test_a_message_that_never_went_is_finished_at_once(self):
        self.pin(delivered=False)
        self.transcript()
        self.report(summary="Not sent: session garden was renamed since Kevin's yes.")
        self.w.drain_reports(self.now)
        self.assertEqual(self.yes["status"], "done")
        self.assertEqual(self.outbox()[0]["payload"]["summary"],
                         "Not sent: session garden was renamed since Kevin's yes.")

    def test_a_plan_whose_words_changed_since_the_card_is_not_watched(self):
        """`send_plan_sha` pins the card; a plan that no longer hashes to it is not Kevin's."""
        self.pin()
        self.card["send_plan_sha"] = "a different hash entirely"
        self.transcript()
        self.report()
        self.w.drain_reports(self.now)
        self.assertEqual(self.yes["status"], "done")

    def test_a_listed_machine_never_arms_a_watch(self):
        self.w.scope = "listed"
        self.w.cfg["scope"] = "listed"
        self.pin()
        self.transcript()
        self.report()
        self.w.drain_reports(self.now)
        self.assertEqual(self.yes["status"], "done")
        self.assertNotIn("reply_watch", self.yes)

    def test_a_denied_session_says_so_on_the_card_and_waits_for_nothing(self):
        self.w.cfg["sessions_deny"] = ["garden"]
        self.pin()
        self.transcript()
        self.report()
        self.w.drain_reports(self.now)
        self.assertEqual(self.yes["status"], "done")
        self.assertIn("no-read list", self.outbox()[0]["payload"]["summary"])


class TestWaitingIsNotWorking(WorkerBase):
    def arm(self):
        self.pin()
        self.transcript()
        self.report()
        self.w.drain_reports(self.now)

    def test_a_waiting_task_does_not_make_the_machine_busy(self):
        self.arm()
        self.assertEqual(self.w.running(), [self.yes])
        self.assertEqual(self.w.working(), [])
        self.w.next_contact = 0.0
        self.w.contact_engine(self.now)
        beat = self.engine.heartbeats[-1]
        self.assertEqual(beat["state"], "idle")
        self.assertEqual(beat["running"], [42])

    def test_the_report_grace_never_fails_a_waiting_task(self):
        """Without this the nudge and then SAY_NO_REPORT would kill every watch inside 2 min."""
        self.arm()
        self.yes["helper_ended_at"] = self.now
        self.yes["helper_end"] = "completed"
        for _ in range(4):  # the nudge, then the grace after it: both must pass it by
            self.now += 2 * 60
            self.w.check_timers(self.now)
            self.assertEqual(self.yes["status"], "running")
        self.assertEqual([p for p in self.w.pending if p[0] is None], [])

    def test_the_thirty_minute_cap_is_still_a_backstop(self):
        self.arm()
        self.now += 31 * 60
        self.w.check_timers(self.now)
        self.assertEqual(self.yes["status"], "failed")

    def test_a_main_session_restart_does_not_fail_a_waiting_task(self):
        self.arm()
        self.w.startup()
        self.assertEqual(self.yes["status"], "running")


class TestTheReplyComesBack(WorkerBase):
    def arm(self):
        self.pin()
        self.transcript()
        self.report()
        self.w.drain_reports(self.now)
        self.w.next_reply_poll = 0.0

    def poll(self):
        self.w.next_reply_poll = 0.0
        self.w.poll_replies(self.now)

    def test_a_reply_finishes_the_task_with_the_laptops_summary(self):
        self.arm()
        self.transcript(peer_entry(MSG), said("the android job is red"))
        self.poll()
        self.assertEqual(self.yes["status"], "done")
        self.assertEqual(self.outbox()[0]["payload"]["summary"],
                         "CI is red on the android job only.")
        self.assertEqual(FakeSummariser.made, ["the android job is red"])

    def test_the_session_text_never_reaches_the_engine(self):
        """The canary is in the turn. Only the laptop's summary may leave (R1)."""
        self.arm()
        self.transcript(peer_entry(MSG), said(f"the android job is red because {CANARY}"))
        self.poll()
        blob = json.dumps(self.outbox()) + json.dumps(self.w.progress) + json.dumps(
            read_json(self.paths.state, {}) or {})
        self.assertNotIn(CANARY, blob)
        self.assertIn(CANARY, FakeSummariser.made[0])  # the laptop did see it

    def test_a_turn_still_running_keeps_waiting(self):
        self.arm()
        self.transcript(peer_entry(MSG), said("let me look", stop="tool_use"), used_a_tool())
        self.poll()
        self.assertEqual(self.yes["status"], "running")
        self.assertEqual(self.outbox(), [])

    def test_a_reply_split_over_two_polls_is_still_the_last_text(self):
        self.arm()
        self.transcript(peer_entry(MSG), said("let me look", stop="tool_use"))
        self.poll()
        self.transcript(used_a_tool(), tool_result(), said("it is the android job"))
        self.poll()
        self.assertEqual(self.yes["status"], "done")
        self.assertEqual(FakeSummariser.made, ["it is the android job"])

    def test_nothing_within_ten_minutes_says_so(self):
        self.arm()
        self.transcript(peer_entry(MSG), said("working on it", stop="tool_use"))
        self.poll()
        self.now += sr.WINDOW_S + 1
        self.poll()
        self.assertEqual(self.yes["status"], "done")
        self.assertIn("said nothing back", self.outbox()[0]["payload"]["summary"])

    def test_a_summariser_that_gives_nothing_falls_back_to_the_workers_own_words(self):
        self.w.make_summariser = NoSummary
        self.arm()
        self.transcript(peer_entry(MSG), said(f"the android job is red because {CANARY}"))
        self.poll()
        self.assertEqual(self.yes["status"], "done")
        summary = self.outbox()[0]["payload"]["summary"]
        self.assertIn("could not be summarised", summary)
        self.assertNotIn(CANARY, summary)

    def test_reading_the_transcript_marks_the_task_read_through(self):
        self.arm()
        self.transcript(peer_entry(MSG), said("the android job is red"))
        self.poll()
        self.assertTrue(self.w.session_read(42))
        self.assertIn("session_transcript",
                      (read_json(self.paths.taint / "task-42.json", {}) or {})["sources"])

    def test_the_poll_keeps_to_the_heartbeats_beat(self):
        self.arm()
        self.w.next_reply_poll = self.now + sr.POLL_S
        self.transcript(peer_entry(MSG), said("the android job is red"))
        self.w.poll_replies(self.now)
        self.assertEqual(self.yes["status"], "running")


class TestASecondReportCannotSpeakForTheSession(WorkerBase):
    def test_a_second_report_while_waiting_is_ignored(self):
        """Finishing on it would put the HELPER's words where the reply belongs, and the engine
        takes one finish per task, so the reply would then have nowhere to go."""
        self.pin()
        self.transcript()
        self.report()
        self.w.drain_reports(self.now)
        self.assertEqual(self.yes["status"], "running")
        self.report(summary="the helper decided to say something else entirely")
        self.w.drain_reports(self.now)
        self.assertEqual(self.yes["status"], "running")
        self.assertEqual(self.outbox(), [])
        self.assertIn("reply_watch", self.yes)


class TestTheGuardsAreRecheckedOnEveryPoll(WorkerBase):
    def test_a_session_denied_after_the_watch_was_armed_stops_being_read(self):
        self.pin()
        self.transcript()
        self.report()
        self.w.drain_reports(self.now)
        self.w.cfg["sessions_deny"] = ["garden"]  # Kevin adds it while the watch is open
        self.transcript(peer_entry(MSG), said("the android job is red"))
        self.w.next_reply_poll = 0.0
        self.w.poll_replies(self.now)
        self.assertEqual(self.yes["status"], "done")
        self.assertIn("no-read list", self.outbox()[0]["payload"]["summary"])
        self.assertEqual(FakeSummariser.made, [])


class TestTheProgressStep(WorkerBase):
    def test_the_phone_is_told_the_message_is_in_its_inbox(self):
        self.pin()
        self.transcript()
        self.report()
        self.w.drain_reports(self.now)
        self.assertEqual(self.w.progress[42], sr.STEP_IN_INBOX)


class TestTheSummariserItself(T.Base):
    def test_it_is_given_no_tools_and_a_bounded_budget(self):
        cmd = sr.summary_command({"claude_cmd": ["claude"]})
        self.assertEqual(cmd[cmd.index("--tools") + 1], "")
        self.assertEqual(cmd[cmd.index("--permission-mode") + 1], "dontAsk")
        self.assertEqual(cmd[cmd.index("--max-budget-usd") + 1], sr.SUMMARY_BUDGET)
        self.assertIn("--no-session-persistence", cmd)

    def test_an_enormous_reply_is_capped_before_it_reaches_the_pipe(self):
        seen = {}

        def popen(cmd, prompt, cwd, env):
            seen["prompt"] = prompt
            raise OSError("not actually starting one")

        sr.Summariser("x" * 50_000, {"claude_cmd": ["claude"]}, self.paths, {"PATH": ""}, 0.0,
                      popen=popen)
        self.assertLess(len(seen["prompt"]), sr.REPLY_MAX + 2000)

    def test_a_summariser_that_could_not_start_is_done_with_nothing(self):
        def popen(cmd, prompt, cwd, env):
            raise OSError("no claude here")

        s = sr.Summariser("a reply", {"claude_cmd": ["claude"]}, self.paths, {"PATH": ""}, 0.0,
                          popen=popen)
        self.assertTrue(s.done(0.0))
        self.assertEqual(s.summary(), "")

    def test_one_that_runs_too_long_is_killed_and_gives_nothing(self):
        class Slow:
            stdout = None
            killed = False

            def poll(self):
                return None

            def kill(self):
                Slow.killed = True

        s = sr.Summariser("a reply", {"claude_cmd": ["claude"]}, self.paths, {"PATH": ""}, 0.0,
                          popen=lambda *a: Slow())
        self.assertFalse(s.done(1.0))
        self.assertTrue(s.done(sr.SUMMARY_TIMEOUT_S + 1))
        self.assertTrue(Slow.killed)
        self.assertEqual(s.summary(), "")

    def test_a_summary_is_trimmed_to_the_cap_that_may_leave_the_laptop(self):
        class Fat:
            stdout = T.io.StringIO("word " * 500)

            def poll(self):
                return 0

        s = sr.Summariser("a reply", {"claude_cmd": ["claude"]}, self.paths, {"PATH": ""}, 0.0,
                          popen=lambda *a: Fat())
        self.assertTrue(s.done(1.0))
        self.assertLessEqual(len(s.summary()), S.SUMMARY_MAX)


class TestTheThreadColumns(WorkerBase):
    def test_the_card_carries_the_session_and_the_exact_message(self):
        self.pin(delivered=False)
        self.w.finish(self.card, "needs_confirmation", "", "ignored, the worker rewrites it")
        payload = self.outbox()[0]["payload"]
        self.assertEqual(payload["session_id"], SID)
        self.assertEqual(payload["session_text"], MSG)
        self.assertIn(MSG, payload["question"])

    def test_the_yes_carries_the_session_but_never_the_message_again(self):
        self.pin()
        self.w.finish(self.yes, "done", "Delivered to garden's inbox.")
        payload = self.outbox()[0]["payload"]
        self.assertEqual(payload["session_id"], SID)
        self.assertNotIn("session_text", payload)

    def test_an_ordinary_task_carries_neither(self):
        plain = {"id": 50, "parent": None, "status": "running", "kind": "ask", "request": "hi",
                 "started_at": 0, "turns": 0, "found": []}
        self.w.state["tasks"]["50"] = plain
        self.w.finish(plain, "done", "done")
        payload = self.outbox()[0]["payload"]
        self.assertNotIn("session_id", payload)
        self.assertNotIn("session_text", payload)

    def test_a_listed_machine_sends_neither(self):
        self.w.scope = "listed"
        self.pin(delivered=False)
        self.w.finish(self.card, "needs_confirmation", "", "q")
        self.assertNotIn("session_id", self.outbox()[0]["payload"])


class TestTheSendRecordsWhereTheTranscriptEnded(T.Base):
    def test_the_offset_is_taken_before_the_courier_runs(self):
        d = self.projects / "proj"
        d.mkdir(parents=True, exist_ok=True)
        f = d / f"{SID}.jsonl"
        f.write_text("x" * 500, encoding="utf-8")
        rows = [{"sessionId": SID, "name": "garden", "kind": "interactive", "status": "idle",
                 "cwd": str(self.root / "repos" / "garden")}]
        (self.work / "msg.txt").write_text(MSG, encoding="utf-8")
        S.ask(self.paths, self.cfg, 41, "garden", str(self.work / "msg.txt"), rows, 0)
        plan = S.pinned_plan(self.paths, 41)
        write_json_atomic(self.paths.tokens / "42-session_send.json",
                          {"token": "t" * 32, "task": 42, "parent": 41, "kind": "session_send",
                           "plan_sha": plan["sha256"], "expires_at": 10_000})

        def runner(cmd, prompt, cwd, env):
            f.write_text("x" * 900, encoding="utf-8")  # the session writes while we send
            ev = {"tool_name": "SendMessage", "tool_input": {"to": "garden", "message": MSG}}
            ok = CH.decide(ev, Paths(Path(env["LEGION_CLAUDE_HOME"]))) is None
            return "\n".join(json.dumps(x) for x in (
                {"type": "assistant", "message": {"content": [
                    {"type": "tool_use", "id": "t1", "name": "SendMessage"}]}},
                {"type": "user", "message": {"content": [
                    {"type": "tool_result", "tool_use_id": "t1",
                     "content": json.dumps({"success": ok})}]}}))

        out = S.send(self.paths, self.cfg, 42, "t" * 32, rows, 0, T.PY, runner=runner,
                     base_env={"PATH": ""}, projects=self.projects)
        self.assertIn("inbox", out)
        self.assertEqual(S.pinned_plan(self.paths, 41)["transcript_bytes"], 500)


if __name__ == "__main__":
    unittest.main()
