# Can OBD keep its Bluetooth radio while the phone projects to the car?

Type: research
Status: resolved
Blocked by: -

## Question

Fleet is the aspect that is *most* alive while driving, and it already owns a Bluetooth radio:
`vehicle/` runs an ELM327 adapter over **RFCOMM** (and BLE). In the car, that same phone will
simultaneously be paired to the head unit for **HFP/A2DP**, possibly projecting Android Auto over
that Bluetooth link (wireless AA uses Bluetooth for the handshake and Wi-Fi Direct for the video),
and - if ticket 01 lands well - routing call audio over **SCO**.

That is three or four concurrent Bluetooth profiles on a budget phone (OPPO A17k, `CPH2471`). If OBD
polling dies whenever Kevin plugs in, the single most valuable thing LEGION could say in the car goes
away, and the map should know that before deciding what the car surface is for.

Establish, against primary sources (Android Bluetooth documentation, `BluetoothSocket`/RFCOMM
reference, Bluetooth Core and HFP/A2DP profile specs, Android Auto wireless projection
documentation) plus any credible field reports where the specs are silent:

1. **Can an RFCOMM socket to an ELM327 coexist** with an active HFP + A2DP link to a head unit on the
   same adapter? What is the practical limit on concurrent connections and active profiles on a
   typical single-antenna Android phone?
2. **Does SCO (active call audio) starve RFCOMM?** SCO reserves periodic slots; an RFCOMM data link
   sharing the radio typically loses throughput. Quantify what happens - slower polling, dropped
   frames, dropped socket - and whether the socket survives or must be reconnected.
3. **Wireless Android Auto specifically.** It negotiates over Bluetooth then moves video to Wi-Fi
   Direct on 5 GHz. Does the Bluetooth link stay busy afterwards, and does the Wi-Fi Direct link
   interfere with 2.4 GHz Bluetooth in practice (coexistence, antenna sharing)?
4. **Wired Android Auto** over USB: does it avoid the problem entirely by taking Bluetooth out of the
   projection path? If so, that is a cheap mitigation worth stating.
5. **Failure shape.** When an RFCOMM socket does die under contention, what does the app see -
   `IOException` on read, a silent stall, or a disconnect callback? `vehicle/` needs to distinguish
   "car is off" from "radio is busy", and today it may not.
6. **BLE ELM327 adapters:** does using the BLE path instead of RFCOMM change any of the above?

State which claims are **documented**, which are **inferred**, and which are **field reports** rather
than specification. Name the smallest on-unit test that would settle it - Kevin has both the adapter
and the head unit.

Findings go to `.scratch/android-auto/research/05-obd-bluetooth-contention-while-projecting.md`.

## Answer

**The radios coexist. LEGION's own code is the problem, and it is a real defect in shipped source.**
Full findings and citations:
[research/05-obd-bluetooth-contention-while-projecting.md](../research/05-obd-bluetooth-contention-while-projecting.md).
Resolved 2026-08-13 from a research agent's report; tags are the agent's, carried forward unchanged.
**Nothing was run on device.**

1. **They coexist, structurally** (`documented`). Android's "one client per channel" is per channel
   per device, not per phone. RFCOMM rides ACL, HFP audio rides SCO/eSCO - separate logical
   transports (Core 5.4 Vol 2 Part B §4.6). In-car load is 2 ACL + 1 SCO; the A17k is Bluetooth 5.3
   per OPPO's own spec page.
2. **SCO taxes throughput, it does not evict** (`inferred`). Slot reservation delays ACL rather than
   closing it. **The often-quoted 1/3 to 2/3 HV3 throughput figures are secondary literature, not
   spec-verified** - the SIG's HTML spec renders as navigation only to a fetcher, so §4.6.1's body
   was never read. The existing 5000 ms timeouts probably absorb it.
3. **Wireless Android Auto is LESS risky than the ticket assumed** (`documented` for the band,
   `field-report` for the rest): bulk projection traffic is on 5 GHz Wi-Fi Direct, a different band
   from BR/EDR. No first-party statement was found on whether the Bluetooth link stays busy after
   handshake.
4. **Wired USB is only a partial mitigation, and it is UNSETTLED.** It removes the Wi-Fi Direct leg,
   not Bluetooth. One field report has the head unit's Bluetooth link dropping under USB projection;
   Google's own community threads contradict each other. **If it does drop, settled decision 3 is
   threatened** - the call disguise exists to get the car's HFP microphone, and no HFP link means no
   car microphone. Flagged onto ticket 01.
5. **The load-bearing finding, `traced` in the real source.** `Elm327Io.readUntilPrompt` polls
   `input.available()` in a 20 ms sleep loop and **never calls a blocking `read()`**. AOSP raises the
   disconnect signal from `read()`; `available()` delegates with no error handling. So a link that
   goes **quiet** - exactly what contention produces - returns `""` after 5 s. `isFailureResponse("")`
   is true via `isBlank()`, which increments `consecutivePidSilence`, fires `reinitProtocolLocked()`
   (whose ATPC/ATSP0/0100 all also return `""`), and leaves `_connectionState` at **`CONNECTED`**.
   That is byte-identical to a dormant ISO 9141-2 K-line, so **the app runs its K-line recovery
   ritual against a Bluetooth problem and reports the car as fine.** Only a torn-down fd throws and
   reaches `disconnect()`. There is no disconnect callback at all: `ACTION_ACL_DISCONNECTED` and
   `ACTION_ACL_CONNECTED` return **zero matches** across `app/src/main`.
   The fix is cheap and local to `sendCommand`: `""` means nothing arrived on the socket, while
   `"NO DATA"` / `"BUS INIT: ERROR"` means the adapter answered and the car did not. Today both
   collapse into one boolean.
6. **BLE is worse, not better** (`traced`). `BleTransport.GattInputStream.closed` is `@Volatile`, set
   by `shutdown()` on GATT disconnect, and **never read** - neither `available()` nor `read()`
   consults it. The class KDoc claims a dropped link surfaces as end-of-stream. It does not. BLE
   recovers only if the next `gatt.writeCharacteristic()` happens to return false. Switching to BLE
   to dodge contention makes a stall **harder** to see.

**Named experiments:** T1 wired coexistence (5 min, also settles Q4 by watching the head unit's
Bluetooth icon), T2 place a call and time `010C` round-trips, T3 the same wireless, T4 yank the
dongle mid-poll on both RFCOMM and BLE. **T4 is cheapest, needs no head unit, and either promotes
finding 5 from `traced` to `tested` or kills it.** Graduated into ticket 13.
