"""Content-Security-Policy for every response that has none (architecture review 2026-11-10, item 5).

**Modes**, `LEGION_CSP`: `on` sends `Content-Security-Policy`; `report` (the default) sends
`Content-Security-Policy-Report-Only`, so a source this list forgot shows in the browser
console and blocks nothing; `off` sends neither. Report is the default because no browser
pass on `/try`, books and the bank link has been made against this list yet; flip to `on`
after one (the report names the exact check).

**Why each source is here** (read from the frontend, 2026-11-10):
- `connect-src wss://generativelanguage.googleapis.com`: the web assistant and the public
  `/try` demo open Gemini Live from the browser (`assistant/live.py` WS_URL, handed to the
  page as `ws_url`; `frontend/src/assistant/deps.ts` opens it). Without it voice is dead.
- `https://cdn.plaid.com` (script, frame) and `https://*.plaid.com` (connect): Plaid Link
  (`frontend/src/lib/plaid-link.ts`), per Plaid's own CSP guidance.
- `frame-src 'self' blob:`: the `/try` report iframe (same origin, `/api/demo/asset/report`)
  and Readium's EPUB frames. `worker-src 'self' blob:`: the service worker and the audio
  capture worklet. `img-src`/`media-src` `blob: data:`: comic pages and covers decoded in
  the browser. `style-src 'unsafe-inline'`: React style attributes and Readium's injected
  styles.
- Inline scripts: the theme script in `index.html` is allowed by its own sha256, computed
  here from the HTML being sent, so editing that script never needs this file edited.
- `'wasm-unsafe-eval'`: pdf.js decodes some images with WebAssembly. No `'unsafe-eval'`.

A response that already carries a policy keeps its own (the report asset sets
`sandbox allow-scripts`, `demo/content_views.py`): two policies combine as the most
restrictive, and this one's `frame-ancestors 'none'` would stop `/try` framing it.
"""

from __future__ import annotations

import base64
import hashlib
import os
import re

HEADER = "Content-Security-Policy"
REPORT_HEADER = "Content-Security-Policy-Report-Only"

GEMINI_LIVE = "wss://generativelanguage.googleapis.com"
PLAID_CDN = "https://cdn.plaid.com"

_INLINE_SCRIPT = re.compile(rb"<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>", re.S | re.I)


def mode() -> str:
    raw = os.environ.get("LEGION_CSP", "").strip().lower()
    return raw if raw in {"on", "report", "off"} else "report"


def inline_script_hashes(html: bytes) -> list[str]:
    return [
        "'sha256-" + base64.b64encode(hashlib.sha256(body).digest()).decode() + "'"
        for body in _INLINE_SCRIPT.findall(html)
        if body.strip()
    ]


def policy(script_hashes: list[str] | None = None) -> str:
    script = ["'self'", "'wasm-unsafe-eval'", PLAID_CDN, "blob:", *(script_hashes or [])]
    directives = {
        "default-src": ["'self'"],
        "script-src": script,
        "style-src": ["'self'", "'unsafe-inline'"],
        "img-src": ["'self'", "data:", "blob:"],
        "media-src": ["'self'", "data:", "blob:"],
        "font-src": ["'self'", "data:"],
        "connect-src": ["'self'", GEMINI_LIVE, "https://*.plaid.com"],
        "frame-src": ["'self'", "blob:", PLAID_CDN],
        "worker-src": ["'self'", "blob:"],
        "manifest-src": ["'self'"],
        "object-src": ["'none'"],
        "base-uri": ["'self'"],
        "form-action": ["'self'"],
        "frame-ancestors": ["'none'"],
    }
    return "; ".join(f"{name} {' '.join(values)}" for name, values in directives.items())


class ContentSecurityPolicyMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        response = self.get_response(request)
        current = mode()
        if current == "off" or HEADER in response or REPORT_HEADER in response:
            return response
        hashes: list[str] = []
        content_type = response.get("Content-Type", "")
        if content_type.startswith("text/html") and not getattr(response, "streaming", False):
            hashes = inline_script_hashes(response.content)
        response[HEADER if current == "on" else REPORT_HEADER] = policy(hashes)
        return response
