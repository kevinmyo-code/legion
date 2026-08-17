# 12-hour screen-off unplugged run - RESULT

Read 2026-08-17 08:07 CDT on the A25. Baseline: [12h-run-baseline.md](12h-run-baseline.md).
Gates the ten on-device items in [scheduling research](07-scheduling.md) §8.

**Elapsed window: 7h53m, not 12h** (00:14:07 -> 08:07:01). The last ~3 minutes are dirty - Kevin
opened LEGION at 08:04:22, which brought MainActivity to the foreground, connected Spotify App
Remote and started two live sessions. Everything before 08:04 is undisturbed.

## Headline: IT SURVIVED

| Reading | Baseline 00:14 | Now 08:07 | Verdict |
|---|---|---|---|
| pid | 20976 | **20976** | never killed |
| /proc starttime (jiffies) | 10750861 | **10750861** | never restarted |
| device uptime (s) | 108809.86 | 137187.95 | +28378s, **no reboot** |
| `AriaForegroundService` | isForeground=true, types=0x91 | **identical**, createTime=-7h53m56s | held the whole window |
| standby bucket | 10 (ACTIVE) | **10** | never throttled |
| battery-opt allowlist | not allowlisted | still not | survived without it |
| battery | 92%, unplugged | 86%, unplugged | **-6% over 7h53m = 0.76%/h** |

`MediaNotificationListener` also held (createTime -8h14m). The foreground service kept the app in
bucket 10 for the whole window, so the Samsung sleeping-apps risk (~3 days unused) was never
approached by a run this short and remains **untested**, not cleared.

`tag: on-device` for every row above.

## What the run did NOT prove

- **Nothing fired.** No alarm was due - the only pending one is `REMINDER_FIRE` at
  2026-08-18 00:00. No proactive line, no digest, no scheduled work of any kind ran, because none
  was scheduled. The run proves the process survives; it proves nothing about whether a proactive
  would have been delivered.
- **The wake word still cannot run** (`assets/vosk-model/` is a README).
- **Samsung's restricted bucket** needs ~3 days unused, not 8 hours.

## DEFECT FOUND: the prewarm socket reconnects forever, all night, with nobody there

**64 `session_start` events in 7h53m** - roughly one every 7 minutes, from 00:13 to 08:07, on a
screen-off phone in a dark room. End reasons:

| Reason | Count |
|---|---|
| `failure http null: Connection reset` | 29 |
| `The operation was aborted.` | 24 |
| `sent ping but didn't receive pong within 20000ms` | 9 |
| `closed code 1000` | 1 |

**Root cause, `traced`:** `LiveSessionController.kt:496` escalates the backoff only when
`!everConnected`. Every one of these sockets **did** connect and then dropped (that is what
"Connection reset" and a failed ping/pong mean), so `consecutivePrewarmFailures` stays 0 and
line 515 calls `prewarm()` **immediately, with no delay**. Connect, WiFi sleeps, drop, reconnect,
forever. The backoff ladder at line 511 exists and was never reached once all night.

**Consequences:**
- **12.87 MB uploaded / 7.01 MB received** overnight, 121 WiFi AP wakeups. The setup frame carries
  the base system instruction plus **78 tool declarations** and is re-sent on every connect.
- **Token cost is `reasoned`, NOT measured.** The mic is not opened by a prewarm socket
  (`prewarmOnly` skips `setBusy`; `capturing`/`vadMicOpenedOnce` stay false and the mic opens only
  in `startConversation`), so no audio was streamed and no audio-input billing accrued. What was
  billed, if anything, is 64 setup frames of instruction + declarations on Kevin's own key.
  **Check the API usage page against 2026-08-17 00:14-08:07 rather than trusting this paragraph.**
- Only **1 voice turn** in the whole window, at 00:13, and it was `silent_mic_turn: bytes=0 heard=""`.

**Nobody ever saw this** because it only happens when the app is left alone for hours - and until
last night the service had never been left running for hours.

## The biggest power line is the OBD Bluetooth link, not the socket

`dumpsys batterystats` blames LEGION **79.8** total, top of the device list, `fgs` 9h39m:

```
bluetooth=22.8  (bluetooth:fgs=21.9, of which 21.5 during screen-off/doze)
cpu=16.8        (cpu:fgs=9.07)
screen=39.3     (screen-on only, i.e. Kevin's own use, not the run)
Bluetooth Idle 8h59m (90.3%) / Rx 51m8s (8.6%) / Tx 6m37s (1.1%)
```

The radio did **51 minutes of receive and 6.6 minutes of transmit** overnight, so the dongle link
was live and being polled with the car parked. `obd_connected: 78:9A:BC:11:22:33` at 00:13:41; by
08:06 it was reporting `obd_pid_silence[1]: 'UNABLE TO CONNECT'`.

**Caveat, `traced`:** Android blames a Bluetooth connection to every UID using it - `u0a240`,
`u0a117` and uid `5006` each show the same 22.8 - so 22.8 is a shared total, not LEGION's private
cost. The direction (Bluetooth is the dominant line) holds; the magnitude attributable to LEGION
alone does not follow from this dump.

## What this changes

1. **The ignition defect is now the only thing standing between the app and a working assistant.**
   The service demonstrably survives a night once started. It just is not started by anything
   except the Settings toggle - not app launch, not `BootReceiver`. Fix that and the standing
   MEMORY line "OBD, wake word, proactives never run" is largely addressed.
2. **The prewarm reconnect loop needs a cap** before proactive mode ships. Suggested shape: escalate
   the backoff on ANY unexpected close, not only `!everConnected`; and stop prewarming entirely
   while the screen has been off and no conversation has happened for N minutes.
3. **Poll the dongle only when the car is awake.** Holding an ELM327 link through a parked night
   buys nothing and is the largest measured power line.
4. **Re-run for a real 12h+, ideally 3+ days**, if the Samsung restricted-bucket question is to be
   answered rather than assumed.

None of items 1-4 was acted on. They are findings, not changes.
