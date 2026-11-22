"""next2: the checks for phone OTA 263261832 (dev 70e6f71c, "cards refresh live, budget card, date
carry, mic release after a ring, closing by voice, event delete").

Test items are made up and named `verify-test ...`; each check removes its own. Assistant turns go
through the test-turn door as his words, so the voice cascade (the code under test for date carry and
closing) runs with no laptop speaker. Nothing here touches money writes, mail, messages or calls: the
budget question is a read, and a forbidden tool name in any turn stops the run.
"""
from __future__ import annotations

import datetime as dt

from .. import logs
from ..runner import Blocked, Check, Ctx, Fail, Suite
from ..steps import calls_since, end, find, go_home, poll_log, say, wait_for

A = "app/src/main/java/com/kevin/legion/"
VERSION_CODE = 263261832

# What the checks read on the phone, and where the app writes it (the `stale` map).
CARD_UI = {
    A + "ui/assistant/CardStage.kt": ["Close card", "CardPage", "CardStage"],
}
DOOR = {
    A + "service/testturn/TestTurns.kt": ["STATUS_KEYS", "fun turn", "fun end"],
    A + "service/testturn/TestTurnPolicy.kt": ["fun isRead"],
}
AUDIT = {A + "service/LiveAudit.kt": ["fun toolCall", "fun toolResult", "fun sessionEnd"]}


def deps(*maps: dict) -> dict:
    out: dict[str, list[str]] = {}
    for m in maps:
        for k, v in m.items():
            out.setdefault(k, []).extend(x for x in v if x not in out.get(k, []))
    return out


# ---------------------------------------------------------------------------------- version

def version_verify(ctx: Ctx) -> None:
    code, name = ctx.device.version()
    ctx.note("version_code", code)
    ctx.note("version_name", name)
    ctx.run["installed"] = {"version_code": code, "version_name": name}
    if code != ctx.suite.version_code:
        raise Fail(f"installed versionCode {code} ({name}), suite expects {ctx.suite.version_code}")


# ------------------------------------------------------------------------ card live update

LIST_ITEM = "verify-test milk"


def card_setup(ctx: Ctx) -> None:
    go_home(ctx)
    say(ctx, "what's on my grocery list?")
    if not wait_for(ctx, "Close card", 15):
        raise Blocked("no card came up for the grocery list read (is there a grocery list?)")


def card_act(ctx: Ctx) -> None:
    ctx.data["cleanup_needed"] = True
    ctx.data["add_mark"] = say(ctx, f"add {LIST_ITEM} to my grocery list", writes=True)


def card_verify(ctx: Ctx) -> None:
    calls = calls_since(ctx, ctx.data["add_mark"])
    wrote = [c for c in calls if "checklist" in c.name and str(c.args.get("action", "")).lower() == "add"]
    ctx.note("add_succeeded", any(c.success for c in wrote))
    if not any(c.success for c in wrote):
        raise Fail("the add did not come back successful in LIVE_AUDIT")
    if not wait_for(ctx, LIST_ITEM, 10):
        raise Fail("the card on screen did not show the added item (no live refresh)")
    reread = [c for c in calls if "checklist" in c.name and str(c.args.get("action", "")).lower() == "read"]
    ctx.note("model_reread_list", bool(reread))
    if reread:
        raise Blocked("inconclusive: the model re-read the list in the add turn, so the card may be a new read")


def card_cleanup(ctx: Ctx) -> None:
    if not ctx.data.get("cleanup_needed"):
        end(ctx)
        return
    mark = say(ctx, f"remove {LIST_ITEM} from my grocery list", writes=True)
    removed = any(c.success for c in calls_since(ctx, mark)
                  if "checklist" in c.name and str(c.args.get("action", "")).lower() in ("remove", "delete"))
    end(ctx)
    if not removed:
        raise Fail(f"'{LIST_ITEM}' may still be on the grocery list: remove it by hand")


CARD = Check(
    "card_live_update", "a shown list card updates in place after an add, no reopen",
    deps(CARD_UI, DOOR, AUDIT, {
        A + "assistant/cards/LiveCards.kt": ["fun follow", "fun refresh", "data class Checklist"],
        A + "service/LiveCardReads.kt": [],
        A + "assistant/cards/ConversationCards.kt": ["fun replace"],
    }),
    card_setup, card_act, card_verify, card_cleanup, door=True,
)


# ---------------------------------------------------------------------------------- budget card

def budget_act(ctx: Ctx) -> None:
    go_home(ctx)
    say(ctx, "how am I doing on my budget this month?")


def budget_verify(ctx: Ctx) -> None:
    # The card's title only; its figures are his and are never read into the log.
    if not wait_for(ctx, "Budget pace", 15):
        raise Fail("no 'Budget pace' card after a budget question")


BUDGET = Check(
    "budget_card", "a budget question puts up the budget pace card",
    deps(CARD_UI, DOOR, {
        A + "assistant/cards/BudgetCards.kt": ["Budget pace", "fun pace"],
        A + "service/tools/money/BudgetTool.kt": ["BudgetCards"],
    }),
    None, budget_act, budget_verify, lambda ctx: end(ctx), door=True,
)


# ---------------------------------------------------------------------------------- date carry

CARRY_ITEM = "verify-test pickup"


def carry_setup(ctx: Ctx) -> None:
    now = ctx.device.now()
    if now.time() >= dt.time(20, 30):
        raise Blocked("run before 8:30 pm phone time: a 9 pm add after that is tomorrow on its own")
    ctx.data["tomorrow"] = (now.date() + dt.timedelta(days=1)).isoformat()
    say(ctx, "what's on my calendar tomorrow?")


def carry_act(ctx: Ctx) -> None:
    ctx.data["cleanup_needed"] = True
    ctx.data["add_mark"] = say(ctx, f"add {CARRY_ITEM} at 9 pm", writes=True)


def carry_verify(ctx: Ctx) -> None:
    adds = [c for c in calls_since(ctx, ctx.data["add_mark"])
            if c.name == "manage_item" and c.success]
    dates = [m.group(1) for c in adds for m in [logs.DATE_IN_RESULT.search(c.response)] if m]
    ctx.note("landed_on_tomorrow", ctx.data["tomorrow"] in dates)
    if not adds:
        raise Fail("no successful manage_item add in LIVE_AUDIT")
    if not dates:
        raise Fail("the add's result names no date (LiveToolbox appointment result changed?)")
    ctx.data["landed"] = dates[-1]
    if dates[-1] != ctx.data["tomorrow"]:
        raise Fail("the 9 pm add did not land on tomorrow (date carry)")


def carry_cleanup(ctx: Ctx) -> None:
    if not ctx.data.get("cleanup_needed"):
        end(ctx)
        return
    day = dt.date.fromisoformat(ctx.data.get("landed") or ctx.data.get("tomorrow") or ctx.device.now().date().isoformat())
    _delete_event(ctx, CARRY_ITEM, day)


def _delete_event(ctx: Ctx, item: str, day: dt.date) -> None:
    words = day.strftime("%A %B ") + str(day.day)
    mark = say(ctx, f"delete the {item} event on {words}", writes=True)
    ok = any(c.success for c in calls_since(ctx, mark) if c.name in ("manage_item", "manage_event", "day"))
    end(ctx)
    if not ok:
        raise Fail(f"'{item}' on {day.isoformat()} may still be on the calendar: delete it by hand")


CARRY = Check(
    "date_carry", "ask about tomorrow, then add at a time: it lands on tomorrow",
    deps(DOOR, AUDIT, {
        A + "service/tools/day/DateCarry.kt": ["fun carried", "fun resolved", "CARRY_ACTIONS"],
        A + "service/cascade/CascadeSession.kt": ["conversationDay", "fun runDated", "fun noteAboutDay"],
        A + "service/LiveToolbox.kt": ["DateCarry.resolved", "put(\"date\", date.toString())"],
        A + "service/tools/day/RelativeDay.kt": ["class TurnDates"],
    }),
    carry_setup, carry_act, carry_verify, carry_cleanup, door=True,
)


# --------------------------------------------------------------------------- calendar delete

CAL_ITEM = "verify-test dentist"
SYNC_WAIT_S = 150  # EnginePoll ticks every 60 s in the foreground


def cal_setup(ctx: Ctx) -> None:
    now = ctx.device.now()
    if now.time() >= dt.time(23, 0):
        raise Blocked("run before 11 pm phone time (the test event goes at 11:30 pm today)")
    ctx.data["day"] = now.date()
    ctx.data["cleanup_needed"] = True
    say(ctx, f"add {CAL_ITEM} today at 11:30 pm", writes=True)
    end(ctx)


def _open_calendar(ctx: Ctx) -> None:
    go_home(ctx)
    ctx.device.tap_text("Calendar")
    if not ctx.device.wait_text("Calendar", 10):
        raise Blocked("the Calendar screen did not open")
    ctx.step("calendar open")


def cal_act(ctx: Ctx) -> None:
    _open_calendar(ctx)
    if not wait_for(ctx, CAL_ITEM, 15):
        raise Fail("the event added by the assistant is not on the Calendar screen's day")
    els = ctx.device.screen()
    row = find(els, CAL_ITEM)[0]
    removes = [e for e in els if e.label == "Remove" and e.bounds[1] - 80 <= row.center[1] <= e.bounds[3] + 80]
    if not removes:
        raise Fail("no Remove control on the event's row")
    target = min(removes, key=lambda e: abs(e.center[1] - row.center[1]))
    ctx.data["delete_mark"] = ctx.device.mark()
    ctx.device.tap_xy(*target.center, what="the event row's Remove")
    ctx.step("tapped Remove")
    if not ctx.device.wait_text(CAL_ITEM, 10, gone=True):
        raise Fail("the event stayed on screen after Remove")
    ctx.data["deleted_by_hand"] = True


def cal_verify(ctx: Ctx) -> None:
    synced = poll_log(ctx, ctx.data["delete_mark"], logs.EVENTS_PULLED, SYNC_WAIT_S)
    ctx.note("sync_seen", synced)
    if not synced:
        raise Blocked(f"no events sync logged within {SYNC_WAIT_S} s of the delete; cannot say it stays deleted")
    ctx.device.key("back")
    _open_calendar(ctx)
    if ctx.device.wait_text(CAL_ITEM, 8):
        raise Fail("the deleted event came back after a sync")
    ctx.data["cleanup_needed"] = False


def cal_cleanup(ctx: Ctx) -> None:
    if ctx.data.get("cleanup_needed"):
        _delete_event(ctx, CAL_ITEM, ctx.data.get("day") or ctx.device.now().date())


CAL = Check(
    "calendar_delete", "an event deleted on the Calendar screen stays deleted after a sync",
    deps(DOOR, {
        A + "ui/CalendarScreen.kt": ["\"Remove\"", "removeAppointment", "fun CalendarDayRow"],
        A + "backend/EventsSync.kt": ["fun pull", "fun fresh", "local.deleted && local.serverId == null"],
        A + "backend/EventsOutbox.kt": [],
        A + "MidnightEvents.kt": ["events_auto_pull", "engine_poll["],
        A + "backend/engine/EnginePoll.kt": ["DEFAULT_INTERVAL_MS"],
    }),
    cal_setup, cal_act, cal_verify, cal_cleanup, door=True,
)


# ------------------------------------------------------------------------ closing with thanks

def close_act(ctx: Ctx) -> None:
    say(ctx, "what time is it?")
    ctx.data["close_mark"] = say(ctx, "thanks, that's all")


def close_verify(ctx: Ctx) -> None:
    for _ in range(15):
        if ctx.device.conversation() in ("none", "None"):
            break
        ctx.device.sleep(1)
    else:
        raise Fail("the conversation stayed open after 'thanks, that's all'")
    ended = logs.sessions_ended(ctx.device.logcat(ctx.data["close_mark"]))
    ctx.note("session_end_reasons", ended)
    if not ended:
        raise Fail("closed, but no session_end in LIVE_AUDIT")


CLOSE = Check(
    "closing_thanks", "'thanks, that's all' ends the conversation (as his words, not a stop tap)",
    deps(DOOR, AUDIT, {
        A + "service/WakePhrases.kt": ["LEAD_INS", "fun isSleepPhrase", "fun isStopPhrase", "\"thanks\""],
        A + "service/cascade/CascadeSession.kt": ["WakePhrases.isSleepPhrase"],
    }),
    None, close_act, close_verify, lambda ctx: end(ctx), door=True,
)


# ------------------------------------------------------------------------ mic after a ring

def _stop_controls(els):
    return [e for e in els if e.label in ("Stop", "Stop alarm") and e.clickable]


def mic_act(ctx: Ctx) -> None:
    go_home(ctx)
    ctx.data["cleanup_needed"] = True
    say(ctx, "set a timer for 10 seconds", writes=True)
    end(ctx)
    for _ in range(20):
        stops = _stop_controls(ctx.device.screen())
        if stops:
            break
        ctx.device.sleep(2)
    else:
        raise Fail("the 10 second timer never showed a Stop control")
    ctx.data["stop_mark"] = ctx.device.mark()
    ctx.device.tap_xy(*stops[0].center, what="the ring's Stop")
    ctx.data["cleanup_needed"] = False
    ctx.step("ring stopped")


def mic_verify(ctx: Ctx) -> None:
    since = ctx.data["stop_mark"]
    granted = poll_log(ctx, since, logs.MIC_WAKE_GRANTED, 30)
    lines = ctx.device.logcat(since)
    refused = any(logs.MIC_WAKE_REFUSED.search(ln.msg) for ln in lines if ln.tag == "MicArbiter")
    ctx.note("ring_stop_logged", any(logs.RING_STOP.search(ln.msg) for ln in lines))
    ctx.note("wake_granted", granted)
    ctx.note("wake_refused_seen", refused)
    if granted:
        return
    if refused:
        raise Fail("the wake word was refused the mic after the ring (a claim was never released)")
    raise Blocked("inconclusive: no wake-word mic request logged in 30 s (is the wake word on?)")


def mic_cleanup(ctx: Ctx) -> None:
    stops = _stop_controls(ctx.device.screen()) if ctx.data.get("cleanup_needed") else []
    if stops:
        ctx.device.tap_xy(*stops[0].center, what="the ring's Stop")
    elif ctx.data.get("cleanup_needed"):
        say(ctx, "cancel the timer", writes=True)
        end(ctx)


MIC = Check(
    "mic_after_ring", "after a conversation and a timer ring, the wake word gets the mic back",
    deps(DOOR, {
        A + "service/MicArbiter.kt": ["class Hold", "fun drop", "granted", "refused", "WAKE_WORD"],
        A + "service/cascade/CascadeSession.kt": ["micHold", "micHold.drop()"],
        A + "clock/RingControl.kt": ["ring_stop kind=", "fun stop"],
        A + "ui/clock/RingScreen.kt": ["BigButton(\"Stop\""],
        A + "ui/clock/RingStopBar.kt": ["Stop alarm"],
        A + "service/WakeWordEngine.kt": ["watchdog"],
    }),
    None, mic_act, mic_verify, mic_cleanup, door=True,
)


# ------------------------------------------------------------------------ manual + crash scan

MANUAL_VOICE_CLOSE = Check(
    "closing_by_voice", "spoken 'thanks, Alfred, that's all' closes",
    {A + "service/WakePhrases.kt": ["LEAD_INS"]},
    manual="say it out loud in a real conversation; the typed check covers the phrase rule, not the mic path",
)
MANUAL_CALL_RING = Check(
    "wake_after_call_ring", "wake word answers after an incoming call rang",
    {A + "service/LiveSessionController.kt": ["RING_LISTENING"], A + "service/MicArbiter.kt": ["class Hold"]},
    manual="needs a real incoming call (the RING_LISTENING claim this fix is about): let one ring out, "
           "then say the wake word; never scripted (calls are off limits)",
)


def crash_verify(ctx: Ctx) -> None:
    found = logs.crashes(ctx.device.logcat(ctx.run["phone_mark"]))
    ctx.note("crashes", found)
    if found:
        raise Fail(f"{len(found)} LEGION crash/ANR during the run: {', '.join(found[:3])}")


NO_CRASH = Check(
    "no_crash", "no LEGION crash or ANR in logcat during the run",
    {}, verify=crash_verify, always=True,
)

VERSION = Check(
    "version", "installed build is the suite's OTA",
    {"app/build.gradle.kts": ["versionCode"]}, verify=version_verify, always=True,
)

SUITE = Suite("next2", "phone OTA 263261832: live cards, budget card, date carry, mic, closing, event delete",
              VERSION_CODE, [VERSION, CARD, BUDGET, CARRY, CAL, CLOSE, MIC, MANUAL_VOICE_CLOSE, MANUAL_CALL_RING, NO_CRASH])
