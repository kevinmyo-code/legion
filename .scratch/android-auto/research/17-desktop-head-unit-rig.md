# The Desktop Head Unit rig: how to get one running

Written 2026-08-19, from the session that first got LEGION visible in Android Auto. Everything here
is `tested` on Kevin's own machine unless it says otherwise.

**Why this file exists.** The map's charting note says "the test rig is a real head unit" and
"Google's Desktop Head Unit emulator is worth adding later... the map does not depend on it".
[Ticket 16](../issues/16-can-a-sideloaded-car-app-library-app-run.md) then said in writing that the
call "should be revisited". It was, and the DHU turned out to be the difference between a car trip
and a window on the desk. This is the recipe, so nobody pays the setup cost twice.

## Install it without the Android Studio GUI

There is no `cmdline-tools`/`sdkmanager` on this machine, but **Android Studio ships its own CLI**:

```
C:\Users\Kwin\Apps\AndroidStudio\plugins\android\resources\android-cli\bin\android.exe sdk install "extras;google;auto"
```

It self-downloads on first run. Lands at `Sdk/extras/google/auto/desktop-head-unit.exe`, with
`config/*.ini` profiles beside it.

## Run it

Phone first, and **only Kevin can do this part** - the activity is not exported and `adb` cannot
reach it:

1. Android Auto settings, tap the version line about ten times for developer mode.
2. Overflow menu, **Start head unit server**.

Then:

```
ADB="/c/Users/Kwin/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s <serial> forward tcp:5277 tcp:5277
cd "C:/Users/Kwin/AppData/Local/Android/Sdk/extras/google/auto"
tail -f /dev/null | ./desktop-head-unit.exe -a 5277 -c config/default.ini -i touch
```

## Two failures that both look exactly like a hang

Neither reports itself honestly, and both cost real time here.

1. **DHU is an interactive shell.** Launched detached, stdin hits EOF and it exits with **code 0**
   about a second after connecting - a clean exit that reads as success in a log. Hence the
   `tail -f /dev/null |` prefix above.
2. **One "Start head unit server" tap arms exactly ONE session.** Every DHU exit burns it. A second
   DHU launched against a spent server negotiates TLS *successfully* and then sits on "waiting for
   phone" forever. If you see that, the answer is another tap, not more debugging.

## 800x480 works over wireless adb. 1080p does not.

With `config/default_1080p.ini` the phone logged:

```
CAR.SERVICE.FCD.LITE: timed out at stage FIRST_ACTIVITY_LAUNCHED after 5000 milliseconds,
                      publishing PROJECTION_NOT_STARTED
GH.ConnLoggerV2: ... USB_ISSUE_PROJECTION_NOT_STARTED
```

while DHU logged `Failed to read from transport - disconnect`. The identical setup on
`config/default.ini` (800x480, 30fps) projected first try. `tested`.

That bandwidth is the cause rather than the four-year-old DHU build (2.0-windows,
2022-03-30-438482292) is `reasoned` - the two runs differed only in resolution. Kevin has no
data-capable USB cable, so the wired path is untested and stays untested.

## Unknown sources was never needed

Ticket 12 and the folklore both expect Android Auto's unknown-sources developer option to be
required for a sideloaded app. **A media app did not want it** - LEGION appeared without it, on the
first session that projected. That is a finding for ticket 12, not a footnote.

## What the rig cannot prove

Unchanged from the map's own charting note, and a green DHU must not be allowed to stand in for any
of it: **car mic, HFP routing, the real in-call UI**, and **OBD Bluetooth contention while
projecting**. Tickets 07, 13 and 14 still need the car.

## Versions

Android Auto **17.3.662854-release** on the phone (Galaxy A25, SM-A256U, Android 16). DHU
**2.0-windows**, build 2022-03-30-438482292. See `memory/library/` and the repo's own
`MEMORY.md` for the adb connection dance, which is separate from this.
