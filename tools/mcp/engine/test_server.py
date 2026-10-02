"""Tests for the engine MCP adapter against a fake engine (stdlib http.server).

    uv run --project tools/mcp/engine python -m unittest discover -s tools/mcp/engine -v

The fake records every HTTP method it receives. The no-write test runs every tool, on the
success path and on every failure path, and asserts the only verb that ever reached the engine
was GET. A second check reads the source: `urllib.request.Request` is built in exactly one place,
with `method="GET"`.
"""
from __future__ import annotations

import inspect
import json
import os
import re
import socket
import sys
import threading
import unittest
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import server  # noqa: E402

TOKEN = "good-token"
NOW = datetime.now(timezone.utc)


def iso(delta_days: float) -> str:
    return (NOW + timedelta(days=delta_days)).isoformat()


EVENTS = [
    {"id": "e1", "title": "Dentist", "kind": "event", "starts_at": iso(2), "done": False, "deleted_at": None},
    {"id": "e2", "title": "Pay rent", "kind": "task", "starts_at": iso(-5), "done": False, "deleted_at": None},
    {"id": "e3", "title": "Deleted", "kind": "task", "starts_at": iso(1), "done": False, "deleted_at": iso(-1)},
    {"id": "e4", "title": "Water plants", "kind": "reminder", "starts_at": iso(-30), "done": False,
     "repeat_kind": "WEEKLY", "deleted_at": None},
    {"id": "e5", "title": "Done thing", "kind": "task", "starts_at": iso(1), "done": True, "deleted_at": None},
    {"id": "e6", "title": "Someday", "kind": "task", "starts_at": None, "done": False, "deleted_at": None},
]
CHECKLISTS = [{"id": "11111111-1111-1111-1111-111111111111", "name": "Morning", "archived": False,
               "sort_order": 0, "deleted_at": None}]
ITEMS = [{"id": "i1", "text": "Meds", "sort_order": 0, "deleted_at": None}]
PLACES = [{"label": "home", "latitude": 29.7, "longitude": -95.4, "provenance": "USER", "deleted_at": None}]
TXNS = [
    {"id": "t1", "txn_date": "2026-09-30", "description": "HEB", "amount_cents": -4512,
     "account_last4": "1234", "provenance": "DETERMINISTIC", "verification_note": None,
     "created_at": "2026-10-01T00:00:00Z"},
    {"id": "t2", "txn_date": "2026-10-01", "description": "Shell", "amount_cents": -3000,
     "account_last4": "1234", "provenance": "UNRECONCILED",
     "verification_note": "Unverified: from a bank activity export.", "created_at": "2026-10-01T00:00:00Z"},
    {"id": "t3", "txn_date": "2026-08-01", "description": "Old", "amount_cents": -100,
     "account_last4": "9999", "provenance": "LLM_RECONCILED", "verification_note": None,
     "created_at": "2026-08-02T00:00:00Z"},
]
RECEIPTS = [
    {"id": "r1", "store": "HEB", "purchase_date": "2026-09-30", "total_cents": 4512,
     "provenance": "DETERMINISTIC", "unaccounted_cents": None, "created_at": "2026-10-01T00:00:00Z"},
    {"id": "r2", "store": "Kroger", "purchase_date": "2026-08-01", "total_cents": 999,
     "provenance": "UNRECONCILED", "unaccounted_cents": 120, "created_at": "2026-08-02T00:00:00Z"},
]


class FakeEngine(BaseHTTPRequestHandler):
    methods: list[str] = []
    mode = "ok"  # ok | 403 | 500 | html

    def log_message(self, *args):  # keep test output quiet
        pass

    def _send(self, code: int, body, content_type="application/json"):
        raw = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _any(self):
        FakeEngine.methods.append(self.command)
        if self.command != "GET":
            return self._send(405, {"detail": "write attempted"})
        if self.headers.get("Authorization") != "Token " + TOKEN:
            return self._send(401, {"detail": "Invalid token."})
        if FakeEngine.mode == "403":
            return self._send(403, {"detail": "You do not have permission to perform this action."})
        if FakeEngine.mode == "500":
            return self._send(500, {"detail": "boom"})
        if FakeEngine.mode == "html":
            return self._send(200, b"<html>login</html>", "text/html")
        path = self.path.split("?")[0]
        if "since=" in self.path and path == "/api/ledger/transactions/":
            return self._send(200, {"results": TXNS[2:], "next": None, "next_after": None})
        routes = {
            "/api/events": {"results": EVENTS, "next": None},
            "/api/checklists/": {"results": CHECKLISTS, "next": None},
            "/api/checklists/%s/items" % CHECKLISTS[0]["id"]: {"results": ITEMS, "next": None},
            "/api/places/": {"results": PLACES, "next": None, "next_after": None},
            # Two pages, to exercise the keyset cursor.
            "/api/ledger/transactions/": {"results": TXNS[:2], "next": "2026-10-01T00:00:00Z", "next_after": "t2"},
            "/api/pantry/receipts/": {"results": RECEIPTS, "next": None, "next_after": None},
        }
        if path in routes:
            return self._send(200, routes[path])
        return self._send(404, {"detail": "Not found."})

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = _any


def all_tools():
    return [
        lambda: server.due(),
        lambda: server.checklists(),
        lambda: server.places(),
        lambda: server.ledger_transactions(),
        lambda: server.pantry_receipts(),
    ]


class EngineAdapterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), FakeEngine)
        cls.url = "http://127.0.0.1:%d" % cls.httpd.server_address[1]
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    def setUp(self):
        FakeEngine.mode = "ok"
        self._env = {k: os.environ.get(k) for k in (server.URL_VAR, server.TOKEN_VAR)}
        os.environ[server.URL_VAR] = self.url
        os.environ[server.TOKEN_VAR] = TOKEN

    def tearDown(self):
        for k, v in self._env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v

    # -- success -------------------------------------------------------------

    def test_due_buckets(self):
        r = server.due(days_ahead=7, days_back=1)
        self.assertTrue(r["ok"])
        self.assertEqual([e["id"] for e in r["upcoming"]], ["e1"])
        self.assertEqual([e["id"] for e in r["overdue"]], ["e2"])
        self.assertEqual([e["id"] for e in r["repeating"]], ["e4"])
        self.assertEqual([e["id"] for e in r["undated"]], ["e6"])
        every = [e["id"] for k in ("upcoming", "recent", "overdue", "repeating", "undated") for e in r[k]]
        self.assertNotIn("e3", every)  # deleted
        self.assertNotIn("e5", every)  # done

    def test_checklists_with_items(self):
        r = server.checklists()
        self.assertTrue(r["ok"])
        self.assertEqual(r["checklists"][0]["name"], "Morning")
        self.assertEqual(r["checklists"][0]["items"][0]["text"], "Meds")

    def test_places(self):
        r = server.places()
        self.assertEqual(r["places"][0]["label"], "home")

    def test_ledger_pages_and_says_unverified_in_words(self):
        r = server.ledger_transactions()
        self.assertTrue(r["ok"])
        self.assertEqual(r["matched"], 3)  # both pages read
        by_id = {t["id"]: t for t in r["transactions"]}
        self.assertFalse(by_id["t2"]["verified"])
        self.assertTrue(by_id["t2"]["verification_note"].startswith("Unverified"))
        self.assertTrue(by_id["t1"]["verified"])
        self.assertIsNone(by_id["t1"]["verification_note"])
        self.assertEqual(r["unverified_count"], 1)
        self.assertIn("unverified", r["note"].lower())

    def test_ledger_date_and_account_filter(self):
        r = server.ledger_transactions(from_date="2026-09-01", to_date="2026-10-31", account_last4="1234")
        self.assertEqual(sorted(t["id"] for t in r["transactions"]), ["t1", "t2"])
        self.assertEqual(r["transactions"][0]["id"], "t2")  # newest first

    def test_ledger_bad_date_makes_no_request(self):
        FakeEngine.methods.clear()
        r = server.ledger_transactions(from_date="last week")
        self.assertFalse(r["ok"])
        self.assertEqual(FakeEngine.methods, [])

    def test_receipts_unverified(self):
        r = server.pantry_receipts()
        by_id = {x["id"]: x for x in r["receipts"]}
        self.assertFalse(by_id["r2"]["verified"])
        self.assertTrue(by_id["r2"]["verification_note"].startswith("Unverified"))
        self.assertTrue(by_id["r1"]["verified"])

    # -- failures ------------------------------------------------------------

    def assertReadNothing(self, r, kind):
        self.assertFalse(r["ok"])
        self.assertEqual(r["failure"], kind)
        self.assertEqual(r["read"], "nothing")
        self.assertIn("Nothing was read", r["error"])
        for key in ("upcoming", "checklists", "places", "transactions", "receipts"):
            self.assertNotIn(key, r)  # never an empty list standing in for a failure

    def test_unreachable_is_not_empty(self):
        sock = socket.socket()
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
        sock.close()  # nothing listens on this port now
        os.environ[server.URL_VAR] = "http://127.0.0.1:%d" % port
        for tool in all_tools():
            r = tool()
            self.assertReadNothing(r, "unreachable")
            self.assertIn("unreachable", r["error"])
            self.assertIn("not an empty result", r["error"])

    def test_bad_token_refused(self):
        os.environ[server.TOKEN_VAR] = "wrong"
        for tool in all_tools():
            r = tool()
            self.assertReadNothing(r, "token_refused")
            self.assertIn("refused the token", r["error"])

    def test_no_household(self):
        FakeEngine.mode = "403"
        for tool in all_tools():
            r = tool()
            self.assertReadNothing(r, "no_household")
            self.assertIn("no household", r["error"])

    def test_server_error_and_non_json(self):
        FakeEngine.mode = "500"
        self.assertReadNothing(server.places(), "engine_error")
        FakeEngine.mode = "html"
        self.assertReadNothing(server.places(), "bad_response")

    def test_missing_config_makes_no_request(self):
        FakeEngine.methods.clear()
        os.environ.pop(server.TOKEN_VAR)
        r = server.places()
        self.assertEqual(r["failure"], "not_configured")
        self.assertIn(server.TOKEN_VAR, r["error"])
        self.assertEqual(FakeEngine.methods, [])

    # -- no writes -----------------------------------------------------------

    def test_no_tool_issues_a_non_get_request(self):
        FakeEngine.methods.clear()
        for mode, token in (("ok", TOKEN), ("ok", "wrong"), ("403", TOKEN), ("500", TOKEN), ("html", TOKEN)):
            FakeEngine.mode = mode
            os.environ[server.TOKEN_VAR] = token
            for tool in all_tools():
                tool()
        self.assertGreater(len(FakeEngine.methods), 10)
        self.assertEqual(set(FakeEngine.methods), {"GET"})

    def test_source_builds_requests_only_as_get(self):
        src = inspect.getsource(server)
        self.assertEqual(len(re.findall(r"urllib\.request\.Request\(", src)), 1)
        self.assertEqual(len(re.findall(r"urlopen\(", src)), 1)
        self.assertIn('method="GET"', inspect.getsource(server._get))
        self.assertNotRegex(src, r"\bdata\s*=")  # no request body anywhere

    def test_registered_tools_are_the_read_set(self):
        names = sorted(t.name for t in server.mcp._tool_manager.list_tools())
        self.assertEqual(names, ["checklists", "due", "ledger_transactions", "pantry_receipts", "places"])


if __name__ == "__main__":
    unittest.main()
