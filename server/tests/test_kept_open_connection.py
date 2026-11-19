"""One kept-open connection to the model service per worker process (perf/try-faster-first-word
fix 3). Measured before: every Gemini call (speech-to-text, each model round, each speech request)
opened a new TCP+TLS connection, ~60 to 90 ms each from the laptop, 3 to 6 calls in a row per
spoken turn. Now a connection read to its end is kept for the next call; one the server has closed
is replaced once, before anything was answered; one cut off, failed or abandoned is never reused.
No network: connections are fakes."""

from __future__ import annotations

import http.client

import pytest

from agents.runtime import model as runtime_model
from agents.runtime.model import ModelFailure, UrllibTransport

# The real methods, taken at import: `conftest._no_agent_model` replaces them for every test.
REAL_LINES = UrllibTransport.lines
REAL_REQUEST = UrllibTransport.request
URL = "https://generativelanguage.googleapis.com/v1beta/models/m:streamGenerateContent?alt=sse"


class Resp:
    def __init__(self, status=200, lines=(b"data: {}\n",), fail=None, will_close=False, body=b""):
        self.status = status
        self.lines = list(lines)
        self.fail = fail
        self.will_close = will_close
        self.body = body

    def __iter__(self):
        yield from self.lines
        if self.fail is not None:
            raise self.fail

    def read(self, size=-1):
        return self.body or b"".join(self.lines)


class Conn:
    """A connection that answers its script in order: a `Resp`, or an exception to raise."""

    def __init__(self, script):
        self.script = list(script)
        self.requests: list[tuple[str, str]] = []
        self.closed = False
        self.sock = None
        self.timeout = None

    def request(self, method, path, body=None, headers=None):
        self.requests.append((method, path))

    def getresponse(self):
        step = self.script.pop(0)
        if isinstance(step, BaseException):
            raise step
        return step

    def close(self):
        self.closed = True


@pytest.fixture
def net(monkeypatch):
    """Each new connection takes the next script from `net.scripts`; `net.made` lists them."""

    class Net:
        scripts: list[list] = []
        made: list[Conn] = []

    def connect(host, timeout):
        conn = Conn(Net.scripts.pop(0))
        Net.made.append(conn)
        return conn

    monkeypatch.setattr(UrllibTransport, "lines", REAL_LINES)
    monkeypatch.setattr(UrllibTransport, "request", REAL_REQUEST)
    monkeypatch.setattr(runtime_model, "_connect", connect)
    monkeypatch.setattr(runtime_model, "POOL", runtime_model._Pool())
    return Net


def _read(transport) -> list[bytes]:
    return list(transport.lines(URL, {}, b"{}", 5.0))


def test_two_calls_in_a_row_share_one_connection(net):
    net.scripts = [[Resp(), Resp()]]
    transport = UrllibTransport()
    assert _read(transport) == [b"data: {}\n"]
    assert _read(transport) == [b"data: {}\n"]
    assert len(net.made) == 1
    assert net.made[0].requests[0] == ("POST", "/v1beta/models/m:streamGenerateContent?alt=sse")


def test_a_connection_the_server_closed_is_replaced_once_before_any_answer(net):
    net.scripts = [[Resp(), http.client.RemoteDisconnected("closed")], [Resp()]]
    transport = UrllibTransport()
    _read(transport)
    assert _read(transport) == [b"data: {}\n"]
    assert len(net.made) == 2 and net.made[0].closed


def test_a_new_connection_that_fails_is_not_tried_again(net):
    net.scripts = [[ConnectionResetError("reset")]]
    with pytest.raises(ModelFailure, match="could not be reached"):
        _read(UrllibTransport())
    assert len(net.made) == 1 and net.made[0].closed


def test_a_body_cut_off_midway_fails_in_words_and_its_connection_is_dropped(net):
    net.scripts = [[Resp(fail=http.client.IncompleteRead(b"x"))], [Resp()]]
    transport = UrllibTransport()
    with pytest.raises(ModelFailure, match="stopped answering"):
        _read(transport)
    assert net.made[0].closed
    _read(transport)
    assert len(net.made) == 2


def test_an_abandoned_stream_never_hands_its_connection_on(net):
    # A cancelled turn stops reading mid-answer: the rest of that answer must not reach the next.
    net.scripts = [[Resp(lines=(b"data: 1\n", b"data: 2\n"))], [Resp()]]
    transport = UrllibTransport()
    stream = iter(transport.lines(URL, {}, b"{}", 5.0))
    next(stream)
    stream.close()
    assert net.made[0].closed
    _read(transport)
    assert len(net.made) == 2


def test_an_error_answer_says_googles_words_and_drops_the_connection(net):
    body = b'{"error": {"message": "API key not valid."}}'
    net.scripts = [[Resp(status=400, lines=(), body=body)]]
    with pytest.raises(ModelFailure, match=r"HTTP 400: API key not valid\."):
        _read(UrllibTransport())
    assert net.made[0].closed


def test_a_server_that_says_it_will_close_is_not_kept(net):
    net.scripts = [[Resp(will_close=True)], [Resp()]]
    transport = UrllibTransport()
    _read(transport)
    _read(transport)
    assert len(net.made) == 2


def test_a_long_idle_connection_is_not_reused(net, monkeypatch):
    clock = [100.0]
    monkeypatch.setattr(runtime_model, "POOL", runtime_model._Pool(clock=lambda: clock[0]))
    net.scripts = [[Resp()], [Resp()]]
    transport = UrllibTransport()
    _read(transport)
    clock[0] += runtime_model._Pool.IDLE_S + 1
    _read(transport)
    assert len(net.made) == 2 and net.made[0].closed


def test_a_plain_request_shares_the_pool_and_keeps_its_status(net):
    net.scripts = [[Resp(body=b'{"name": "c"}'), Resp(status=404)]]
    transport = UrllibTransport()
    assert transport.request("POST", URL, {}, b"{}", 5.0) == (200, b'{"name": "c"}')
    assert transport.request("GET", URL, {}, None, 5.0) == (404, b"")
    assert len(net.made) == 1
