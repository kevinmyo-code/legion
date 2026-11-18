# ruff: noqa: F811 - the `real` fixture is imported from the cascade harness
"""A real-model check of the voice-first demo (ADR 0103). NOT part of the suite (pytest collects
only `test_*.py`); run it by name with a key, as `realrun_web_cascade.py`:

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
import os
import re
import time
from pathlib import Path

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile

from assistant import cascade, fillers, voice_engine
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
                    if not pcm:
                        events.append(
                            ("_answer_audio_ms", int((time.monotonic() - started) * 1000))
                        )
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
    assert response.status_code == 200, (
        label,
        response.status_code,
        getattr(response, "data", None),
    )
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
        "first_answer_audio_ms": next((d for k, d in events if k == "_answer_audio_ms"), None),
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
    real.spent += (
        back.prompt_tokens * PRICE["text_in"] + back.output_tokens * PRICE["text_out"]
    ) / 1e6
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
    from assistant.speech_tags import untagged as strip
    from assistant.tour import END_LINE

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
    money = _post(
        real, visitor, "tags-money", audio=real.audio_for("vf_spend"), conversation_id=cid
    )
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
            "audio": SimpleUploadedFile(
                "u.pcm", real.audio_for("vf_spend"), "application/octet-stream"
            ),
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


# ---- section 8.5: the utterance's audio straight to the text model (an experiment, not shipped) --

AUDIO_CASES = ("spend", "today", "car", "kevin", "arch", "vf_car", "vf_docs", "vf_spend")
TRANSCRIPT_ASK = (
    "(First write what they said, word for word, inside square brackets on its own line, then "
    "answer.)"
)


def _wav16k(pcm: bytes) -> bytes:
    import struct

    rate = 16_000
    header = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE"
    header += b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
    return header + b"data" + struct.pack("<I", len(pcm)) + pcm


def _first_round(model, req):
    """(first event ms, tool called or None, text, usage) for one model round."""
    from agents.runtime.model import TextDelta, ToolCallEvent, Usage

    started = time.monotonic()
    first, tool, text, usage = None, None, "", Usage()
    for item in model.stream(req):
        if first is None and isinstance(item, TextDelta | ToolCallEvent):
            first = int((time.monotonic() - started) * 1000)
        if isinstance(item, ToolCallEvent) and tool is None:
            tool = item.name
        elif isinstance(item, TextDelta):
            text += item.text
        elif isinstance(item, Usage):
            usage = item
    return first, tool, text, usage


def test_realrun_audio_in_experiment(real, household_user, monkeypatch):
    """Transcript-in (today's pipeline: speech-to-text, then the text model) against audio-in
    (the utterance's audio as the user part), on the standing cases, as a member. Writes one row
    per case: tool chosen each way, time to first model event, the speech-to-text time saved,
    the estimated cost each way, and whether a transcript came back with the audio-in answer."""
    import dataclasses

    from assistant import views
    from tests.realrun_web_cascade import PRICE, _member

    client = _member(household_user)
    captured = []
    real_clients = views._cascade_clients

    class Recording:
        def __init__(self, inner):
            self.inner = inner

        def stream(self, req):
            captured.append(req)
            yield from self.inner.stream(req)

        def __getattr__(self, name):
            return getattr(self.inner, name)

    def clients():
        model, speech = real_clients()
        return Recording(model), speech

    monkeypatch.setattr(views, "_cascade_clients", clients)
    rows = []
    for key in AUDIO_CASES:
        real.check()
        audio = real.audio_for(key)
        captured.clear()
        started = time.monotonic()
        stt = real.speech.transcribe(audio)
        stt_ms = int((time.monotonic() - started) * 1000)
        real.spent += (
            stt.prompt_tokens * PRICE["text_in"] + stt.output_tokens * PRICE["text_out"]
        ) / 1e6
        # The pipeline's first round, built by the real turn (text in), then sent again three ways.
        _post(real, client, f"audio-in-base-{key}", text=stt.text, spoken="true")
        if not captured:
            continue
        base = captured[0]
        model, _speech = real_clients()
        user = base.contents[-1]
        audio_part = {
            "inlineData": {
                "mimeType": "audio/wav",
                "data": base64.b64encode(_wav16k(audio)).decode(),
            }
        }
        text_parts = [p for p in user["parts"][:-1]]
        variants = {
            "transcript_in": base,
            "audio_in": dataclasses.replace(
                base,
                contents=[
                    *base.contents[:-1],
                    {"role": "user", "parts": [*text_parts, audio_part]},
                ],
            ),
            "audio_in_with_transcript": dataclasses.replace(
                base,
                contents=[
                    *base.contents[:-1],
                    {"role": "user", "parts": [*text_parts, audio_part, {"text": TRANSCRIPT_ASK}]},
                ],
            ),
        }
        row = {"case": key, "heard": stt.text, "stt_ms": stt_ms}
        for name, req in variants.items():
            first, tool, text, usage = _first_round(model, req)
            cached = min(usage.cached_tokens, usage.prompt_tokens)
            cost = (
                (usage.prompt_tokens - cached) * PRICE["text_in"]
                + cached * PRICE["text_cached"]
                + usage.output_tokens * PRICE["text_out"]
            ) / 1e6
            real.spent += cost
            row[name] = {
                "first_event_ms": first,
                "tool": tool,
                "text": text[:160],
                "prompt_tokens": usage.prompt_tokens,
                "cost_usd": round(cost, 6),
            }
        row["transcript_in"]["cost_usd"] = round(
            row["transcript_in"]["cost_usd"]
            + (stt.prompt_tokens * PRICE["text_in"] + stt.output_tokens * PRICE["text_out"]) / 1e6,
            6,
        )
        row["same_tool"] = row["audio_in"]["tool"] == row["transcript_in"]["tool"]
        row["transcript_returned"] = "[" in row["audio_in_with_transcript"]["text"]
        rows.append(row)
        real._write({"case": f"audio-in-{key}", **{k: v for k, v in row.items() if k != "heard"}})
    (Path(os.environ.get("REALRUN_OUT", "realrun-out")) / "audio-in.json").write_text(
        json.dumps(rows, indent=1), encoding="utf-8"
    )
    print(f"AUDIO-IN spent ${real.spent:.4f}")


def test_realrun_one_breath_against_per_sentence(real, monkeypatch):
    """Section 8.3: first audio with the reply spoken as its first words then one request, against
    one request per sentence, on the same questions, alternating so neither gets the warm cache."""
    load_and_visit = _visitor()
    rows = {"breath": [], "sentence": []}
    for key in ("vf_docs", "vf_car", "spend", "arch"):
        for mode in ("breath", "sentence", "sentence", "breath"):
            monkeypatch.setattr(cascade, "SPEECH_GROUPING", mode)
            row = _post(real, load_and_visit, f"grouping-{mode}-{key}", audio=real.audio_for(key))
            rows[mode].append(row["first_answer_audio_ms"])
    import statistics

    summary = {
        mode: {"median_first_audio_ms": statistics.median([v for v in vals if v is not None])}
        for mode, vals in rows.items()
    }
    real._write({"case": "grouping", **summary, "raw": rows})
    print("GROUPING", summary)


# ---- no speech never becomes words (2026-11-17, the Midcon self-talk) ----------------------------


def test_realrun_voice_map(real, household_user, monkeypatch):
    """One spoken member turn and one spoken visitor turn: the voice each asked the speech model
    for, whether Google took it (no fallback to Puck), and that audio came back."""
    from assistant import views
    from tests.realrun_web_cascade import _member

    real_clients = views._cascade_clients
    asked: list[str] = []
    speeches = []

    class Recording:
        def __init__(self, inner):
            self.inner = inner

        def speak(self, text, voice, spoken):
            asked.append(voice)
            yield from self.inner.speak(text, voice, spoken)

        def __getattr__(self, name):
            return getattr(self.inner, name)

    def clients():
        model, speech = real_clients()
        speeches.append(speech)
        return model, Recording(speech)

    monkeypatch.setattr(views, "_cascade_clients", clients)
    rows = []
    for role, client, want in (
        ("member", _member(household_user), voice_engine.MEMBER_VOICE_DEFAULT),
        ("visitor", _visitor(), "Puck"),
    ):
        asked.clear()
        row = _post(real, client, f"voice_{role}", audio=real.audio_for("vf_car"))
        refused = sorted(set().union(*(s.bad_voices for s in speeches)))
        rows.append(
            {
                "role": role,
                "want": want,
                "asked": sorted(set(asked)),
                "refused": refused,
                "reply_audio_ms": row["reply_audio_ms"],
                "said": row["said"][:160],
            }
        )
    real._write({"case": "voice_map", "rows": rows})
    print("VOICE MAP", json.dumps(rows))
    for r in rows:
        assert r["asked"] and set(r["asked"]) <= {r["want"]}, r
        assert not r["refused"], r
        assert r["reply_audio_ms"] > 0, r


def _noise_clips(real) -> dict[str, bytes]:
    import math
    import random
    import struct

    from tests.test_no_speech import NOISE, _pcm

    rate = 16_000
    rnd = random.Random(9)
    room = [rnd.gauss(0, 120) + 90 * math.sin(2 * math.pi * 60 * i / rate) for i in range(rate * 2)]
    tts = real.audio_for("vf_car")
    count = len(tts) // 2
    samples = struct.unpack(f"<{count}h", tts[: count * 2])
    echo = struct.pack(f"<{count}h", *(int(v * 0.05) for v in samples))
    clips = {"digital_silence": b"\x00\x00" * rate * 2}
    clips.update({name: _pcm(make()) for name, make in NOISE.items()})
    clips["room_tone"] = _pcm(room)
    clips["tts_echo_5pct"] = echo
    return clips


def test_realrun_no_speech_never_becomes_words(real):
    """8 no-speech clips x 3 runs through the engine's transcriber (gate included) give no text;
    the same clips sent to the model with the gate skipped show what the gate prevents; 4 real
    synthesised questions still come back right."""
    from assistant import speech as speech_module

    rows = []
    for name, pcm in _noise_clips(real).items():
        for run in range(3):
            real.check()
            heard = real.speech.transcribe(pcm)
            row = {"clip": name, "run": run, "text": heard.text, "paid": heard.prompt_tokens > 0}
            if run == 0:
                # The model alone, gate skipped: what reaching it would have produced.
                ungated = real.speech._transcribe_once.__func__
                gate = speech_module.voiced_speech
                loud = speech_module.LOUDNESS_GATE_RMS
                try:
                    speech_module.voiced_speech = lambda _pcm: True
                    speech_module.LOUDNESS_GATE_RMS = 0
                    raw = ungated(real.speech, pcm)
                finally:
                    speech_module.voiced_speech = gate
                    speech_module.LOUDNESS_GATE_RMS = loud
                real.spent += (raw.prompt_tokens * 0.30 + raw.output_tokens * 2.50) / 1e6
                row["model_alone"] = raw.text
            rows.append(row)
            real._write({"case": "no-speech", **row})
    questions = []
    for key in ("spend", "car", "vf_docs", "vf_car"):
        heard = real.speech.transcribe(real.audio_for(key))
        want = set(re.findall(r"[a-z']+", QUESTIONS[key].lower()))
        got = set(re.findall(r"[a-z']+", heard.text.lower()))
        overlap = len(want & got) / max(len(want), 1)
        questions.append({"key": key, "heard": heard.text, "overlap": round(overlap, 2)})
        real._write({"case": "question", **questions[-1]})
    (Path(os.environ.get("REALRUN_OUT", "realrun-out")) / "no-speech.json").write_text(
        json.dumps({"noise": rows, "questions": questions}, indent=1), encoding="utf-8"
    )
    assert all(r["text"] == "" for r in rows), [r for r in rows if r["text"]]
    assert all(q["overlap"] >= 0.8 for q in questions), questions
