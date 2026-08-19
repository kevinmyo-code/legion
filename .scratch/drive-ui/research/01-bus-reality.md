# What cadence can a 1998 Jeep XJ's ISO 9141-2 bus actually sustain?

Research for ticket `issues/01-bus-reality-research.md`. Charted 2026-08-16.

Target: 1998 Jeep Cherokee (XJ), ELM327 over Bluetooth SPP, ISO 9141-2 slow init
(`ObdBluetoothManager.kt:780-784`), one PID per round trip behind `commandMutex`, 5000ms timeout.

## Source-type key

Every claim below is tagged. The three kinds are not interchangeable.

| Tag | Meaning |
|---|---|
| **[STANDARD]** | ISO 15031-5:2006 (= SAE J1979) or ISO 14230-2, read directly. Normative. |
| **[VENDOR]** | Elm Electronics ELM327 datasheet, rev. J (`ELM327DSJ`, 94 pp). Describes one chip's behaviour, not the bus. |
| **[3P-TECH]** | Third-party technical writing with no standing. Better than a forum, not a standard. |
| **[COMMUNITY]** | Forums, project issue trackers, blogs. Anecdote. |
| **[DERIVED]** | Arithmetic done here from tagged inputs. The inputs are cited; the sum is mine. |
| **[CODE]** | Read out of this repo. |

Primary sources used:

- ISO 15031-5:2006 (BS ISO 15031-5:2006), full text —
  <https://share.qclt.com/%E6%B1%BD%E8%BD%A6%E8%AF%8A%E6%96%AD%E5%8D%8F%E8%AE%AE2/ISO-15031-5%5B1%5D.pdf>
  (ISO catalogue entry: <https://www.iso.org/standard/50816.html>)
- ELM327 datasheet rev. J — <https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf>
- ISO/DIS 14230-2 (KWP2000 data link layer) —
  <https://www.internetsomething.com/kwp/KWP2000%20ISO%2014230-2%20KLine%20.pdf>
- K-Line & ISO 9141 deep dive, testerpresent.com.au —
  <https://testerpresent.com.au/DiagInfo/kline_iso9141_deep_dive.pdf>

---

## Q1. Is multi-PID batching possible on ISO 9141-2? **NO. Confirmed, twice, independently.**

This is the load-bearing answer in the ticket and it is not close.

### The standard says so structurally

ISO 15031-5:2006 splits its service definitions into two clauses by protocol family:

- **Clause 6** — "Diagnostic service definition for ISO 9141-2, ISO 14230-4, and SAE J1850"
- **Clause 7** — "Diagnostic service definition for ISO 15765-4" (CAN)

**[STANDARD]** Clause 6.1.2.3, **Table 18** — "Request current powertrain diagnostic data request message
(read PID value)", the K-line/J1850 form. The entire request is two data bytes:

| Data Byte | Parameter Name | Cvt | Hex Value |
|---|---|---|---|
| #1 | Request current powertrain diagnostic data request SID | M | 01 |
| #2 | PID (see Annex B) | M/C | xx |

There is no byte #3. The prose under Table 19 reinforces the singular: "The PID, which is included in
the request message may be supported by all emission-related ECUs."
(ISO 15031-5:2006 §6.1.2.3-6.1.2.4)

**[STANDARD]** Clause 7.1.2.3, **Table 127** — the CAN form of the *same* request:

| Data Byte | Parameter Name | Cvt | Hex Value |
|---|---|---|---|
| #1 | Request current powertrain diagnostic data request SID | M | 01 |
| #2 | PID#1 (see Annex B) | M | xx |
| #3 | PID#2 | **U** | xx |
| #4 | PID#3 | **U** | xx |
| #5 | PID#4 | **U** | xx |
| #6 | PID#5 | **U** | xx |
| #7 | PID#6 | **U** | xx |

with the footnote "U = User Optional -- the parameter may be present or not."
(ISO 15031-5:2006 §7.1.2.3, Table 127)

Six PIDs, and only in the CAN clause. The optional PID#2-#6 rows exist in Table 127 and do not exist
in Table 18. That is the whole answer.

**[STANDARD]** Corroborating: §5.2.4.2 ("ISO 15765-4 -- Data not available") is the only place in the
document that contemplates partial multi-PID responses — "If the external test equipment sends a
message including multiple PIDs and each emission-related ECU does not support all requested PIDs,
then each ECU shall send a positive response message including the supported PID(s)". Its K-line
counterpart §5.2.4.1 has no such paragraph.

### The vendor says so in plain English

**[VENDOR]** ELM327 datasheet rev. J, p. 44, section titled **"Multiple PID Requests"**:

> "The SAE J1979 (ISO 15031-5) standard allows requesting multiple PIDs with one message, but only if
> you connect to the vehicle with CAN (ISO 15765-4). Up to six parameters may be requested at once,
> and the reply is one message that contains all of the responses."

**[COMMUNITY]** python-OBD issue #31 reaches the same conclusion citing the same datasheet page:
"up to 6 PIDs can be sampled with a single command... can only be done with the CAN (ISO 15765-4)
protocol." <https://github.com/brendan-w/python-OBD/issues/31>

### What would physically happen if you tried anyway

**[VENDOR]** An ISO 9141-2 frame has room: "The J1850, ISO 9141-2, and ISO 14230-4 protocols all use
essentially the same structure, with three header bytes, a maximum of seven data bytes and one
checksum byte" (datasheet p. 38). So `01 0C 0D 05 04 10` (6 data bytes) *fits* on the wire and the
ELM327 would transmit it.

**[DERIVED]** The frame fitting is irrelevant. A 1998 ECU built to clause 6 has no parse rule for
byte #3 onward. Expected outcome is `NO DATA` (K-line ECUs are forbidden from sending a reject to an
unsupported functional request — ISO 15031-5 §5.2.4.1 **[STANDARD]**), so a failed batch attempt costs
a full timeout and returns nothing. There is also a hard structural ceiling on the *reply* even if an
ECU were generous: 7 data bytes max means `41` + at most 3 two-byte PIDs, and clause 6 defines no
multi-record response format for K-line at all.

**Verdict: one PID per round trip is a hard ceiling on this car.** Design the whole map around it.

---

## Q2. Round-trip floor the protocol itself imposes

### The timing parameters

**[STANDARD]** ISO 15031-5:2006 §5.2.2.2 defers P1 and P4 to ISO 9141-2 itself and specifies P2/P3 in
**Table 2 — Definition ISO 9141-2 application timing parameter values**:

| Parameter | Min (ms) | Max (ms) | Description (abridged from the standard) |
|---|---|---|---|
| P2K-line (Key Bytes $08 $08) | 25 | 50 | Request → start of ECU response. "Each OBD ECU shall start sending its response message within P2K-line after the request message has been correctly received." |
| P2K-line (Key Bytes $94 $94) | 0 | 50 | as above |
| P3K-line | 55 | 5000 | End of ECU response(s) → start of the tester's next request. "The external test equipment may send a new request message if all response messages related to the previously sent request message have been received and if P3K-line minimum time expired." |

**[3P-TECH]** P1 and P4 (which ISO 15031-5 hands off to ISO 9141-2, a paywalled document not obtained
here) are given by testerpresent.com.au's K-line deep dive as:

| Param | Meaning | ms |
|---|---|---|
| P1 | Inter-byte, ECU Tx | 0-20 |
| P2 | ECU response after tester request | 25-50 |
| P3 | Inter-message, tester Tx | 55-5000 |
| P4 | Inter-byte, tester Tx | 5-20 |

That table's P2 and P3 match ISO 15031-5 Table 2 exactly, which is decent evidence the P1/P4 rows are
also faithful. **[STANDARD]** ISO 14230-2 §5.2.4 confirms the *definitions* (P1 = inter-byte ECU
response, P4 = inter-byte tester request) even though KWP's default values differ.
**[VENDOR]** The ELM327 independently corroborates P4min ≈ 5 ms: programmable parameter `PP 14`,
"ISO/KWP final stop bit width (provides P4 interbyte time)", defaults to **5.2 ms**
(datasheet p. 71). `PP 15`, "ISO/KWP maximum inter-byte time (P1), and also used for the minimum
inter-message time (P2)", defaults to **21 ms**. `PP 1D`, "ISO/KWP P3 time (delay before sending
requests)", defaults to **59 ms** — just above the standard's P3min of 55.

### Framing

**[3P-TECH]** A functionally addressed ISO 9141-2 mode-01 exchange (testerpresent, §5, §7):

```
request   68 6A F1 01 0C CS          3 header + 2 data + 1 checksum = 6 bytes
response  48 6B 10 41 0C A B CS      3 header + 4 data + 1 checksum = 8 bytes
```

**[STANDARD]** 10.4 kbaud line rate; **[DERIVED]** 8N1 = 10 bits/byte = **961.5 µs/byte**.

### The floor

**[DERIVED]** from the above, single responding ECU, one PID:

| Phase | Best in-spec | ELM327 defaults | Typical |
|---|---|---|---|
| Request TX (6 bytes + 5×P4) | 5.8 + 25 = **30.8 ms** | 5.8 + 26 = 31.8 ms | 31.8 ms |
| P2 (ECU turnaround) | **25 ms** | 25 ms | 40 ms |
| Response TX (8 bytes + 7×P1) | 7.7 + 0 = **7.7 ms** | 7.7 ms | 7.7 + 35 = 42.7 ms |
| P3 (before next request) | **55 ms** | 59 ms | 59 ms |
| **Total** | **≈ 119 ms** | ≈ 124 ms | ≈ 174 ms |
| **PIDs/sec** | **8.4** | 8.1 | 5.8 |

**The protocol floor is ~119 ms per PID — about 8.4 PIDs/second — and that is with every parameter at
its most favourable in-spec value and exactly one ECU answering.** Realistic is 5-6/s.

Worst legal case is far worse: P4=20, P1=20, P2=50 gives 106 + 50 + 148 + 55 = **359 ms per PID**,
under 3/s, without breaking any rule.

### Two structural aggravators

**[STANDARD]** §5.2.1: "In some vehicles, multiple ECUs may respond with the information requested."
Each extra responder adds its own 8-byte frame plus a P2 gap (P2 also governs the spacing between
consecutive response messages), and — worse — makes it impossible for the tester to know when the
replies have stopped without waiting out a timeout.

**[VENDOR]** ISO 9141-2 bus init is expensive and the session is perishable: "The ISO 9141 standard
allows for only a slow (2 to 3 second) initiation process" (datasheet p. 32), and "Once the bus has
been initiated, communications must take place regularly (typically at least once every five
seconds), or the bus will revert to a low-power 'sleep' mode." That 5 s figure is P3max
**[STANDARD]**. The ELM327 sends its own keep-alive every 3 s by default (`AT SW`, `PP 17` = 3.0 s)
**[VENDOR]**. `ObdBluetoothManager.reinitProtocolLocked` already exists because this bites on the
real car **[CODE]**.

---

## Q3. What the ELM327 adds

### Its own processing latency

**Not established.** The datasheet states no figure for internal turnaround. What it does state:

**[VENDOR]** Default RS232 rate is 38400 baud (datasheet p. 4, p. 33: "9600 or 38400 baud, with 8 data
bits, and no parity"). **[DERIVED]** at 38400 8N1 = 260 µs/char: `010C\r` out = 5 chars = **1.3 ms**;
`41 0C 1A F8\r\r>` back ≈ 14 chars = **3.6 ms**. So the serial leg is ~5 ms of the round trip. Below
the noise floor of P2 alone.

### `ATE0` (echo off) — already set

**[VENDOR]** "These commands control whether or not the characters received on the RS232 port are
echoed (retransmitted) back to the host computer... The default is E1 (or echo on)" (datasheet p. 17).
**[CODE]** `ObdBluetoothManager.kt:598` issues `ATE0` during connect.

**[DERIVED]** The saving is exactly the echoed command bytes: 5 chars ≈ **1.3 ms per command** at
38400. It is worth having (it also removes a parsing hazard), but it is **not** a throughput lever.
Anyone claiming `ATE0` meaningfully speeds up scanning is confusing hygiene with performance.

### Adaptive timing `ATAT0/1/2` — exists, and is ON by default here

**[VENDOR]** Datasheet p. 22 and p. 52:

> "The Adaptive Timing feature automatically sets the timeout value for you, to a value that is based
> on the actual response times that your vehicle is responding in... always using your AT ST hh
> setting as the maximum setting, and will never choose one which is longer."
>
> "By default, Adaptive Timing option 1 (AT1) is enabled, and is the recommended setting. AT0 is used
> to disable Adaptive Timing (so the timeout is always as set by AT ST), while AT2 is a more
> aggressive version of AT1 (the effect is more noticeable for very slow connections -- you may not
> see much difference with faster OBD systems). The J1939 protocol does not support Adaptive Timing."

**[VENDOR]** `PP 04`, "Default Adaptive Timing mode (AT AT setting)", defaults to `01`.
**[VENDOR]** `PP 03`, "NO DATA timeout time (AT ST default setting)", defaults to `32` hex =
**204.8 ms**.

**[CODE]** LEGION's init is `ATZ`, `ATE0`, `ATL0`, `ATSP0` (`ObdBluetoothManager.kt:597-600`). It never
sends `ATAT` or `ATST`, so the adapter runs **AT1 with ST = 205 ms**. That is the sane default, and
"ISO 9141-2 is a very slow connection" is precisely the case where the datasheet says `ATAT2` might
help — untested here, worth a measured A/B in ticket 02.

### The real ELM327 cost: the trailing wait for more responses

This, not echo and not the chip, is where adapter-side time goes.

**[VENDOR]** Datasheet p. 32 and p. 51-52:

> "the replies often consist of several separate messages, either from multiple ECUs responding, or
> from one ECU providing messages that need to be combined... In order to be adaptable to this
> variable number of responses, the ELM327 normally waits to see if any more are coming."
>
> "After each reply has been received, the ELM327 must wait to see if any more replies are coming...
> If a typical vehicle query response time were about 50 msec, and the timeout were set to 200 msec,
> the fastest scan rate possible would only be about 4 queries per second."

**[VENDOR]** The documented fix is the **responses digit** — append a hex digit to the request naming
how many response lines to expect:

> "Simply add a single hex digit after the OBD request bytes... For example, if you know that there is
> only one response coming for the engine temperature request that was previously discussed, you can
> send: `01 05 1` and the ELM327 will return immediately after obtaining only one response."

**[VENDOR]** Its worked example (a J1850 VPW car, *not* K-line — do not transplant the numbers): a
90 ms adaptive timeout gives "about 6 readings per second"; adding the responses digit "might give you
10 to 12 responses per second, instead of the 6 obtained previously."

Two things matter for LEGION:

1. The responses digit is documented in the general "Talking to the Vehicle" chapter with plain
   mode-01 examples (`01 05 1`, `09 02 5`), **not** in the CAN chapter. It is not CAN-only.
   **[CODE]** LEGION does not use it anywhere — every request is a bare `010C`/`0105`/etc.
2. **[VENDOR] The datasheet's own warning applies directly:** "always determine the number of
   responses that will be coming from the vehicle, and then set the responses digit to that value."
   Setting it too low on a protocol needing acknowledgements causes retransmission storms. On this
   car the count must be *measured* (does the TCM answer too?), never assumed to be 1.

**Not established:** whether the ELM327 runs its response-wait timer and its P3 delay concurrently or
back to back. If concurrent, the responses digit only buys back the excess over ~59 ms and the gain is
modest; if sequential, it buys back the whole adaptive timeout. The datasheet does not say. This is
measurable in ticket 02 and materially changes the payoff.

### `ATSP0` auto-search — free per command once settled

**[VENDOR]** Datasheet p. 27: "since it is used so often, and since writes to EEPROM result in an
unnecessary delay (of about 30 msec), the AT SP0 command sets the protocol to 0, but does not perform
a write to EEPROM." So even the command itself skips the EEPROM cost.

**[VENDOR]** Once a protocol is found it becomes the active protocol; the search runs again only when
a connection attempt fails ("the next time the ELM327 fails to connect to the saved protocol, it will
again search all protocols"). **No per-command cost once settled.**

**[VENDOR]** But the failure mode is expensive: a re-search on a car whose only working protocol is
ISO 9141-2 pays the 2-3 second slow init, and may pay failed attempts at other protocols first
(`PP 13`, "Time delay added between protocols 1 & 2 during a search", alone defaults to 498 ms).
**[CODE]** `reinitProtocolLocked` (`ObdBluetoothManager.kt:870-872`) does `ATPC` → `ATSP0` → `0100`,
so each recovery costs seconds, not milliseconds. Correct call, but it means recovery events should be
counted and rare, not routine.

---

## Q4. PID support on a 1998 Chrysler/Jeep

**Caveat up front, and it is not pedantry:** ISO 15031-5:2006 postdates the vehicle by eight years.
A 1998 XJ was certified against the then-current SAE J1979 and ISO 9141-2 plus CARB requirements. The
support *conditions* quoted below are the best normative statement available and are stable across
revisions, but they are not literally the document the car was built to.

**[STANDARD]** The only universal guarantee: "Service $01 with PID $00 is defined as the universal
'initialization/keep alive/ping' message for all emissions-related OBD ECUs" and "shall be supported
by all ECUs that respond to a Service $01 request" (§5.2.1, §6.1.1). **Everything else must be read out
of PID $00 / $20's support bitmask on the actual car.** **[CODE]** `ObdBluetoothManager` already
maintains `_supportedPids`, so this is a read, not a build.

| PID | Parameter | Standard's support condition **[STANDARD]**, Annex B | Plausible on a 1998 XJ 4.0? |
|---|---|---|---|
| `0104` | Calculated engine load | "**Both spark-ignition and compression-ignition engines shall support PID $04.**" (Table B.5) | **Yes — closest thing to mandatory in the list.** |
| `0105` | Engine coolant temperature | "shall display engine coolant temperature derived from an engine coolant temperature sensor or a cylinder head temperature sensor" (Table B.6) | **Yes.** ECT is a fuelling input on this engine. |
| `0106` | Short term fuel trim, bank 1 | "shall indicate the correction being utilized by the closed-loop fuel algorithm" (Table B.7) | **Yes.** O2-sensor closed loop. |
| `0107` | Long term fuel trim, bank 1 | "**If long-term fuel trim is not utilized at all by the fuel control algorithm, the PID shall not be supported.**" (Table B.8) | **Likely yes.** Chrysler adaptive memory is well documented; confirm from `0100`. |
| `010C` | Engine RPM | No conditional wording (Table B.13) | **Yes.** |
| `010D` | Vehicle speed | No conditional wording (Table B.14) | **Yes.** |
| `010F` | Intake air temperature | "shall display intake manifold air temperature, **if utilized by the control module strategy**... may be obtained directly from a sensor, or may be inferred" (Table B.16) | **Yes.** Speed density needs IAT to compute charge density; the sensor is present. |
| `0110` | MAF air flow rate | "shall display the airflow rate as measured by **a vehicle that utilizes a MAF sensor or an equivalent source**" (Table B.17) | **NO. See below.** |
| `012F` | Fuel tank level input | "shall indicate nominal fuel tank liquid fill capacity as a percent of maximum, **if utilized by the control module for OBD monitoring**" (Table B.35) | **Not established. Assume no until measured.** Lives in the $21-$40 block, so it also requires PID $20 support. |

### MAF: `0110` is almost certainly dead on this car, and that kills MAF-based instantaneous mpg

**[COMMUNITY]**, but consistently across many independent sources: the Jeep 4.0L I6 is a
**speed-density** system with **no mass air flow sensor at all**. It computes airflow from MAP + IAT +
RPM.

- <https://www.cherokeeforum.com/f2/mass-airflow-sensor-4794/> — "Cherokee's use 'speed density',
  speed density systems do not use a mass airflow (MAF) sensor."
- <https://wranglertjforum.com/threads/how-should-my-4-0-run-when-unplugging-the-airflow-sensor.34959/>
  — the 4.0L "does not use MAF... has a MAP sensor instead."
- <https://troubleshootmyvehicle.com/jeep/4000/how-to-test-the-map-sensor> — the 4.0L uses "a 'speed
  density' type of multi-port fuel injection system", MAP being "one of the most critical components
  the PCM uses to calculate the amount of air entering the engine."

**[STANDARD]** cross-checks this cleanly. Annex B Table B.12 (PID $0B, MAP): "MAP shall display
manifold pressure derived from a Manifold Absolute Pressure sensor, **if a sensor is utilized. If a
vehicle uses both a MAP and MAF sensor, both the MAP and MAF PIDs shall be supported.**" A
MAP-only vehicle supports `010B` and is under no obligation to support `0110`. Table B.17 conditions
`0110` on the vehicle actually having a MAF or equivalent.

**Consequence, stated plainly: MAF-derived instantaneous mpg is very probably impossible on this car.**
`ObdBluetoothManager.getMaf()` and `TelemetryRecorder`'s `0110` reads **[CODE]** will most likely
return `NO DATA` forever, and every failed read costs a full round trip (though `TelemetryRecorder`'s
`MAX_CONSECUTIVE_FAILS` gate should already be dropping it).

Fallbacks, both worse and both needing their own decision:

1. **Speed-density MAF estimate** from `010B` (MAP) + `010F` (IAT) + `010C` (RPM) + engine
   displacement + an assumed volumetric efficiency. That is 3 PIDs *just for the airflow term*, before
   speed. On a bus that does ~6 PIDs/s, an mpg number would refresh under 1.5 Hz — and it is an
   **estimate**, so per CLAUDE.md §7 it must be labelled one everywhere it appears, never rendered as
   a measured figure.
2. **`0104` (calculated load) as a proxy** — one PID, and it is the PCM's own airflow-derived number,
   but it is normalised percent, not g/s, so converting it to fuel flow reintroduces the same
   assumptions.

**Recommendation:** do not build instantaneous mpg on this car until ticket 02 has read `0100`/`0120`
and settled `0110` and `010B` empirically. If `0110` is absent, treat instantaneous mpg as an estimate
feature or drop it; trip-average mpg from `010D` + tank refill data is honest and costs one PID.

---

## Q5. Bluetooth SPP contribution — noise against the bus, with one cheap self-inflicted exception

### The transport

**[3P-TECH]** Bluetooth BR/EDR is slotted at **625 µs**, master transmits in even slots and the slave
in odd, so a poll-plus-data exchange is at minimum 2 slots = **1.25 ms**.
<https://circuitlabs.net/bluetooth-classic-introduction-and-architecture/>

**[3P-TECH]** SPP rides RFCOMM, "a simple reliable data stream... similar to TCP", over that baseband.
<https://community.infineon.com/t5/Knowledge-Base-Articles/FAQs-on-Bluetooth-Serial-Port-Profile-SPP/ta-p/915028>

**[3P-TECH]** Academic modelling of SPP minimum end-to-end delay exists (Morón, Luque, Casilari,
Díaz-Estrella, *Electronics Letters* 44(18), 2008, "Minimum delay bound in Bluetooth transmissions with
serial port profile"; and *Electronics Letters* 46(13), 2010 for 2.0+EDR) but the numeric bounds sit
behind a paywall and were not obtained. <https://digital-library.theiet.org/doi/10.1049/el.2010.1108>

**[DERIVED]** Order of magnitude: a few milliseconds each way for a sub-20-byte payload, against a
119-359 ms bus round trip. **Under 5%. Noise.** The transport is not the problem, and swapping
Bluetooth for anything else buys nothing.

Caveat **[DERIVED]**: if the phone's Bluetooth controller parks the link in *sniff mode* during idle
gaps, the first packet after an idle period waits for the next sniff anchor, which can be tens of ms.
LEGION polls continuously during a drive so the link should stay active, but this is a real
measurable-only effect, not a settled one.

### `Elm327Io`'s read loop — small, real, and nearly free to remove

**[CODE]** `Elm327Io.kt:43-59`:

```kotlin
while (System.currentTimeMillis() < deadline) {
    if (input.available() > 0) {
        val n = input.read(buffer)      // one byte at a time
        ...
    } else {
        Thread.sleep(20)               // <-- here
    }
}
```

**[DERIVED]** The 20 ms sleep only fires when the stream is empty, which for a K-line PID read is
essentially the entire 119-359 ms the bus is busy. The cost is not 20 ms per byte — it is **up to 20 ms
of latency on detecting the terminating `>`**, averaging ~10 ms, once per exchange. Against a ~175 ms
typical round trip that is **~6%**. Against the ~119 ms floor it is ~8%.

Verdict: **not the bottleneck, but the cheapest millisecond in the whole stack.** A blocking
`input.read()` (RFCOMM's `InputStream.read()` blocks until data or socket close) with the existing
deadline enforced by the caller's timeout removes it outright. Reading one byte at a time is itself
fine — the volume is ~20 bytes per exchange.

**Not established:** the actual behaviour of `available()` on Android's `BluetoothSocket` input stream
under load. It is documented to return the bytes readable without blocking, but its interaction with
the stack's internal buffering is not something to assume from the docs.

---

## Bottom line

**Realistic sustained refresh for a 3-PID gauge set on this specific car: ~1.3 to ~2.2 full refreshes
per second as the code stands today (450-750 ms per cycle). Best plausible after tuning — responses
digit, `ATAT2`, killing the 20 ms sleep — roughly 2.4 to 2.7 Hz (370-420 ms per cycle). The protocol
floor, unreachable in practice, is 2.8 Hz.**

Derivation **[DERIVED]**: 3 PIDs × (119 ms absolute floor / ~150-250 ms realistic per PID), serialised
because Q1 forecloses batching. Bluetooth and the read loop together move this by under 15%.

Design consequences that follow without further measurement:

1. **A "live" gauge on this car updates about twice a second per PID set. Not 10 Hz, not 5 Hz.** Any
   UI that implies smooth continuous motion is lying about the bus. Design for a value that steps.
2. **Every PID added to the polled set costs ~150-250 ms of cycle time, linearly.** Adding a fourth
   PID drops the set from ~2 Hz to ~1.5 Hz. The polled set is a budget, and it should be explicit.
3. **`0110` should probably come out of the hot loop entirely** (see Q4), and MAF-based instantaneous
   mpg should not be planned until `0100` says otherwise.

### The single biggest source of uncertainty

**How many ECUs answer a functional mode-01 request on this vehicle, and what P1/P2 the Chrysler PCM
actually uses.** These are worth 2-3× on the final number:

- One responder at P1≈0 gives ~124 ms/PID. Two responders, or P1≈5-10 ms, gives 220-300 ms/PID.
- **[STANDARD]** ISO 15031-5 gives P2 a 25 ms *range* and P1 a 20 ms range, and vehicle manufacturers
  are explicitly told to pick their own values inside it ("It is the vehicle manufacturer's
  responsibility to specify a shorter P2 timing window than specified in this part of ISO 15031",
  §5.2.2.1). The standard bounds the answer; it does not give it.
- Whether the ELM327's response-wait and P3 delay overlap (Q3) decides whether the responses digit is
  worth 5 ms or 90 ms per PID.

**This needs measuring on the car. Ticket 02 exists to do exactly that.** Nothing above substitutes
for a stopwatch on the real bus, and the numbers here should be treated as the shape of the answer,
not the answer. What ticket 02 must capture, at minimum:

1. `0100` and `0120` bitmasks — settles `0110`, `010B`, `012F` and the whole polled set.
2. Wall-clock per-PID round trip for `010C` over a few hundred samples: mean, p50, p95.
3. Response line count per request with `ATH1` on — settles the responses digit.
4. A/B: bare `010C` vs `010C1`; `ATAT1` vs `ATAT2`; blocking read vs the 20 ms sleep.
5. How often `reinitProtocolLocked` fires during a real drive.

## Assumptions ledger

| Claim | Tag |
|---|---|
| Multi-PID batching is CAN-only; Table 18 vs Table 127 | **traced** — read directly in ISO 15031-5:2006 §6.1.2.3 / §7.1.2.3 |
| ELM327 datasheet p. 44 says the same in prose | **traced** — read directly in ELM327DSJ |
| P2 25-50 ms, P3 55-5000 ms for ISO 9141-2 | **traced** — ISO 15031-5:2006 Table 2 |
| P1 0-20 ms, P4 5-20 ms | **reasoned** — from a third-party table whose P2/P3 rows match the standard exactly, plus ELM327 `PP 14`/`PP 15` defaults. ISO 9141-2 itself not obtained. |
| ELM327 defaults: ST 205 ms, AT1, P4 5.2 ms, P3 59 ms, P1 21 ms | **traced** — ELM327DSJ Programmable Parameter Summary |
| LEGION sends ATZ/ATE0/ATL0/ATSP0 and no ATAT/ATST, no responses digit | **traced** — `ObdBluetoothManager.kt:597-600`, grep of the vehicle package |
| ~119 ms floor, ~175 ms typical, 1.3-2.2 Hz for 3 PIDs | **reasoned** — arithmetic on the above. Not measured on any car. |
| Jeep 4.0L is speed-density with no MAF | **reasoned** — multiple independent community sources agreeing, consistent with the standard's conditional wording for PID $10 vs $0B. No FSM page obtained. |
| PID $04 mandatory for SI and CI engines | **traced** — ISO 15031-5:2006 Annex B Table B.5 |
| `012F` support on this car | **not established** |
| ELM327 internal processing latency | **not established** — no vendor figure exists |
| Whether ELM327's response-wait overlaps P3 | **not established** |
| Bluetooth SPP ≈ 1.25 ms minimum slot round trip; <5% of the budget | **reasoned** — from the 625 µs slot structure; no ELM327-specific measurement found |
| The 20 ms sleep costs ~10 ms mean per exchange | **reasoned** — from reading `Elm327Io.kt:43-59`. Not instrumented. |
