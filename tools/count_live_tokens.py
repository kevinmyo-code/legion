"""Count the Live setup payload's REAL tokens with Gemini's countTokens (architecture review
2026-11-10, item 7a).

`LiveSetupPayloadSizeTest` holds the setup under a ceiling estimated as chars/4. That estimate is
the CI proxy and stays one; this script is the on-demand check of what it approximates. The test
writes the exact JSON it measures (the tools array as `buildSetup` wraps it, plus the static half
of the system instruction) to `app/build/live-setup/setup.json`; this script sends that to
`models/<model>:countTokens` and prints the count beside the chars/4 estimate.

    ./gradlew :app:testDebugUnitTest --tests 'com.kevin.legion.service.LiveSetupPayloadSizeTest'
    GEMINI_API_KEY=... python tools/count_live_tokens.py
    python tools/count_live_tokens.py --key-file <file holding only the key> --model gemini-2.5-flash

What it does not count, same as the test: the safety block, the date/clock block and the live
context, which need a phone. The Live model itself may not serve countTokens; the default is a
text model, whose tokenizer the Live family shares, and the model used is printed.

The key comes from GEMINI_API_KEY or --key-file and is never printed. Run it on your own key.
It makes one HTTPS call and writes nothing.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SETUP = REPO / "app" / "build" / "live-setup" / "setup.json"
DEFAULT_MODEL = "gemini-2.5-flash"
ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:countTokens"


def load_setup(path: Path) -> dict:
    if not path.is_file():
        raise SystemExit(
            f"{path} is missing. Run LiveSetupPayloadSizeTest first (it writes the file)."
        )
    setup = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(setup.get("systemInstruction"), str) or not isinstance(
        setup.get("tools"), list
    ):
        raise SystemExit(f"{path} does not hold a systemInstruction string and a tools list.")
    return setup


def request_body(setup: dict, model: str) -> dict:
    """countTokens over a generateContentRequest carrying the setup's instruction and tools.

    `contents` must be non-empty; one one-character turn is the whole of it, and its few tokens
    are reported separately by subtracting a contents-only count."""
    return {
        "generateContentRequest": {
            "model": f"models/{model}",
            "systemInstruction": {"parts": [{"text": setup["systemInstruction"]}]},
            "tools": setup["tools"],
            "contents": [{"role": "user", "parts": [{"text": "."}]}],
        }
    }


def contents_only_body(model: str) -> dict:
    return {
        "generateContentRequest": {
            "model": f"models/{model}",
            "contents": [{"role": "user", "parts": [{"text": "."}]}],
        }
    }


def chars_estimate(setup: dict) -> int:
    """The test's own arithmetic: tools JSON chars plus instruction chars, over 4."""
    tools_chars = len(json.dumps(setup["tools"], separators=(",", ":"), ensure_ascii=False))
    return (tools_chars + len(setup["systemInstruction"])) // 4


def count(body: dict, model: str, key: str) -> int:
    request = urllib.request.Request(
        ENDPOINT.format(model=model),
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json", "x-goog-api-key": key},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return int(json.loads(response.read())["totalTokens"])
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:400]
        raise SystemExit(f"countTokens answered {exc.code}: {detail}") from exc


def read_key(args) -> str:
    if args.key_file:
        key = Path(args.key_file).read_text(encoding="utf-8").strip()
    else:
        key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key:
        raise SystemExit("No key: set GEMINI_API_KEY or pass --key-file. Use your own key.")
    return key


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--setup", default=str(SETUP))
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--key-file", default=None)
    args = parser.parse_args(argv)
    setup = load_setup(Path(args.setup))
    key = read_key(args)
    total = count(request_body(setup, args.model), args.model, key)
    overhead = count(contents_only_body(args.model), args.model, key)
    print(f"model            {args.model}")
    print(f"tool decls       {sum(len(t.get('functionDeclarations', [])) for t in setup['tools'])}")
    print(f"countTokens      {total - overhead} (setup only; {total} with the one-character turn)")
    print(f"chars/4 estimate {chars_estimate(setup)} (LiveSetupPayloadSizeTest's figure)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
