"""A real-model check of the voice-first demo (ADR 0103). NOT part of the suite (pytest collects only
`test_*.py`); run it by name with a key, as `realrun_web_cascade.py`:

    LEGION_GEMINI_KEY_FILE=<path> REALRUN_OUT=<dir> pt.sh <worktree> <port> <db> \\
        tests/realrun_demo_voice_first.py -s

As a recruiter visitor (an invented content folder, `tests/test_recruiter_content.fake_content`):
- the opening: its first audio and how long it is;
- the tour, beat by beat: what was shown and said, first audio per beat;
- fillers: questions that need a tool, spoken; each filler heard names the tool that ran;
- speculation: `POST /api/assistant/transcribe` then the words as a spoken turn, against the audio
  turn, first audio each;
- a browser hanging up mid-reply (what a barge-in does to the engine): the turn stops.
Costs are ESTIMATES (`tools/voice_prices.json`); the run stops past `REALRUN_BUDGET_USD`.
"""

from __future__ import annotations

import base64
import json
import re
import time

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile

from assistant import voice_engine
from assistant import cascade, fillers
from assistant.companions import companion_for
from tests.realrun_web_cascade import QUESTIONS, _visitor, real  # noqa: F401 - fixture
from tests.test_recruiter_content import load_fake

pytestmark = [pytest.mark.django_db, pytest.mark.live_model]

QUESTIONS.update(
    {
        "vf_car": "When is the car due for service?",
        "vf_docs": "How does LEGION decide which agent answers?",
        "vf_spend": "What did this household spend on groceries last month?",
    }
)
FILLER_CASES = (("vf_car", "next_service"), ("vf_docs", None), ("vf_spend", None))


TAG_WORDS = re.compile(r"\b(chuckle|laugh|sigh|short pause|long pause|breath)\b", re.I)


def _stream(response, started):
    events, first_audio, audio_bytes = [], None, 0
    pcm = bytearray()
    for raw in response.streaming_content:
        for block in raw.decode().strip().split("\n\n"):
            if not block.strip():
                continue
            kind = block.split("\n")[0].removeprefix("event: ")
            data = json.loads(block.split("\n")[1].removeprefix("data: "))
            if kind == "audio":
                if first_audio is None:
                    first_audio = int((time.monotonic() - started) * 1000)
                chunk = base64.b64decode(data["pcm"])
                audio_bytes += len(chunk)
                if not data.get("filler"):
                    pcm += chunk
                data = {"filler": bool(data.get("filler"))}
            events.append((kind, data))
    events.append(("_pcm", bytes(pcm)))
    return events, first_audio, audio_bytes


def _post(real, client, label, **fields):
    real.check()
    if "audio" in fields:
        fields["audio"] = SimpleUploadedFile("u.pcm", fields["audio"], "application/octet-stream")
    started = time.monotonic()
    response = client.post("/api/assistant/turn", fields, format="multipart")
    assert response.status_code == 200, (label, response.status_code, getattr(response, "data", None))
    events, first_audio, audio_bytes = _stream(response, started)
    done = next((d for k, d in events if k == "done"), {})
    real.spent += float(done.get("cost_usd") or 0)
    row = {
        "case": label,
        "heard": next((d["text"] for k, d in events if k == "heard"), None),
        "fillers": [d for k, d in events if k == "filler"],
        "tools": [(d["name"], d["ok"], d.get("ms")) for k, d in events if k == "tool"],
        "said": " ".join(d["text"] for k, d in events if k == "sentence"),
        "first_audio_ms_wall": first_audio,
        "reply_audio_ms": audio_bytes // 48,
        "wall_ms": int((time.monotonic() - started) * 1000),
        "cost_usd": done.get("cost_usd"),
        "tour": done.get("tour"),
        "conversation_id": next((d["conversation_id"] for k, d in events if k == "turn"), None),
    }
    reply = next((d for k, d in events if k == "_pcm"), b"")
    row["_pcm"] = reply
    real._write({k: v for k, v in row.items() if k != "_pcm"})
    return row


def _heard_back(real, row) -> str:
    """The reply's own audio, transcribed back: a tag read aloud would show up as its word."""
    from tests.realrun_web_cascade import PRICE, _to_16k

    if not row.get("_pcm"):
        return ""
    back = real.speech.transcribe(_to_16k(row["_pcm"]))
    real.spent += (back.prompt_tokens * PRICE["text_in"] + back.output_tokens * PRICE["text_out"]) / 1e6
    return back.text


def test_realrun_demo_voice_first(real, tmp_path, monkeypatch):
    load_fake(tmp_path)
    visitor = _visitor()
    # Filler lines made once, as the process does after its first spoken turn.
    from demo.models import DemoVisit

    user = DemoVisit.objects.order_by("-id").first().user
    voice = voice_engine.voice_for(user)
    audio = cascade.FillerAudio(spawn=lambda work: work())
    audio.warm(real.speech, voice)
    monkeypatch.setattr(cascade, "FILLER_AUDIO", audio)

    opening = _post(real, visitor, "opening", greet="true")
    assert opening["said"] and "train" not in opening["said"].lower()
    cid = opening["conversation_id"]

    # The tour, as the page plays it.
    beat, beats = 0, []
    for _ in range(8):
        row = _post(real, visitor, f"tour-{beat}", tour=beat, conversation_id=cid)
        beats.append(row)
        nxt = (row["tour"] or {}).get("next")
        if nxt is None:
            break
        beat = nxt
    from assistant.tour import END_LINE

    from assistant.speech_tags import strip

    assert strip(END_LINE) in beats[-1]["said"], beats[-1]
    assert any(t[0] == "show_resume" for b in beats for t in b["tools"]), beats

    # Fillers say what ran.
    mismatched = []
    for key, _expect in FILLER_CASES:
        row = _post(real, visitor, f"filler-{key}", audio=real.audio_for(key), conversation_id=cid)
        ran = [t[0] for t in row["tools"]]
        for f in row["fillers"]:
            if f["tool"] not in ran or f["text"] not in fillers.lines_for(f["tool"]):
                mismatched.append((key, f, ran))
    assert not mismatched, mismatched

    # Speculation: the early transcript, then the words as a spoken turn; against the audio turn.
    q = real.audio_for("vf_car")
    started = time.monotonic()
    heard = visitor.post(
        "/api/assistant/transcribe",
        {"audio": SimpleUploadedFile("s.pcm", q, "application/octet-stream")},
        format="multipart",
    )
    transcribe_ms = int((time.monotonic() - started) * 1000)
    assert heard.status_code == 200, heard.data
    spec = _post(
        real, visitor, "speculated", text=heard.data["text"], spoken="true", conversation_id=cid
    )
    plain = _post(real, visitor, "unspeculated", audio=q, conversation_id=cid)
    real._write(
        {
            "case": "speculation",
            "transcribe_ms": transcribe_ms,
            "first_audio_after_end_spec_ms": spec["first_audio_ms_wall"],
            "first_audio_after_end_plain_ms": plain["first_audio_ms_wall"],
        }
    )

    # Expression tags: never read aloud, never on a money answer, never in what is shown.
    spoken_tags = []
    for row in (opening, beats[-1], spec):
        back = _heard_back(real, row)
        if TAG_WORDS.search(back):
            spoken_tags.append((row["case"], back))
        assert "<" not in row["said"], row["said"]
    money = _post(real, visitor, "tags-money", audio=real.audio_for("vf_spend"), conversation_id=cid)
    assert "<" not in money["said"]
    back = _heard_back(real, money)
    if TAG_WORDS.search(back):
        spoken_tags.append(("money", back))
    real._write({"case": "tags", "spoken_tag_words": spoken_tags, "money_heard_back": back[:200]})
    assert not spoken_tags, spoken_tags

    # A browser hanging up mid-reply (a barge-in): the engine stops the turn.
    real.check()
    response = visitor.post(
        "/api/assistant/turn",
        {
            "audio": SimpleUploadedFile("u.pcm", real.audio_for("vf_spend"), "application/octet-stream"),
            "conversation_id": cid,
        },
        format="multipart",
    )
    chunks = iter(response.streaming_content)
    seen = b""
    started = time.monotonic()
    while b"event: audio" not in seen:
        seen += next(chunks)
    response.close()
    real._write({"case": "hang_up", "closed_after_ms": int((time.monotonic() - started) * 1000)})
    print(f"VOICE-FIRST spent ${real.spent:.4f}")
