"""A stand-in for the engine's `/api/claude/` worker endpoints (map "The API" table), for tests.

Real HTTP on 127.0.0.1 so the worker's urllib client is exercised end to end.
"""
from __future__ import annotations

import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = "test-device-key"


class FakeEngine:
    def __init__(self):
        self.lock = threading.Lock()
        self.queued: list[dict] = []
        self.heartbeats: list[dict] = []
        self.finishes: list[tuple[int, dict]] = []
        self.uploads: list[tuple[int, str, bytes]] = []
        self.cancel_requested: list[int] = []
        self.files_requested: list[dict] = []
        self.sessions: list[dict] = []
        self.deliver: dict[int, str | None] = {}
        self.delay = 0.0  # seconds to stall each request (timeout tests)
        self.down = False
        self.requests = 0
        # memory-review ticket 16: `/api/member-memories/review/` stand-ins.
        self.review_context: dict | None = None
        self.review_submits: list[dict] = []
        self.review_answer: dict = {"words": "1 merged, 2 to check.", "mode": "shadow",
                                    "counts": {"applied": 0, "proposed": 2, "refused": 0,
                                               "stale": 0}, "verdicts": []}
        self.review_status = 200
        self.review_runs = 0
        # Worker workflows (ticket 05): catalogs received, the hash held and echoed, and whether to
        # echo it at all (an engine deployed before workflows does not).
        self.catalogs: list[list] = []
        self.workflows_hash = ""
        self.echo_hash = True
        engine = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _reply(self, code, obj=None):
                body = b"" if obj is None else json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                with engine.lock:
                    engine.requests += 1
                    if engine.down:
                        return self._reply(503, {"detail": "down"})
                    if self.headers.get("Authorization") != f"Token {TOKEN}":
                        return self._reply(401, {"detail": "Invalid token."})
                    if self.path.startswith("/api/member-memories/review/context"):
                        if engine.review_status != 200:
                            return self._reply(engine.review_status, {"detail": "The review is off."})
                        return self._reply(200, engine.review_context or {})
                    return self._reply(404, {"detail": "no route"})

            def do_POST(self):
                n = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(n) if n else b""
                if engine.delay:
                    import time
                    time.sleep(engine.delay)
                with engine.lock:
                    engine.requests += 1
                    if engine.down:
                        return self._reply(503, {"detail": "down"})
                    if self.headers.get("Authorization") != f"Token {TOKEN}":
                        return self._reply(401, {"detail": "Invalid token."})
                    p = self.path
                    if p == "/api/claude/machines/heartbeat/":
                        body = json.loads(raw)
                        engine.heartbeats.append(body)
                        if (engine.echo_hash and isinstance(body.get("workflows"), list)
                                and body.get("workflows_hash") != engine.workflows_hash):
                            engine.catalogs.append(body["workflows"])
                            engine.workflows_hash = body["workflows_hash"]
                        out = {"cancel_requested": list(engine.cancel_requested),
                               "files_requested": list(engine.files_requested)}
                        if engine.echo_hash:
                            out["workflows_hash"] = engine.workflows_hash
                            out["workflows_dropped"] = 0
                        engine.cancel_requested.clear()
                        return self._reply(200, out)
                    if p == "/api/claude/tasks/claim/":
                        if not engine.queued:
                            return self._reply(204)
                        out, engine.queued = engine.queued, []
                        return self._reply(200, out)
                    if p == "/api/member-memories/review/changes":
                        engine.review_submits.append(json.loads(raw))
                        return self._reply(engine.review_status, engine.review_answer)
                    if p == "/api/member-memories/review/run":
                        engine.review_runs += 1
                        return self._reply(200, {"task": 9, "words": "Queued a memory review."})
                    if p == "/api/claude/sessions/":
                        engine.sessions.append(json.loads(raw))
                        return self._reply(200, {"ok": True})
                    m = re.match(r"^/api/claude/tasks/(\d+)/finish/$", p)
                    if m:
                        tid = int(m.group(1))
                        body = json.loads(raw)
                        engine.finishes.append((tid, body))
                        # Like the engine (ticket 09): a page of a `deliver: phone` task is
                        # requested at finish; file ids are 1000 + task*10 + index.
                        page = engine.deliver.get(tid) == "phone"
                        # ADR 0080: every found file of a `deliver: files` task that finished
                        # done (the worker already turned an over-the-caps find into a question).
                        files_too = (engine.deliver.get(tid) == "files"
                                     and body.get("status") == "done")
                        files = [{"id": 1000 + tid * 10 + i, "name": f["name"], "size": f["size"],
                                  "requested": files_too or (
                                      page and f["name"].lower().endswith((".html", ".htm"))),
                                  "uploaded": False}
                                 for i, f in enumerate(body.get("found") or [])]
                        return self._reply(200, {"id": tid, "status": body.get("status"),
                                                 "files": files, "detail": "Recorded."})
                    m = re.match(r"^/api/claude/tasks/(\d+)/files/$", p)
                    if m:
                        fid = re.search(rb'name="file_id"\r\n\r\n([^\r]*)', raw).group(1).decode()
                        content = raw.split(b"\r\n\r\n", 2)[2].rsplit(b"\r\n--", 1)[0]
                        engine.uploads.append((int(m.group(1)), fid, content))
                        engine.files_requested = [f for f in engine.files_requested
                                                  if str(f["file_id"]) != fid]
                        return self._reply(201, {"id": fid})
                    return self._reply(404, {"detail": "no route"})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def queue(self, **task):
        task.setdefault("parent", None)
        task.setdefault("machine", None)
        with self.lock:
            self.deliver[int(task["id"])] = task.get("deliver")
            self.queued.append(task)

    def finished(self, tid: int):
        with self.lock:
            return [p for i, p in self.finishes if i == tid]

    def close(self):
        self.server.shutdown()
        self.server.server_close()
