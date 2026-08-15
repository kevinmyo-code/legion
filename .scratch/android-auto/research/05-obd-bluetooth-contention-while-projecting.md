# Research: Can OBD keep its Bluetooth radio while the phone projects to the car?

Ticket: `.scratch/android-auto/issues/05-obd-bluetooth-contention-while-projecting.md`
Researched: 2026-08-13
Tags used on every claim: `documented` (citation URL), `inferred` (reasoned from a documented fact),
`field-report` (user/forum evidence, NOT specification), `traced` (read in this repo's source).

## Short answer

Coexistence is **not** the thing most likely to break OBD in the car. Three findings, in order of
how much they should move the map:

1. **RFCOMM to the dongle and HFP/A2DP to the head unit are different logical transports on
   different links. Nothing in the spec or in Android forbids running them together**, and the
   phone is the piconet central for both. The cost of SCO is *bandwidth*, not *eviction*.
   `documented` + `inferred`.
2. **Wired Android Auto does not remove Bluetooth from the picture the way the ticket hopes.** It
   removes Wi-Fi Direct, which is the 2.4 GHz coexistence risk, but the phone stays paired to the
   head unit for HFP/A2DP and one field report says the head unit's own Bluetooth link drops when
   USB projection starts. That is a head-unit behaviour, not an Android guarantee. `field-report`.
3. **The real exposure is LEGION's own failure shape, not the radio.** `Elm327Io.readUntilPrompt`
   polls `available()` and never blocks on `read()`, so a link that goes *quiet* - which is exactly
   what radio contention produces - is invisible to it. It returns `""`, which
   `ObdResponseParser.isFailureResponse` classifies identically to a dead K-line, so the app reports
   `CONNECTED` with dead gauges and blames the car. **The app today cannot tell "car is off" from
   "radio is busy", and would not learn to just by adding a coexistence mitigation.** `traced`.

Finding 3 is actionable now, independent of every other answer on this page.

---

## 1. Can RFCOMM to the ELM327 coexist with HFP + A2DP to the head unit?

**Yes, structurally.** `documented` + `inferred`.

- Android exposes RFCOMM client sockets per remote device; the documented RFCOMM limitation is
  "**Unlike TCP/IP, RFCOMM allows only one connected client per channel at a time**" - that is one
  client per *channel on one device*, not one RFCOMM link per phone.
  [developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)
  `documented`
- HFP and A2DP are separate profiles the vehicle side connects, and AOSP's automotive connectivity
  doc treats concurrent per-profile connections as normal ("the IVI can connect to multiple devices
  via HFP"). Nothing there implies a profile connection displaces an app's RFCOMM socket.
  [source.android.com/docs/automotive/ivi_connectivity](https://source.android.com/docs/automotive/ivi_connectivity)
  `documented`
- Bluetooth Core 5.4 Vol 2 Part B enumerates ACL, SCO and eSCO as distinct **logical transports**
  multiplexed over the piconet physical channel (§4.6, §4.6.1 SCO, §4.6.2 eSCO; piconet topology
  §4.1-4.2). RFCOMM rides ACL. HFP audio rides SCO/eSCO. Different transports, same radio.
  [bluetooth.com Core-54 Baseband Specification](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/br-edr-controller/baseband-specification.html)
  `documented` for the section structure; **the SIG's HTML renders as navigation only to a fetcher,
  so the body text of §4.6.1 was NOT read directly** - treat the numbers in §2 below accordingly.
- Practical concurrent-connection limit on a single-antenna phone is a **controller** property, not
  an Android one. AOSP hardcodes a max for *connected A2DP devices*
  (`kDefaultMaxConnectedAudioDevices` in `btif_av.cc`) but exposes no documented global cap for
  app RFCOMM sockets. `documented` (that the A2DP cap exists) /
  `inferred` (that no comparable app-facing RFCOMM cap is published).
- Hardware here is not the constraint: OPPO's own spec sheet lists the A17k as **Bluetooth 5.3**.
  [oppo.com A17k specs](https://www.oppo.com/en/smartphones/series-a/a17k/specs/) `documented`

**Load on the phone in the car, counted:** one ACL to the dongle (RFCOMM/SPP), one ACL to the head
unit (AVDTP signalling + AVRCP + HFP control channel), one SCO/eSCO to the head unit during a call.
Two ACL links plus one synchronous link. That is unremarkable for a modern controller. `inferred`

## 2. Does SCO starve RFCOMM?

**It taxes it; it does not evict it.** `inferred` from `documented` structure.

The mechanism is slot reservation. SCO/eSCO are described by the Core spec as reserving slots at a
regular interval, which is what makes them circuit-switched; ACL traffic uses what is left. Widely
repeated figures put HV1 at all slots reserved (no ACL possible), HV2 at half, HV3 at one third -
i.e. HV3 leaves roughly two thirds of slots for ACL. Modern HFP 1.6+ over eSCO (EV3 / 2-EV3) is
lighter still and adds a retransmission window instead of blind repetition.

- Spec section anchors: Core 5.4 Vol 2 Part B §4.6.1 (SCO), §4.6.2 (eSCO).
  [bluetooth.com Core-54 Baseband](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/br-edr-controller/baseband-specification.html)
  `documented` (sections exist and are the right ones)
- The 1/3-2/3 arithmetic itself came from secondary technical literature, not from spec text I was
  able to read. **Tag it `inferred`, not `documented`.** Do not quote a percentage at Kevin as fact.

**What this predicts for LEGION:** during an active call, ELM327 round-trips get slower, not fewer.
The socket is not torn down by slot reservation - reservation delays ACL, it does not close it.
`inferred`

**What actually matters more than the percentage:** `sendCommand`'s default timeout is 5000 ms and
`getVin` uses 5000 ms explicitly (`ObdBluetoothManager.kt`). A 2-3x slowdown on a link that already
needed hundreds of milliseconds still fits inside 5 s. **The existing timeouts likely absorb SCO
contention on their own.** `traced` + `inferred`. This is testable (see T2).

**Socket survival:** RFCOMM is L2CAP-over-ACL with retransmission; delayed slots produce latency,
and only a sustained link supervision timeout (radio out of range / link failure) closes the ACL.
`inferred`. If the socket does close, see §5 for what LEGION sees.

## 3. Wireless Android Auto

- **5 GHz is a hard requirement** and it is Google-documented: "a compatible Android phone with an
  active data plan and **5 GHz Wi-Fi support**"; "wireless projection is compatible with any phone
  with Android 11.0".
  [support.google.com/androidauto/answer/6348019](https://support.google.com/androidauto/answer/6348019?hl=en)
  `documented`
- **Bluetooth is used for the initial pairing/handshake**: "The first time you connect wirelessly,
  you will need to pair your phone and car via Bluetooth."
  [support.google.com/androidauto/answer/6348029](https://support.google.com/androidauto/answer/6348029?hl=en)
  `documented`
- **The A17k supports 5 GHz** ("WLAN 2.4G/WLAN 5.1G/WLAN 5.8G", Wi-Fi 5 802.11ac), so wireless AA is
  not ruled out by the phone.
  [oppo.com A17k specs](https://www.oppo.com/en/smartphones/series-a/a17k/specs/) `documented`
- **Does Bluetooth stay busy after the switch to Wi-Fi?** Yes in the sense that the HFP/A2DP link
  remains connected for phone-call audio - that is the standing explanation in Google-hosted
  community and mainstream write-ups ("Bluetooth starts things off and handles calls, while the
  Wi-Fi connection handles the rest"). No first-party Google page states this in those words.
  `field-report`
- **Does the Wi-Fi Direct link interfere with 2.4 GHz Bluetooth?** The projection carrier is on
  **5 GHz**, a different band from BR/EDR's 2.4 GHz, so direct in-band collision is not the
  mechanism. The residual risk is antenna/front-end sharing and controller time-slicing on a budget
  single-antenna phone, which is a hardware-integration property nobody publishes.
  Android does document band-level coexistence machinery generally (Wi-Fi/cellular coex channel
  avoidance, Android 12+), but nothing app-facing for BT/Wi-Fi.
  [source.android.com/docs/core/connect/wifi-coex-channel-avoidance](https://source.android.com/docs/core/connect/wifi-coex-channel-avoidance)
  `documented` (that coexistence machinery exists) / `inferred` (that 5 GHz projection is low BT risk)

**Net:** wireless AA is *less* of a 2.4 GHz coexistence problem than the ticket assumes, because the
bulk traffic is on 5 GHz. `inferred`.

## 4. Wired Android Auto over USB

**Partial mitigation, not a clean escape.** It removes the Wi-Fi Direct leg entirely `inferred`,
but it does not take Bluetooth out of the car: the phone stays paired for HFP/A2DP, and Google's
own setup page describes the wired path only as "plug a USB cable in", saying nothing about
Bluetooth either way.
[support.google.com/androidauto/answer/6348029](https://support.google.com/androidauto/answer/6348029?hl=en) `documented` (by omission)

Field evidence, both directions, both `field-report`:
- Users report running an OBD-II Bluetooth adapter and Android Auto at the same time successfully:
  "You can connect to both Android Auto and an OBDII unit simultaneously."
  [forums.androidcentral.com](https://forums.androidcentral.com/android-auto/778601-compatibility-using-obd-2-units-android-auto.html)
- Same thread: when USB-connected to Android Auto, **the head unit's Bluetooth link to the phone
  disconnects** - which, if it holds on Kevin's unit, is a *free win*: the head-unit ACL and any SCO
  go away for the duration of projection, leaving the dongle alone on the radio.
  [forums.androidcentral.com](https://forums.androidcentral.com/android-auto/778601-compatibility-using-obd-2-units-android-auto.html)
- Google's own AA community carries recurring "Bluetooth disconnecting randomly when connected via
  USB" and "Android Auto disconnects Bluetooth audio connection" threads, so head-unit behaviour
  here is inconsistent across units.
  [support.google.com/androidauto/thread/5386001](https://support.google.com/androidauto/thread/5386001/bluetooth-disconnecting-randomly-in-vehicle-connected-with-usb-bluetooth?hl=en)

**Consequence for map ticket 01.** If the head unit drops Bluetooth under wired projection, then a
self-managed telephony call cannot get the **car's** HFP microphone while wired - which is decision
#3's entire reason for existing. That interaction belongs on ticket 01, not here. `inferred`

## 5. Failure shape - what LEGION's code actually observes

All `traced` against `vehicle/` on branch `feat/cyberdeck`.

Path: `ObdBluetoothManager.sendCommand` -> `exchangeLocked` -> `Elm327Io.exchange`
(`drainInput`, `write`, `readUntilPrompt`).

`Elm327Io.readUntilPrompt` is a **polling loop**:

```kotlin
while (System.currentTimeMillis() < deadline) {
    if (input.available() > 0) { ... } else { Thread.sleep(20) }
}
return sb.toString()
```

It never calls a blocking `read()`, so it never sees the one signal AOSP actually raises. AOSP's
`BluetoothSocket.read()` is where disconnect surfaces:

```java
if (ret < 0) {
    mSocketState = SocketState.CLOSED;
    throw new IOException("bt socket closed, read return: " + ret);
}
```
[android.googlesource.com packages/modules/Bluetooth BluetoothSocket.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothSocket.java) `documented`

`available()` in the same file just delegates (`return mSocketIS.available();`) with no error
handling of its own. `documented`

So there are exactly three observable outcomes, and only one of them is the good one:

| Situation | What the code sees | Resulting app state |
|---|---|---|
| Local socket fd torn down by the stack | `available()` or `output.write()` throws `IOException` -> `exchangeLocked` catches -> `disconnect()` | `DISCONNECTED`, connection loop retries every 5 s. **Correct.** |
| Link stalled / quiet but fd still open | `available()` returns 0 for the whole 5 s, `exchange` returns `""` | `isFailureResponse("")` is true (`response.isBlank()`), `consecutivePidSilence++`, three of them fire `reinitProtocolLocked()` (ATPC/ATSP0/0100 - all of which also return `""`), `_connectionState` stays **`CONNECTED`**. **Silent stall.** |
| Any disconnect callback | There is none. `grep` for `ACTION_ACL_DISCONNECTED` / `ACTION_ACL_CONNECTED` across `app/src/main` returns **no matches**. | n/a |

**The ticket's worry is confirmed and is worse than stated.** Radio contention produces the middle
row, and the middle row is byte-identical to a dormant ISO 9141-2 K-line - which is precisely the
case `PID_REINIT_THRESHOLD` and `reinitProtocolLocked` were built for (drive-notes ticket 03,
drive-notes-2 ticket 02). LEGION will run its K-line recovery ritual against a Bluetooth problem,
fail, and keep reporting `CONNECTED`.

**A cheap, real fix exists and is independent of everything else on this page:** the distinction is
recoverable without any new radio API. A blank/`""` response means *nothing arrived on the socket*;
`"NO DATA"`/`"BUS INIT: ERROR"` means *the adapter answered and the car did not*. Today
`isFailureResponse` collapses both into one boolean and `MidnightEvents.obdPidSilence` is the only
place the raw string survives. Splitting "silent socket" from "adapter said no" is a one-function
change in `ObdBluetoothManager.sendCommand` and would let the app say "radio busy" instead of
"car asleep". `traced` + `reasoned`. Not implemented - this is a research note, no source touched.

**Second defect found while tracing.** `BleTransport.GattInputStream` declares
`@Volatile private var closed = false`, sets it in `shutdown()` (called from
`onConnectionStateChange(STATE_DISCONNECTED)`), and **never reads it** - `available()` returns
`buffer.size` and `read()` returns `-1` only when the buffer is empty, neither consults `closed`.
The class KDoc claims a dropped BLE link makes `sendCommand` "see end-of-stream rather than hanging
forever"; it does not. After a GATT drop the BLE path detects nothing on the read side at all, and
recovers only if `gatt.writeCharacteristic()` returns `false` on the next command (which throws
`IOException("writeCharacteristic() returned false")`). If the stack queues that write instead, the
BLE dongle stalls silently and indefinitely. `traced`.

## 6. BLE ELM327 instead of RFCOMM

- BLE runs on the LE physical layer with its own connection events, not on the BR/EDR piconet slot
  map that SCO reserves. So **BLE is structurally less exposed to SCO slot starvation than RFCOMM**.
  Core 5.4 splits these into separate controller volumes (BR/EDR Controller vs Low Energy
  Controller). [bluetooth.com Core-54](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/index-en.html)
  `documented` (structure) / `inferred` (the coexistence conclusion)
- But it is still the same 2.4 GHz radio and the same controller doing the time-slicing, so it trades
  slot-reservation contention for scheduler contention. `inferred`
- And per §5, **the BLE path's failure detection is strictly worse than RFCOMM's**, so switching to
  BLE to dodge contention would make a contention stall *harder* to see. `traced`
- Kevin's V020 is the BLE clone (`DEVICE_NAME_PATTERNS` in `ObdBluetoothManager.kt` names it
  explicitly), so both paths are live in practice, not hypothetical. `traced`

---

## The smallest on-device experiments that settle this

Rig: OPPO A17k (CPH2471), the V020 BLE dongle and/or an RFCOMM ELM327, the real Android Auto head
unit. Diagnostics must be surfaced **in LEGION's UI**, not logcat - the A17k filters the app's own
logs (auto-memory: `oppo-a17k-logcat-filters-app-logs`).

**T1 - Does it coexist at all? (5 minutes, settles Q1 and most of Q4.)**
Connect the dongle, confirm gauges live, then start Android Auto over USB. Watch
`ObdBluetoothManager.connectionState` and one moving gauge (RPM) for 60 s.
- Gauges keep moving -> Q1 answered yes on this hardware, and the map can stop worrying.
- Gauges freeze while state stays `CONNECTED` -> §5's silent stall reproduced, and the fix in §5 is
  now a blocking prerequisite for any car surface.
Also note whether the **head unit's Bluetooth icon drops** when USB projection starts - that single
observation decides the §4 question and feeds ticket 01.

**T2 - Does SCO starve it? (5 minutes, settles Q2.)**
With T1 running, place a phone call and let it route to the car. Record per-command round-trip time
for `010C` before, during, and after the call (a visible counter is enough; no profiling needed).
- Round-trips lengthen but stay under 5000 ms -> existing timeouts absorb it, no work needed.
- Round-trips exceed 5000 ms -> timeouts need raising *while a call is active*, not globally.
- Socket dies -> §5's top row; confirm the app actually reconnects within ~5 s.

**T3 - Wireless AA only if T1 passes wired. (10 minutes, settles Q3.)**
Repeat T1 and T2 with wireless Android Auto. The A17k has 5 GHz so this is possible. The only new
variable is the Wi-Fi Direct leg; if T1/T2 pass wired and fail wireless, the cause is
antenna/front-end sharing on this specific phone and the answer is "use the cable".

**T4 - Failure-shape ground truth (2 minutes, settles Q5 empirically).**
Connect, confirm gauges, then **unplug the dongle from the OBD port** while polling. Record which of
§5's three rows actually happens on this phone: `IOException` -> `DISCONNECTED`, or silent stall at
`CONNECTED`. Do it once on RFCOMM and once on the V020 BLE dongle - §5 predicts they differ, and
that prediction is currently `traced`-from-code, not `on-device`.

T4 is the cheapest and the most valuable: it needs no head unit at all, and it validates or kills
the central claim of this document before anyone spends time on radio mitigations.

---

## Assumptions ledger

| Claim | Tag |
|---|---|
| RFCOMM's "one client per channel" is per-channel, not per-phone | `traced` (Android doc quoted) |
| HFP/A2DP/RFCOMM are distinct logical transports and can coexist | `reasoned` from Core 5.4 section structure; spec body text not read |
| SCO/eSCO reserve slots and tax ACL throughput | `reasoned`; the 1/3-2/3 figures are secondary literature, NOT spec-verified |
| SCO delays RFCOMM rather than closing it | `reasoned` |
| LEGION's 5000 ms timeouts likely absorb SCO contention | `reasoned` from `traced` timeout values |
| Wireless AA needs 5 GHz and Android 11+; BT does the pairing | `traced` (Google support pages quoted) |
| A17k supports 5 GHz and Bluetooth 5.3 | `traced` (OPPO spec page quoted) |
| BT stays connected during wireless AA for call audio | `reasoned` from field reports; no first-party statement found |
| Wired AA drops the head unit's BT link | `reasoned` from a single forum report; contradicted by other AA community threads. **Unsettled - T1 decides.** |
| `Elm327Io` polls `available()` and never blocks on `read()` | `traced` |
| AOSP raises `IOException` from `read()`, not from polling `available()` | `traced` (AOSP source quoted) |
| No `ACTION_ACL_*` receiver exists anywhere in the app | `traced` (grep, zero matches) |
| A stalled link is indistinguishable from a dormant K-line in today's code | `traced` + `reasoned` |
| `GattInputStream.closed` is written and never read; BLE read-side disconnect detection does not exist | `traced` |
| BLE is less exposed to SCO slot reservation than RFCOMM | `reasoned` |
| Any of the above as it behaves on Kevin's actual car | **NOT `on-device`.** Nothing here was run. T1-T4 exist to fix that. |

No source files, ticket files, or `map.md` were modified.
