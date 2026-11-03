r"""Print a readable timeline of what was said and done in each Live voice session.

CAPTURE FILTER: the capture below filters by tag, so a tag left off the `logcat -s` list is simply
absent and the script cannot tell it from "never happened". LIVE_BARGE_IN and LIVE_TURN must be in
it, or the barge-in block is empty:

    adb logcat -v threadtime -s LIVE_AUDIT LIVE_LATENCY LIVE_FOLLOWUP LIVE_TOOL_CANCEL LIVE_BARGE_IN LIVE_TURN > live.txt

The phone writes LIVE_AUDIT lines from GeminiLiveSession through LiveAudit (async-lookups ticket 05;
the "Conversation log" row in Assistant settings; its logcat switch is default on, and conversation-records
ticket 03 also spools these lines and uploads them to the engine as reason=conversation). One line per event,
the free-text field always last, JSON-quoted, capped at 400 characters with a "..." marker:

    session_start model=<id> async_set=<a,b|none>
    session_end reason="<text>"
    user final=<bool> [typed=true] text="<inputTranscription chunk, or the whole turn when final>"
    assistant text="<outputTranscription chunk>"
    tool_call id=<id> name=<name> async=<bool> args=<json>
    tool_result id=<id> name=<name> success=<bool> ms=<n> scheduling=<WHEN_IDLE|none> sent=<bool>
        response=<json>
    tool_cancelled id=<id> name=<name>
    turn_complete | interrupted source=<server|barge_in> | activity_start | activity_end
    mic_open reason=<first_open|turn_handback> | mic_closed reason=<half_duplex|park|session_end|superseded>
    nudge reason=<still_checking|late_holding_line|empty_holding_line> tool=<name|none> [after_ms=<n>]

A nudge is a system note the phone sent the model (async-lookups ticket 07): still_checking when a
lookup has run 4 s (and again at 12 s), late_holding_line when a turn after a lookup's result was only
a holding line, empty_holding_line when a turn was only a holding line with no tool behind it.

A turn that ran a mail tool (LiveToolbox.EPISODIC_EXCLUDED_TOOLS) logs its assistant speech as one
text="[redacted: third-party content]" line, and that tool's result as
response="[redacted: third-party content]" (CLAUDE.md sec 7). The user's own words always log.

Timing lines come from LIVE_LATENCY (see tools/voice_latency.py): eot, reply, eot_reply, route,
investigate, cold start, sub-agent fallbacks. LIVE_TOOL_CANCEL lines are shown as they are.
LIVE_FOLLOWUP lines and activity/mic events are shown with --verbose. LIVE_TURN lines (turn-taking
config and fallbacks) are shown as they are.

LIVE_BARGE_IN lines come from TurnTaker (call-mode barge-in, ADR 0064), one short line each:

    armed=<bool> disabled=<bool>                     a reply started playing
    fire prob= level= echo_floor= threshold= sustain_ms=
    verdict self_interrupt=<bool> speech_after_ms= fire_level= fire_threshold= self_interrupts= disabled=
    reply_end fired=<bool> windows= speech_windows= loud_windows= qualifying_windows= max_prob=
        max_level= longest_run_ms= best_span= echo_floor= threshold=

best_span (2026-10-28) is the most qualifying windows seen in any 512 ms span; the gate fires at 8.
Older captures lack it, and every line here reads them as before.

--summary adds a barge-in block. Each reply that did not fire is put down to one reason:
(d) unheard: windows=0, the gate never listened (not armed, mic not fed, or not playing);
(c) choppy: some windows qualified but never 8 (256 ms) within a 512 ms span ("best span N/8"
    when the capture has best_span);
(b) unsure: Silero never reached 0.8 (speech_windows=0);
(a) quiet: Silero heard speech, but never above the echo threshold in the same window.
"armed lines" fewer or more than "replies" means a reply start or end went unlogged.

Capture on the host (the full tag list above), then read:

    adb logcat -v threadtime -s LIVE_AUDIT LIVE_LATENCY LIVE_FOLLOWUP LIVE_TOOL_CANCEL LIVE_BARGE_IN LIVE_TURN > live.txt
    python tools/voice_audit.py live.txt
    python tools/voice_audit.py live.txt --summary

Holding line (--summary): an ASSISTANT line that ends before a `TOOL <-` of a tool called in the
same turn, i.e. what was said while a lookup ran. Transcript chunks of one line that a timing line
split ("Checking the" / eot_reply / "records.") are merged first, so they count once.

Output is forced to UTF-8: a Windows console defaults to cp1252, and a transcript can carry any
character (a capture once crashed the script on U+2691).

Stdlib only. Self-test: python -m doctest tools/voice_audit.py

>>> sample = r'''
... 10-28 16:20:00.000  1234  5678 I LIVE_AUDIT: session_start model=gemini-3.8-live async_set=ask_body,ask_fleet,ask_goals,ask_pantry
... 10-28 16:20:00.900  1234  5678 I LIVE_LATENCY: cold_start_ms=900
... 10-28 16:20:01.000  1234  5678 I LIVE_AUDIT: mic_open reason=first_open
... 10-28 16:20:02.000  1234  5678 I LIVE_AUDIT: activity_start
... 10-28 16:20:03.704  1234  5678 I LIVE_LATENCY: eot_ms=704 eot_reason=base eot_longest_silence_ms=704 st_checks=1 st_scores=none
... 10-28 16:20:03.705  1234  5678 I LIVE_AUDIT: activity_end
... 10-28 16:20:03.900  1234  5679 I LIVE_AUDIT: user final=false text=" Any trouble"
... 10-28 16:20:03.950  1234  5679 I LIVE_AUDIT: user final=false text=" codes?"
... 10-28 16:20:04.100  1234  5679 I LIVE_AUDIT: tool_call id=c1 name=ask_fleet async=true args={"question":"any trouble codes?"}
... 10-28 16:20:04.300  1234  5679 I LIVE_AUDIT: assistant text="One"
... 10-28 16:20:04.350  1234  5679 I LIVE_AUDIT: assistant text=" moment."
... 10-28 16:20:04.379  1234  5679 I LIVE_LATENCY: eot_reply_ms=674
... 10-28 16:20:04.400  1234  5679 I LIVE_AUDIT: mic_closed reason=half_duplex
... 10-28 16:20:04.600  1234  5679 I LIVE_AUDIT: user final=true text="Any trouble codes?"
... 10-28 16:20:04.601  1234  5679 I LIVE_AUDIT: turn_complete
... 10-28 16:20:05.131  1234  5680 I LIVE_LATENCY: route_ms=1031 route_post_ms=600 route_tools_ms=400 route_tool_count=1 route_tools=get_codes tool=ask_fleet model=gemini-3.5-flash-lite result=Success
... 10-28 16:20:05.142  1234  5680 I LIVE_LATENCY: tool_ms=1042 tool=ask_fleet sent=true async=true
... 10-28 16:20:05.142  1234  5680 I LIVE_AUDIT: tool_result id=c1 name=ask_fleet success=true ms=1042 scheduling=WHEN_IDLE sent=true response={"success":true,"answer":"No stored codes."}
... 10-28 16:20:05.800  1234  5679 I LIVE_AUDIT: assistant text="No trouble codes stored."
... 10-28 16:20:06.900  1234  5679 I LIVE_AUDIT: turn_complete
... 10-28 16:20:08.000  1234  5679 I LIVE_AUDIT: user final=true text="Read my last email."
... 10-28 16:20:08.100  1234  5679 I LIVE_AUDIT: tool_call id=m1 name=read_mail async=false args={"index":0}
... 10-28 16:20:08.400  1234  5680 I LIVE_AUDIT: tool_result id=m1 name=read_mail success=true ms=300 scheduling=none sent=true response="[redacted: third-party content]"
... 10-28 16:20:08.600  1234  5679 I LIVE_AUDIT: assistant text="[redacted: third-party content]"
... 10-28 16:20:08.674  1234  5679 I LIVE_LATENCY: reply_ms=674
... 10-28 16:20:09.000  1234  5679 I LIVE_AUDIT: interrupted source=server
... 10-28 16:20:09.100  1234  5679 I LIVE_AUDIT: turn_complete
... 10-28 16:20:12.000  1234  5679 I LIVE_FOLLOWUP: closed after silence; msSinceLocalSpeech=8000 peakSinceMicOpen=120
... 10-28 16:20:12.100  1234  5679 I LIVE_AUDIT: session_end reason="stopped"
... '''.splitlines()
>>> for line in timeline(sample): print(line)
== session 1  model=gemini-3.8-live  async=ask_body,ask_fleet,ask_goals,ask_pantry
16:20:00.000  session start
16:20:00.900  cold start 900ms
16:20:03.704  eot 704ms
16:20:03.900  USER: Any trouble codes?
16:20:04.100  TOOL -> ask_fleet({"question":"any trouble codes?"}) [async]
16:20:04.300  ASSISTANT: One moment.
16:20:04.379  eot_reply 674ms
16:20:04.601  turn complete
16:20:05.131  route ask_fleet 1031ms
16:20:05.142  TOOL <- ask_fleet ok 1042ms [async]
                  = {"success":true,"answer":"No stored codes."}
16:20:05.800  ASSISTANT: No trouble codes stored.
16:20:06.900  turn complete
16:20:08.000  USER: Read my last email.
16:20:08.100  TOOL -> read_mail({"index":0})
16:20:08.400  TOOL <- read_mail ok 300ms [redacted]
16:20:08.600  ASSISTANT: [redacted]
16:20:08.674  reply 674ms
16:20:09.000  interrupted (server)
16:20:09.100  turn complete
16:20:12.100  session end (stopped)
>>> for line in summary(sample): print(line)
== session 1  model=gemini-3.8-live
turns: 3
user lines: 2
tools called: ask_fleet x1, read_mail x1
async tools: ask_fleet
holding lines: 1
  16:20:04.300  One moment.  (before ask_fleet returned)

Split chunks count as one holding line, and nudges are counted with what followed them:

>>> sample2 = r'''
... 10-28 11:27:44.000  1234  5678 I LIVE_AUDIT: session_start model=gemini-3.8-live async_set=ask_fleet
... 10-28 11:27:44.824  1234  5679 I LIVE_AUDIT: tool_call id=c2 name=ask_fleet async=true args={}
... 10-28 11:27:44.900  1234  5679 I LIVE_AUDIT: assistant text="Checking the"
... 10-28 11:27:44.901  1234  5679 I LIVE_LATENCY: eot_reply_ms=946
... 10-28 11:27:45.100  1234  5679 I LIVE_AUDIT: assistant text=" records."
... 10-28 11:27:45.200  1234  5679 I LIVE_AUDIT: turn_complete
... 10-28 11:27:48.900  1234  5679 I LIVE_AUDIT: nudge reason=still_checking tool=ask_fleet after_ms=4076
... 10-28 11:27:48.901  1234  5679 I LIVE_LATENCY: nudge_ms=4076 tool=ask_fleet
... 10-28 11:27:49.700  1234  5679 I LIVE_AUDIT: assistant text="Still at it."
... 10-28 11:27:50.300  1234  5679 I LIVE_AUDIT: turn_complete
... 10-28 11:27:53.900  1234  5680 I LIVE_AUDIT: tool_result id=c2 name=ask_fleet success=true ms=9076 scheduling=WHEN_IDLE sent=true response={"success":true}
... 10-28 11:27:54.700  1234  5679 I LIVE_AUDIT: assistant text="Checking."
... 10-28 11:27:55.500  1234  5679 I LIVE_AUDIT: turn_complete
... 10-28 11:27:55.510  1234  5679 I LIVE_AUDIT: nudge reason=late_holding_line tool=ask_fleet
... 10-28 11:27:57.000  1234  5679 I LIVE_AUDIT: session_end reason="stopped"
... '''.splitlines()
>>> [l for l in timeline(sample2) if "NUDGE" in l]
['11:27:48.900  NUDGE still_checking ask_fleet after 4076ms', '11:27:55.510  NUDGE late_holding_line ask_fleet']
>>> for line in summary(sample2): print(line)
== session 1  model=gemini-3.8-live
turns: 3
user lines: 0
tools called: ask_fleet x1
async tools: ask_fleet
holding lines: 1
  11:27:44.900  Checking the records.  (before ask_fleet returned)
nudges: 2 (followed by a line: 1)
  11:27:48.900  still_checking ask_fleet after 4076ms -> Still at it.
  11:27:55.510  late_holding_line ask_fleet -> (no line)

A capture taken with --verbose keeps the mic and follow-up lines:

>>> [l for l in timeline(sample, verbose=True) if "mic" in l or "followup" in l]
['16:20:01.000  mic open (first_open)', '16:20:04.400  mic closed (half_duplex)', '16:20:12.000  followup: closed after silence; msSinceLocalSpeech=8000 peakSinceMicOpen=120']

Quoted text survives quotes, backslashes and truncation markers:

Barge-in lines render short in the timeline, and --summary says why no reply fired:

>>> sample3 = r'''
... 10-28 18:00:00.000  1234  5678 I LIVE_AUDIT: session_start model=gemini-3.8-live async_set=none
... 10-28 18:00:00.100  1234  5678 I LIVE_TURN: config manual=true bargeIn=true smartTurn=true (local end of turn, barge-in on phone_speaker)
... 10-28 18:00:02.000  1234  5679 I LIVE_BARGE_IN: armed=true disabled=false
... 10-28 18:00:02.500  1234  5679 I LIVE_AUDIT: assistant text="Here is the weather."
... 10-28 18:00:05.000  1234  5679 I LIVE_AUDIT: turn_complete
... 10-28 18:00:06.000  1234  5679 I LIVE_BARGE_IN: reply_end fired=false windows=120 speech_windows=14 loud_windows=0 qualifying_windows=0 max_prob=0.97 max_level=9120 longest_run_ms=0 echo_floor=8400 threshold=12600
... 10-28 18:00:06.100  1234  5678 I LIVE_AUDIT: session_end reason="stopped"
... '''.splitlines()
>>> for line in timeline(sample3): print(line)
== session 1  model=gemini-3.8-live  async=none
18:00:00.000  session start
18:00:00.100  turn: config manual=true bargeIn=true smartTurn=true (local end of turn, barge-in on phone_speaker)
18:00:02.000  barge armed
18:00:02.500  ASSISTANT: Here is the weather.
18:00:05.000  turn complete
18:00:06.000  barge reply_end no fire: 120 win, speech 14, loud 0, both 0, max p0.97 L9120/T12600, run 0ms
18:00:06.100  session end (stopped)
>>> for line in summary(sample3): print(line)
== session 1  model=gemini-3.8-live
turns: 1
user lines: 0
tools called: none
async tools: none
holding lines: 0
barge-in: replies 1 (armed lines 1), fires 0, self-interrupts 0
  not fired: 1  max level 9120 vs threshold 12600  max prob 0.97  longest run 0ms
  why: (a) quiet x1

A capture with best_span (2026-10-28) shows it, and a choppy reply says how close it came:

>>> sample4 = r'''
... 10-28 19:00:00.000  1234  5678 I LIVE_AUDIT: session_start model=gemini-3.8-live async_set=none
... 10-28 19:00:01.000  1234  5679 I LIVE_BARGE_IN: armed=true disabled=false
... 10-28 19:00:13.000  1234  5679 I LIVE_BARGE_IN: reply_end fired=false windows=394 speech_windows=53 loud_windows=39 qualifying_windows=27 max_prob=1.00 max_level=30560 longest_run_ms=224 best_span=7 echo_floor=543 threshold=3000
... '''.splitlines()
>>> [line for line in timeline(sample4) if "reply_end" in line]
['19:00:13.000  barge reply_end no fire: 394 win, speech 53, loud 39, both 27, max p1.00 L30560/T3000, run 224ms, best span 7/8']
>>> for line in summary(sample4)[-2:]: print(line)
  not fired: 1  max level 30560 vs threshold 3000  max prob 1.00  longest run 224ms  best span 7/8
  why: (c) choppy: best span 7/8 x1

>>> parse_audit('user final=true text="He said \\"no\\" \\\\ ok..."')
('user', {'final': 'true', 'text': 'He said "no" \\ ok...'})
>>> parse_audit('tool_result id=x name=ask_goals success=false ms=-1 scheduling=none sent=false response={"a": 1}')[1]['response']
'{"a": 1}'
"""

import argparse
import json
import re
import sys
from collections import Counter

AUDIT = "LIVE_AUDIT"
BARGE = "LIVE_BARGE_IN"
# BargeInGate.SUSTAIN_WINDOWS: qualifying windows within a 512 ms span that fire.
SUSTAIN_WINDOWS = 8
TAGS = ("LIVE_AUDIT", "LIVE_LATENCY", "LIVE_FOLLOWUP", "LIVE_TOOL_CANCEL", BARGE, "LIVE_TURN")
REDACTED = "[redacted: third-party content]"

# threadtime: "10-28 16:20:00.000  1234  5678 I LIVE_AUDIT: message"
_LINE = re.compile(
    r"^(?P<date>\d\d-\d\d)\s+(?P<time>\d\d:\d\d:\d\d\.\d{3})\s+\d+\s+\d+\s+[VDIWEF]\s+"
    r"(?P<tag>[A-Z_]+)\s*:\s?(?P<msg>.*)$"
)
_KEY = re.compile(r"\s*(\w+)=")
_PAIR = re.compile(r"(\w+)=(\S+)")
# Keys whose value runs to the end of the line (always the last field).
_REST = ("args", "response")
_DECODER = json.JSONDecoder()


def parse_audit(msg):
    """(event, fields) for one LIVE_AUDIT message. Quoted values are JSON-decoded."""
    event, _, rest = msg.strip().partition(" ")
    fields = {}
    pos = 0
    while pos < len(rest):
        m = _KEY.match(rest, pos)
        if not m:
            break
        key, pos = m.group(1), m.end()
        if rest.startswith('"', pos):
            try:
                value, pos = _DECODER.raw_decode(rest, pos)
            except ValueError:
                value, pos = rest[pos:], len(rest)
        elif key in _REST:
            value, pos = rest[pos:], len(rest)
        else:
            end = rest.find(" ", pos)
            end = len(rest) if end < 0 else end
            value, pos = rest[pos:end], end
        fields[key] = value
    return event, fields


def _records(lines):
    """Time-ordered (date, time, tag, msg) for the four tags; anything else is skipped."""
    out = []
    for line in lines:
        m = _LINE.match(line.rstrip("\r\n"))
        if m and m.group("tag") in TAGS:
            out.append((m.group("date"), m.group("time"), m.group("tag"), m.group("msg")))
    out.sort(key=lambda r: (r[0], r[1]))  # stable: same-millisecond lines keep capture order
    return out


def _clean(text):
    return re.sub(r"\s+", " ", text).strip()


def _timing(msg):
    """One LIVE_LATENCY line as a short timing line, or None for lines the timeline leaves out."""
    p = dict(_PAIR.findall(msg))
    if "route_ms" in p:
        return f"route {p.get('tool', '?')} {p['route_ms']}ms"
    if "investigate_ms" in p:
        return f"investigate {p.get('tool', '?')} {p['investigate_ms']}ms"
    if msg.startswith("subagent_fallback"):
        return f"fallback {p.get('tool', '?')} {p.get('from')} -> {p.get('to')} ({p.get('reason')})"
    for key, label in (("eot_ms", "eot"), ("eot_reply_ms", "eot_reply"), ("reply_ms", "reply"),
                       ("cold_start_ms", "cold start"), ("ask_engine_ms", "ask_engine")):
        if key in p:
            return f"{label} {p[key]}ms"
    return None  # tool_ms (the TOOL <- line has it), st_score and the rest


def _barge(msg):
    """One LIVE_BARGE_IN line as a short timeline line."""
    p = dict(_PAIR.findall(msg))
    if msg.startswith("armed="):
        if p.get("armed") == "true":
            return "barge armed"
        return f"barge NOT armed (disabled={p.get('disabled', '?')})"
    if msg.startswith("fire"):
        return (f"BARGE-IN fire level={p.get('level')} threshold={p.get('threshold')} "
                f"prob={p.get('prob')} sustain={p.get('sustain_ms')}ms")
    if msg.startswith("verdict"):
        who = "SELF-INTERRUPT (echo)" if p.get("self_interrupt") == "true" else "user"
        off = " -> barge-in OFF" if p.get("disabled") == "true" else ""
        return f"barge verdict {who} speech_after={p.get('speech_after_ms')}ms{off}"
    if msg.startswith("reply_end"):
        head = "fired" if p.get("fired") == "true" else "no fire"
        return (f"barge reply_end {head}: {p.get('windows')} win, speech {p.get('speech_windows')}, "
                f"loud {p.get('loud_windows')}, both {p.get('qualifying_windows')}, "
                f"max p{p.get('max_prob')} L{p.get('max_level')}/T{p.get('threshold')}, "
                f"run {p.get('longest_run_ms')}ms{_span(p)}")
    return f"barge: {msg}"


def _span(p):
    """", best span N/8" from a reply_end with best_span, else "" (captures before 2026-10-28)."""
    if "best_span" not in p:
        return ""
    return f", best span {p['best_span']}/{SUSTAIN_WINDOWS}"


def _why(p):
    """Why one non-fired reply_end did not fire: the (a)-(d) of the module doc.

    >>> [_why(p) for p in ({"windows": "0"}, {"windows": "9", "qualifying_windows": "3"},
    ...                    {"windows": "9", "speech_windows": "0"}, {"windows": "9", "speech_windows": "4"})]
    ['(d) unheard', '(c) choppy', '(b) unsure', '(a) quiet']
    >>> _why({"windows": "394", "qualifying_windows": "27", "best_span": "7"})
    '(c) choppy: best span 7/8'
    """
    if int(p.get("windows", 0)) == 0:
        return "(d) unheard"
    if int(p.get("qualifying_windows", 0)) > 0:
        return "(c) choppy" + _span(p).replace(",", ":", 1)
    if int(p.get("speech_windows", 0)) == 0:
        return "(b) unsure"
    return "(a) quiet"


def _barge_block(events):
    """The --summary barge-in lines for one session, or [] when it logged none."""
    msgs = [msg for _, tag, _, msg in events if tag == BARGE]
    if not msgs:
        return []
    ends = [dict(_PAIR.findall(m)) for m in msgs if m.startswith("reply_end")]
    armed = sum(1 for m in msgs if m.startswith("armed="))
    fires = sum(1 for m in msgs if m.startswith("fire"))
    selfs = sum(1 for m in msgs if m.startswith("verdict") and "self_interrupt=true" in m)
    out = [f"barge-in: replies {len(ends)} (armed lines {armed}), fires {fires}, self-interrupts {selfs}"]
    quiet = [p for p in ends if p.get("fired") != "true"]
    if quiet:
        loudest = max(quiet, key=lambda p: int(p.get("max_level", 0)))
        top_prob = max(float(p.get("max_prob", 0)) for p in quiet)
        run = max(int(p.get("longest_run_ms", 0)) for p in quiet)
        spans = [int(p["best_span"]) for p in quiet if "best_span" in p]
        best = f"  best span {max(spans)}/{SUSTAIN_WINDOWS}" if spans else ""
        out.append(f"  not fired: {len(quiet)}  max level {loudest.get('max_level')} vs threshold "
                   f"{loudest.get('threshold')}  max prob {top_prob:.2f}  longest run {run}ms{best}")
        why = Counter(_why(p) for p in quiet)
        out.append("  why: " + ", ".join(f"{k} x{v}" for k, v in sorted(why.items())))
    return out


def _sessions(lines):
    """Split records into sessions at session_start. Lines before the first start form session 0."""
    sessions, current, n = [], {"n": 0, "head": {}, "events": []}, 0
    for date, time, tag, msg in _records(lines):
        if tag == AUDIT:
            event, fields = parse_audit(msg)
            if event == "session_start":
                if current["events"]:
                    sessions.append(current)
                n += 1
                current = {"n": n, "head": fields, "events": []}
            current["events"].append((time, tag, event, fields))
        else:
            current["events"].append((time, tag, None, msg))
    if current["events"]:
        sessions.append(current)
    return sessions


def _items(events, verbose):
    """Collapse events into display items: (time, kind, text, extra). Joins user and assistant chunks."""
    items = []
    user_turn = []      # user chunks already shown this turn, to drop the whole-turn final echo
    pending = None      # open ("USER"|"ASSISTANT", time, [chunks])

    def flush():
        nonlocal pending
        if pending:
            kind, t, chunks = pending
            items.append((t, kind, _clean("".join(chunks)), None))
            pending = None

    for time, tag, event, fields in events:
        if tag != AUDIT:
            if tag == "LIVE_LATENCY":
                line = _timing(fields)
            elif tag == BARGE:
                line = _barge(fields)
            elif tag == "LIVE_TURN":
                line = f"turn: {fields}"
            elif tag == "LIVE_TOOL_CANCEL":
                line = f"cancel: {fields}"
            else:
                line = f"followup: {fields}" if verbose else None
            if line:
                flush()
                items.append((time, "timing", line, None))
            continue
        if event == "user":
            text = fields.get("text", "")
            if fields.get("typed") == "true":
                flush()
                items.append((time, "USER", "(typed) " + _clean(text), None))
                continue
            if fields.get("final") == "true" and user_turn:
                if _clean("".join(user_turn)) == _clean(text):
                    continue
                flush()
                items.append((time, "USER", "(full) " + _clean(text), None))
                continue
            if not (pending and pending[0] == "USER"):
                flush()
                pending = ("USER", time, [])
            pending[2].append(text)
            user_turn.append(text)
            continue
        if event == "assistant":
            text = fields.get("text", "")
            if text == REDACTED:
                flush()
                items.append((time, "ASSISTANT", "[redacted]", None))
                continue
            if not (pending and pending[0] == "ASSISTANT"):
                flush()
                pending = ("ASSISTANT", time, [])
            pending[2].append(text)
            continue
        if event in ("activity_start", "activity_end", "mic_open", "mic_closed") and not verbose:
            continue
        flush()
        if event == "session_start":
            items.append((time, "event", "session start", None))
        elif event == "session_end":
            items.append((time, "event", f"session end ({fields.get('reason', '')})", None))
        elif event == "tool_call":
            tag_async = " [async]" if fields.get("async") == "true" else ""
            items.append((time, "call", f"TOOL -> {fields.get('name')}({fields.get('args', '')}){tag_async}",
                          fields.get("id")))
        elif event == "tool_result":
            ok = "ok" if fields.get("success") == "true" else "failed"
            flags = " [async]" if fields.get("scheduling") == "WHEN_IDLE" else ""
            if fields.get("sent") == "false":
                flags += " [not sent]"
            response = fields.get("response", "")
            if response == REDACTED:
                flags += " [redacted]"
            items.append((time, "result", f"TOOL <- {fields.get('name')} {ok} {fields.get('ms')}ms{flags}",
                          (fields.get("id"), None if response == REDACTED else response)))
        elif event == "tool_cancelled":
            items.append((time, "event", f"TOOL x {fields.get('name')} cancelled", None))
        elif event == "turn_complete":
            items.append((time, "turn", "turn complete", None))
            user_turn = []
        elif event == "interrupted":
            items.append((time, "event", f"interrupted ({fields.get('source', '?')})", None))
        elif event == "nudge":
            after = f" after {fields['after_ms']}ms" if "after_ms" in fields else ""
            items.append((time, "nudge", f"{fields.get('reason', '?')} {fields.get('tool', 'none')}{after}", None))
        elif event == "mic_open":
            items.append((time, "event", f"mic open ({fields.get('reason', '')})", None))
        elif event == "mic_closed":
            items.append((time, "event", f"mic closed ({fields.get('reason', '')})", None))
        else:
            items.append((time, "event", event.replace("_", " "), None))
    flush()
    return items


def _header(s):
    head = s["head"]
    if not head:
        return "== before the first session_start"
    return f"== session {s['n']}  model={head.get('model', '?')}  async={head.get('async_set', 'none')}"


def timeline(lines, verbose=False):
    """The readable timeline, one string per line, every session in capture order."""
    out = []
    for s in _sessions(lines):
        out.append(_header(s))
        for time, kind, text, extra in _items(s["events"], verbose):
            if kind in ("USER", "ASSISTANT"):
                out.append(f"{time}  {kind}: {text}")
            elif kind == "nudge":
                out.append(f"{time}  NUDGE {text}")
            else:
                out.append(f"{time}  {text}")
            if kind == "result" and extra[1]:
                out.append(f"{'':18}= {extra[1]}")
    return out


def summary(lines):
    """Per session: turns, user lines, tools called, async tools, holding lines."""
    out = []
    for s in _sessions(lines):
        items = _items(s["events"], verbose=False)
        head = s["head"]
        out.append(f"== session {s['n']}  model={head.get('model', '?')}" if head else _header(s))
        calls = Counter()
        async_tools = set()
        result_at = {}
        names = {}
        for i, (_, kind, _, extra) in enumerate(items):
            if kind == "result":
                result_at.setdefault(extra[0], i)
        for _, tag, event, fields in s["events"]:
            if event == "tool_call":
                calls[fields.get("name")] += 1
                names[fields.get("id")] = fields.get("name")
                if fields.get("async") == "true":
                    async_tools.add(fields.get("name"))
        # Turns, with the assistant lines and tool calls in each. Consecutive assistant chunks with
        # only timing lines between them are one line (a transcript split by eot_reply).
        holding = []
        turn_said, turn_calls = [], []
        last_kind = None
        for i, (time, kind, text, extra) in enumerate(items + [(None, "turn", None, None)]):
            if kind == "ASSISTANT" and text != "[redacted]":
                if last_kind == "ASSISTANT" and turn_said:
                    idx, t, said = turn_said[-1]
                    turn_said[-1] = (idx, t, _clean(said + " " + text))
                else:
                    turn_said.append((i, time, text))
            elif kind == "call":
                turn_calls.append(extra)
            elif kind == "turn":
                for idx, t, said in turn_said:
                    late = [c for c in turn_calls if result_at.get(c, -1) > idx]
                    if late:
                        holding.append((t, said, names.get(late[0])))
                turn_said, turn_calls = [], []
            if kind != "timing":
                last_kind = kind
        # Nudges, each with the first assistant line after it, before the next nudge or session end.
        nudges = []
        for i, (time, kind, text, _) in enumerate(items):
            if kind != "nudge":
                continue
            reply = None
            for _, k2, t2, _ in items[i + 1:]:
                if k2 == "nudge":
                    break
                if k2 == "ASSISTANT":
                    reply = t2
                    break
            nudges.append((time, text, reply))
        out.append(f"turns: {sum(1 for _, k, _, _ in items if k == 'turn')}")
        out.append(f"user lines: {sum(1 for _, k, _, _ in items if k == 'USER')}")
        out.append("tools called: " + (", ".join(f"{n} x{c}" for n, c in sorted(calls.items())) or "none"))
        out.append("async tools: " + (", ".join(sorted(async_tools)) or "none"))
        out.append(f"holding lines: {len(holding)}")
        for t, said, tool in holding:
            out.append(f"  {t}  {said}  (before {tool} returned)")
        if nudges:
            followed = sum(1 for _, _, reply in nudges if reply)
            out.append(f"nudges: {len(nudges)} (followed by a line: {followed})")
            for t, text, reply in nudges:
                out.append(f"  {t}  {text} -> {reply or '(no line)'}")
        out.extend(_barge_block(s["events"]))
    return out


def _read(path):
    """A capture as text lines. PowerShell 5.1's `>` writes UTF-16, so the BOM decides."""
    raw = sys.stdin.buffer.read() if path == "-" else open(path, "rb").read()
    if raw[:2] in (b"\xff\xfe", b"\xfe\xff"):
        return raw.decode("utf-16", errors="replace").splitlines()
    return raw.decode("utf-8-sig", errors="replace").splitlines()


def _utf8_stdout():
    """Windows consoles default to cp1252; a transcript can carry any character."""
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass


def main(argv=None):
    _utf8_stdout()
    ap = argparse.ArgumentParser(description="Timeline of LIVE_AUDIT lines from a logcat capture.")
    ap.add_argument("logfile", help="adb logcat -v threadtime capture, or - for stdin")
    ap.add_argument("--summary", action="store_true", help="per-session counts and holding lines")
    ap.add_argument("--verbose", action="store_true", help="also mic, activity and follow-up lines")
    args = ap.parse_args(argv)
    lines = _read(args.logfile)
    out = summary(lines) if args.summary else timeline(lines, verbose=args.verbose)
    if not out:
        print("no LIVE_AUDIT lines found (is the audit log switched on?)", file=sys.stderr)
        return 1
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
