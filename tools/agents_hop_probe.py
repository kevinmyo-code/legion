r"""Engine-brain ticket 01 (stage 0): the client-to-engine hop, cold start and SSE through Cloud
Run, measured from a real network. Run it on the LAPTOP, tethered to the phone's mobile hotspot
for a phone-like network (or on Wi-Fi for a second row). Stdlib only; prints timings only.

    python tools/agents_hop_probe.py --base https://<engine host>                 # warm hop x20
    python tools/agents_hop_probe.py --base https://<engine host> --cold          # first request is
                                                                                   # the cold one
    python tools/agents_hop_probe.py --base https://<engine host> --sse --token-file F   # SSE gaps
    python tools/agents_hop_probe.py --base https://<engine host> --turn-off      # the dark check

- **hop**: `GET /health` time to first byte (connect + TLS + Cloud Run + Django), `--n` times on
  one fresh connection each. Pass bar (plan 4.5): warm p50 <= 200 ms.
- **cold**: run after the service has had no request for 20+ minutes (min-instances 0); the first
  sample is the cold start, printed apart. It decides warm-at-wake (ADR 0095) vs min-instances 1.
- **sse**: `GET /api/agents/probe/sse` (a member's device token from `--token-file` or
  `LEGION_TOKEN_FILE`; the file is read, never printed): arrival gaps of its five events, sent
  200 ms apart. Pass bar: every gap within 50 ms of 200 ms (no buffering in front of Django).
- **turn-off**: `POST /api/agents/turn` with a member's token (`--token-file`) must answer 404
  "switched off" while `LEGION_AGENTS_TURN` is unset (with no token: 401/403, auth comes first).

Not measured here: the phone's own `turn_post_ms` / `first_event_ms` lines and a warm request at
wake (a phone change, ticket 04).
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import ssl
import statistics
import sys
import time
from urllib.parse import urlparse


def _conn(base: str):
    url = urlparse(base)
    if url.scheme == "https":
        return http.client.HTTPSConnection(
            url.netloc, timeout=30, context=ssl.create_default_context()
        )
    return http.client.HTTPConnection(url.netloc, timeout=30)


def ttfb(base: str, path: str, method: str = "GET", headers: dict | None = None, body=None):
    conn = _conn(base)
    t0 = time.perf_counter()
    conn.request(method, path, body=body, headers=headers or {})
    resp = conn.getresponse()
    first = (time.perf_counter() - t0) * 1000
    data = resp.read()
    conn.close()
    return first, resp.status, data


def pct(values, p):
    ordered = sorted(values)
    return ordered[max(0, min(len(ordered) - 1, round(p * (len(ordered) - 1))))]


def summary(name, values):
    return (
        f"{name}: n={len(values)} p50={pct(values, 0.5):.0f}ms p90={pct(values, 0.9):.0f}ms "
        f"min={min(values):.0f}ms max={max(values):.0f}ms mean={statistics.fmean(values):.0f}ms"
    )


def hop(base: str, n: int, cold: bool) -> int:
    samples = []
    for _ in range(n + (1 if cold else 0)):
        ms, status, _ = ttfb(base, "/health")
        if status != 200:
            print(f"hop: /health answered {status}", file=sys.stderr)
            return 1
        samples.append(ms)
    if cold:
        print(f"hop_cold_first_request: {samples[0]:.0f}ms")
        samples = samples[1:]
    print(summary("hop_warm_health_ttfb", samples))
    print(f"hop_bar warm_p50<=200ms: {'PASS' if pct(samples, 0.5) <= 200 else 'MISS'}")
    return 0


def sse(base: str, token_file: str) -> int:
    if not token_file or not os.path.isfile(token_file):
        print("sse: --token-file (or LEGION_TOKEN_FILE) must name a file holding a device token")
        return 2
    with open(token_file, encoding="utf-8") as fh:
        token = fh.read().strip()
    conn = _conn(base)
    t0 = time.perf_counter()
    conn.request("GET", "/api/agents/probe/sse", headers={"Authorization": f"Token {token}"})
    resp = conn.getresponse()
    if resp.status != 200:
        print(f"sse: answered {resp.status}")
        return 1
    arrivals = []
    while True:
        line = resp.fp.readline()
        if not line:
            break
        if line.startswith(b"data:"):
            arrivals.append((time.perf_counter() - t0) * 1000)
            json.loads(line[5:])
    conn.close()
    gaps = [b - a for a, b in zip(arrivals, arrivals[1:], strict=False)]
    print(f"sse_first_event: {arrivals[0]:.0f}ms" if arrivals else "sse: no events")
    print("sse_gaps_ms: " + ", ".join(f"{g:.0f}" for g in gaps))
    good = len(gaps) == 4 and all(abs(g - 200) <= 50 for g in gaps)
    print(f"sse_bar every gap 200+-50ms: {'PASS' if good else 'MISS'}")
    return 0


def turn_off(base: str, token_file: str) -> int:
    headers = {"Content-Type": "application/json"}
    if token_file and os.path.isfile(token_file):
        with open(token_file, encoding="utf-8") as fh:
            headers["Authorization"] = f"Token {fh.read().strip()}"
    else:
        print("turn_off: no --token-file; only the anonymous refusal (401/403) can be seen")
    _, status, data = ttfb(base, "/api/agents/turn", "POST", headers, b'{"transcript": "probe"}')
    if "Authorization" not in headers:
        print(f"turn_off: HTTP {status} (anonymous)")
        return 0 if status in (401, 403) else 1
    off = status == 404 and b"switched off" in data
    print(f"turn_off: HTTP {status} -> {'REFUSED as off' if off else 'NOT the off refusal'}")
    return 0 if off else 1


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--base", required=True)
    p.add_argument("--n", type=int, default=20)
    p.add_argument("--cold", action="store_true")
    p.add_argument("--sse", action="store_true")
    p.add_argument("--turn-off", action="store_true")
    p.add_argument("--token-file", default=os.environ.get("LEGION_TOKEN_FILE", ""))
    a = p.parse_args(argv)
    base = a.base.rstrip("/")
    if a.turn_off:
        return turn_off(base, a.token_file)
    if a.sse:
        return sse(base, a.token_file)
    return hop(base, a.n, a.cold)


if __name__ == "__main__":
    sys.exit(main())
