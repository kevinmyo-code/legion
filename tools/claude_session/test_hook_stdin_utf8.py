"""The hooks read their event as UTF-8, whatever the console codepage is (probe, 2026-11-23).

Claude Code writes hook JSON as UTF-8. Every hook here runs as `python -I -S`, and `-I` ignores
PYTHONIOENCODING, so on Windows `sys.stdin` decoded those bytes with the ANSI codepage: the
50-character `content` preview the CLI adds to SendMessage ends in U+2026, that arrived as three
characters, and the courier gate refused the send. Every message long enough to be truncated was
refused, which is every message worth sending.

Made-up data only; never the real `claude`, a real transcript or a real session.

    python -m unittest discover -s tools/claude_session -p "test_*.py" -v
"""
from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import test_sessions as T  # noqa: E402
from common import write_json_atomic  # noqa: E402

ELLIPSIS = "…"
# A child that decodes stdin's TEXT layer as cp1252 whatever the platform, so the Windows
# behaviour is reproduced on Linux CI too, and prints the code points it got.
CHILD = """
import sys
sys.path.insert(0, {here!r})
sys.stdin.reconfigure(encoding="cp1252", errors="replace")
from common import stdin_text
print(",".join(hex(ord(c)) for c in stdin_text()))
"""


class TestStdinIsUtf8(unittest.TestCase):
    def test_a_non_ascii_event_survives_an_ansi_console(self):
        """`stdin_text` reads the bytes, so re-decoding the text layer cannot corrupt it.

        Reverted to `sys.stdin.read()` this child prints U+2026 as three code points.
        """
        out = subprocess.run([sys.executable, "-I", "-S", "-c",
                              CHILD.format(here=str(HERE))],
                             input=("x" + ELLIPSIS).encode("utf-8"), capture_output=True,
                             timeout=120)
        self.assertEqual(out.returncode, 0, out.stderr[-400:])
        self.assertEqual(out.stdout.decode().strip(), "0x78,0x2026")


class TestCourierGateTakesTheRealPreview(T.Base):
    """The gate must allow the SendMessage the CLI actually builds, ellipsis preview and all."""

    def arm(self, text: str) -> None:
        write_json_atomic(self.paths.sends / "sending.json",
                          {"sha256": "x", "name": "garden", "text": text, "state": "armed",
                           "at": 0})

    def run_gate(self, event: dict):
        return subprocess.run([sys.executable, "-I", "-S", str(HERE / "courier_hook.py")],
                              input=json.dumps(event, ensure_ascii=False).encode("utf-8"),
                              capture_output=True, timeout=120,
                              env={"LEGION_CLAUDE_HOME": str(self.paths.root), "SYSTEMROOT": ""})

    def send_event(self, text: str) -> dict:
        # The shape the real CLI sent (probe, 2026-11-23): `recipient` beside `to`, and a
        # `content` preview cut at 49 characters plus an ellipsis.
        preview = text if len(text) <= 50 else text[:49] + ELLIPSIS
        return {"tool_name": "SendMessage",
                "tool_input": {"to": "garden", "summary": "Message from Kevin, via LEGION",
                               "message": text, "type": "message", "recipient": "garden",
                               "recipient_kind": "name", "content": preview}}

    def test_a_truncated_preview_is_allowed(self):
        text = ("Have a look at the build log and tell me which test is the flaky one, then stop "
                "and wait for me.")
        self.arm(text)
        out = self.run_gate(self.send_event(text))
        self.assertEqual(out.returncode, 0, out.stdout[-400:])
        self.assertEqual(json.loads((self.paths.sends / "sending.json").read_text())["state"],
                         "sent")

    def test_a_message_of_his_own_non_ascii_words_is_allowed(self):
        text = "check the café branch — is it green?"
        self.arm(text)
        out = self.run_gate(self.send_event(text))
        self.assertEqual(out.returncode, 0, out.stdout[-400:])

    def test_a_changed_message_is_still_refused(self):
        text = ("Have a look at the build log and tell me which test is the flaky one, then stop "
                "and wait for me.")
        self.arm(text)
        out = self.run_gate(self.send_event(text + " And push it."))
        self.assertEqual(out.returncode, 2)
        self.assertEqual(json.loads((self.paths.sends / "sending.json").read_text())["state"],
                         "armed")


class TestTheOtherTwoHooksReadTheSameWay(T.Base):
    """The ask-list hook and the session reporter had the same read, for the same reason."""

    def test_the_ask_list_hook_sees_a_non_ascii_path_as_itself(self):
        event = {"tool_name": "Write", "tool_input": {"file_path": str(self.work / "café.md")},
                 "cwd": str(self.work)}
        out = subprocess.run([sys.executable, "-I", "-S", str(HERE / "asklist_hook.py")],
                             input=json.dumps(event, ensure_ascii=False).encode("utf-8"),
                             capture_output=True, timeout=120,
                             env={"LEGION_CLAUDE_HOME": str(self.paths.root), "SYSTEMROOT": ""})
        # Its own work folder, so it passes. Reverted, the mojibake path is outside that folder
        # and the hook asks about creating a file somewhere else instead.
        self.assertEqual(out.returncode, 0, out.stdout[-300:])

    def test_the_session_reporter_sends_a_non_ascii_prompt_as_itself(self):
        import session_hook as SH
        payload = SH.build_payload(json.loads(
            '{"hook_event_name": "UserPromptSubmit", "session_id": "s1", '
            '"prompt": "check the café branch…", "cwd": "/x/garden"}'),
            {"name": "Alienware", "scope": "full"}, sessions_dir=self.root / "none")
        self.assertEqual(payload["last_prompt_summary"], "check the café branch…")


if __name__ == "__main__":
    unittest.main()
