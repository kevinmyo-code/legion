# What cadence can this car's bus actually sustain?

Type: research
Status: resolved
Blocked by: -

## Question

Every cadence decision on this map is guesswork until this resolves. Kevin's car is a **1998 Jeep
Cherokee (XJ)**, which `ObdBluetoothManager.kt:780-784` records as negotiating **ISO 9141-2 slow
init**. LEGION talks to it through an ELM327 over Bluetooth SPP, one PID per round trip, serialised
on `commandMutex`, with no batching anywhere in the codebase.

Establish, from ELM327 documentation, the OBD-II standards, and reputable technical sources:

1. **Is multi-PID batching even possible here?** A single request returning several PIDs
   (`01 0C 0D 05`) is widely described as a CAN-mode feature. Confirm or refute for **ISO 9141-2**
   specifically. If batching is CAN-only, that is a hard ceiling on this car and the whole map must
   design around one-PID-per-trip.
2. **Realistic round-trip time per PID on ISO 9141-2.** The protocol's own timing rules (inter-byte
   spacing, P1-P4 timings, bus initialisation) bound this independently of the adapter. Give the
   floor the protocol imposes, not a vendor's best case.
3. **What the ELM327 adds.** Its own processing latency, the effect of `ATE0` (echo off, already
   set), whether adaptive timing (`ATAT0/1/2`) is in use or available and what it changes, and
   whether `ATSP0` auto-search costs anything per command once settled.
4. **PID support on a 1998 XJ.** Which of the PIDs LEGION already asks for are plausibly supported
   on a pre-2001 Chrysler: `010C`, `010D`, `0105`, `0104`, `0110` (MAF), `012F`, `0106`, `0107`,
   `010F`. MAF matters specifically because instantaneous mpg depends on it.
5. **Bluetooth SPP contribution.** Whether the transport adds meaningful latency versus the bus
   itself, and whether `Elm327Io`'s byte-at-a-time read with a 20ms idle sleep
   (`Elm327Io.kt:43-59`) is a material cost at these speeds or noise against the bus.

Write findings to `research/01-bus-reality.md`, cite every claim to its source URL, then append the
Answer here and set Status: resolved.

**State plainly which claims are vendor marketing, which are the standard, and which are community
report.** A number from a forum is not the same as a number from ISO 9141-2.

## Answer

Full findings with per-claim citations and source-type labels: `research/01-bus-reality.md`.
Source tags below: **[STD]** = ISO 15031-5:2006 (= SAE J1979), read directly. **[VEN]** = ELM327
datasheet rev. J. **[3P]** = third-party technical doc. **[COM]** = forum. **[DER]** = arithmetic
done here. **[CODE]** = this repo.

**1. Batching is CAN-only. Confirmed twice, independently. One PID per trip is a hard ceiling.**

- **[STD]** ISO 15031-5 splits service definitions by protocol family. Clause 6 (ISO 9141-2 /
  ISO 14230-4 / J1850) **Table 18**: the mode-01 request is two data bytes, `01` + one PID. There is
  no byte #3. Clause 7 (ISO 15765-4 / CAN) **Table 127**: `01` + PID#1 mandatory + PID#2..PID#6 marked
  "U = User Optional". Six PIDs, CAN clause only.
- **[VEN]** Datasheet p. 44, section "Multiple PID Requests": "The SAE J1979 (ISO 15031-5) standard
  allows requesting multiple PIDs with one message, but only if you connect to the vehicle with CAN
  (ISO 15765-4)."
- **[VEN]** A 6-byte data field physically *fits* an ISO 9141-2 frame (3 header + max 7 data + checksum),
  so the ELM327 would transmit it. **[STD]** A clause-6 ECU has no parse rule for it and is forbidden
  from rejecting an unsupported functional request (§5.2.4.1), so the attempt costs a full timeout and
  returns nothing.

**2. Protocol floor ≈ 119 ms per PID (~8.4/s). Realistic 150-250 ms (~4-6/s).**

**[STD]** ISO 15031-5 Table 2: P2K-line 25-50 ms, P3K-line 55-5000 ms. **[3P]** P1 (ECU inter-byte)
0-20 ms, P4 (tester inter-byte) 5-20 ms — from testerpresent.com.au, whose P2/P3 rows match the
standard exactly, and corroborated by **[VEN]** `PP 14` (P4) defaulting to 5.2 ms.
**[STD]** 10.4 kbaud; **[DER]** 8N1 = 961.5 µs/byte. **[3P]** request 6 bytes, response 8 bytes.

**[DER]** best in-spec: 30.8 (request TX) + 25 (P2) + 7.7 (response TX) + 55 (P3) = **119 ms**.
Typical: 31.8 + 40 + 42.7 + 59 = **174 ms**. Worst legal: **359 ms**. Independent of any adapter.

**3. ELM327: echo is hygiene, the trailing response-wait is the cost, `ATSP0` is free.**

- **[DER]** `ATE0` saves the 5 echoed command chars ≈ **1.3 ms** at 38400. Not a throughput lever.
- **[VEN]** Adaptive timing exists; AT1 is the power-on default (`PP 04` = 01) and `ATST` defaults to
  **205 ms** (`PP 03` = 32h). **[CODE]** LEGION sends `ATZ/ATE0/ATL0/ATSP0` only
  (`ObdBluetoothManager.kt:597-600`), so the adapter runs AT1 / ST 205 ms. `ATAT2` is the documented
  option for "very slow connections" and is untested here.
- **[VEN]** The real cost is the wait after the last reply for a possible second ECU. Documented fix is
  the **responses digit** (`010C1`), shown with plain mode-01 examples in the general chapter, **not**
  CAN-only. **[CODE]** LEGION never uses it. **[VEN]** Its own warning applies: measure the response
  count first; too low a digit causes retransmission storms.
- **[VEN]** `ATSP0` costs nothing per command once settled (it deliberately skips the ~30 ms EEPROM
  write). But a re-search pays the 2-3 s ISO 9141 slow init, so `reinitProtocolLocked` events must stay
  rare.
- **Not established:** ELM327 internal processing latency (no vendor figure); whether its response-wait
  and P3 delay overlap. The latter decides whether the responses digit is worth 5 ms or 90 ms per PID.

**4. `0110` (MAF) is almost certainly dead on this car. Everything else is plausible.**

**[STD]** Only PID $00 is universally guaranteed; read the rest off `0100`/`0120` on the car.
**[STD]** Annex B support conditions: `0104` "**shall** support" for all SI and CI engines — the only
near-mandatory one in the set. `0105`, `0106`, `010C`, `010D`, `010F` all plausible. `0107` conditional
on the PCM using long-term trim (likely). `012F` conditional on OBD use — **not established**, and it
needs `0120` support too.

**[COM]** The Jeep 4.0L I6 is **speed-density with no MAF sensor at all** (MAP + IAT + RPM), consistent
across multiple independent sources. **[STD]** Annex B Table B.12 backs this: a MAP-only vehicle owes
`010B` and owes `0110` nothing.

**Consequence: MAF-based instantaneous mpg is very probably impossible.** Fallbacks are a
speed-density estimate (`010B`+`010F`+`010C`, 3 PIDs just for airflow, refreshing under 1.5 Hz, and an
**estimate** that CLAUDE.md §7 requires be labelled as one) or `0104` as a proxy. Recommendation: do
not build it until ticket 02 reads `0100`.

**5. Bluetooth is noise. The 20 ms sleep is small, real, and nearly free to remove.**

**[3P]** BR/EDR slots are 625 µs; a poll+data round trip is ≥ 1.25 ms. **[DER]** a few ms each way
against a 119-359 ms bus trip — **under 5%, noise.** Changing transport buys nothing.

**[DER]** `Elm327Io.kt:43-59`'s `Thread.sleep(20)` fires whenever the stream is empty, i.e. for
essentially the whole bus round trip. Cost is not 20 ms per byte — it is up to 20 ms (mean ~10 ms)
of latency detecting the terminating `>`, once per exchange: **~6% of a typical trip.** Not the
bottleneck, but the cheapest millisecond in the stack; a blocking `read()` removes it. Byte-at-a-time
reading is itself fine at ~20 bytes per exchange.

### Bottom line

**~1.3 to ~2.2 full refreshes per second for a 3-PID set as the code stands (450-750 ms per cycle).
Best plausible after tuning (responses digit, `ATAT2`, blocking read): ~2.4-2.7 Hz. Protocol floor,
unreachable: 2.8 Hz.**

Design consequences, no further measurement needed:
1. A live gauge on this car **steps about twice a second**. Any UI implying smooth continuous motion is
   lying about the bus.
2. Each added PID costs ~150-250 ms of cycle time, **linearly**. The polled set is a budget; make it
   explicit. A fourth PID takes the set from ~2 Hz to ~1.5 Hz.
3. `0110` should probably leave the hot loop entirely.

**Biggest single uncertainty: how many ECUs answer a functional mode-01 request on this vehicle, and
what P1/P2 the Chrysler PCM actually uses.** Worth 2-3× on the final number — one responder at P1≈0 is
~124 ms/PID, two responders or P1≈5-10 ms is 220-300 ms/PID. **[STD]** The standard bounds these and
explicitly leaves the value to the manufacturer (§5.2.2.1); it does not give it.

**This needs measuring on the car, and ticket 02 exists to do exactly that.** Treat the numbers above
as the shape of the answer, not the answer. Ticket 02 must capture: (1) `0100`/`0120` bitmasks;
(2) wall-clock per-PID round trip for `010C`, mean/p50/p95 over a few hundred samples; (3) response
line count per request with `ATH1`; (4) A/B of `010C` vs `010C1`, `ATAT1` vs `ATAT2`, blocking read vs
the 20 ms sleep; (5) how often `reinitProtocolLocked` fires on a real drive.

## CORRECTION 2026-08-16 - the MAF conclusion is FALSIFIED by Kevin's own data

This ticket concluded the Jeep 4.0L is speed-density with no MAF sensor, that `0110` "will almost
certainly never answer", and that MAF-based instantaneous mpg is "very probably impossible on this
car". **That is wrong, and it was overturned within the hour by querying the real database.**

- `12:34:56:11:22:33` is the 1998 Jeep Cherokee, confirmed against the `vehicles` table.
- It carries **166 `0110` samples** with plausible values (2.28, 11.24, 15.68 g/s).
- It has a finalised drive: `TRIP_MILES` 20.7 mi, `MPG_TRIP` 29.4 mpg.

**The mechanical claim was right and the conclusion drawn from it was wrong.** The engine genuinely
is speed-density; the PCM nonetheless synthesises and reports a `0110` value. "No MAF sensor" does
not imply "no `0110` response".

**The lesson, and it is the same one four other findings taught today: the database was RIGHT THERE.**
This was researched from standards documents and community reports when a single query against a
copy of the car's own history would have settled it. Research the parts you cannot measure; measure
the parts you can.

**Everything else in this ticket stands** - the batching finding is traced to two independent primary
sources, and the timing analysis is unaffected. Only the PID-support conclusion was overturned.

**Consequence:** instantaneous mpg is back on the table, but the figure LEGION currently computes is
probably ~1.7x too high. See [the mpg scale bug](09-mpg-scale-bug.md).
