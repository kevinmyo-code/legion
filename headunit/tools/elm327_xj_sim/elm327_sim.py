#!/usr/bin/env python3
"""
ELM327 emulator with a 1998 Jeep Cherokee XJ (4.0L, ISO 9141-2) profile.

Speaks just enough of the ELM327 AT set and OBD-II modes 01/02/03/09 for
Midnight AI's ObdBluetoothManager to run its full handshake and every live
read without a car. Listens on TCP :35000, which is what the app's
DebugSettings.obdEmulatorEnabled path connects to (TcpTransport ->
10.0.2.2:35000, the Android emulator's alias for this host's loopback).

Ircama's ELM327-emulator only ships a Toyota Auris scenario; this is the XJ
profile that project's backlog flagged as missing, plus scripted failure
injection for debugging the app's degrade-gracefully paths.

Usage:
    python elm327_sim.py                 # healthy warm idle
    python elm327_sim.py --drive         # rpm/speed/coolant/MAF vary over time
    python elm327_sim.py --port 35000    # change port
    python elm327_sim.py --no-vin        # answer NO DATA to mode-09 (realistic for '98)
    python elm327_sim.py --drop 010C     # never answer a specific PID (timeout)
    python elm327_sim.py --flaky 4       # drop ~1 in 4 PID replies (dropout testing)
    python elm327_sim.py --mil P0301,P0171   # MIL on with these stored codes
    python elm327_sim.py --quiet         # don't log each command

Point an Android Studio AVD at it: run this, launch the app on the AVD, then
Settings -> Manage OBD -> "Use OBD emulator (DEBUG)".
"""
import argparse
import random
import socket
import threading
import time

# --- XJ static values (warm idle). Data line only; framing added at send. ----
# Command (uppercased) -> response body, or a callable(state) -> body.
BASE = {
    "ATI": "ELM327 v1.5",
    "AT@1": "OBDII to RS232 Interpreter",
    "ATE0": "OK", "ATE1": "OK", "ATL0": "OK", "ATL1": "OK",
    "ATH0": "OK", "ATH1": "OK", "ATS0": "OK", "ATS1": "OK",
    "ATSP0": "OK", "ATSP3": "OK", "ATAT1": "OK", "ATAT2": "OK",
    "ATDPN": "A3",                       # auto-selected ISO 9141-2
    "ATDP": "AUTO, ISO 9141-2",
    "ATRV": "13.8V",                     # engine running (alternator)
    "0100": "41 00 BE 3E B8 11",         # supported PIDs 01-20
    "0120": "41 20 80 07 A0 11",         # supported PIDs 21-40
    # NB: 0101 (readiness) is intentionally NOT static - it must reflect MIL
    # state from --mil, so it is built dynamically in Car.pid_line().
    "010F": "41 0F 3C",                  # 20 C intake air temp
    "012F": "41 2F 80",                  # ~50% fuel
    "0106": "41 06 80",                  # 0% short fuel trim
    "0107": "41 07 85",                  # +3.9% long fuel trim
}


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


class Car:
    """Live-ish state so --drive produces believable telemetry trends."""

    def __init__(self, args):
        self.args = args
        self.t0 = time.time()
        self.mil_codes = args.mil or []

    def _phase(self):
        # 0..1 sawtooth over ~90s: idle -> accel -> cruise -> decel.
        return ((time.time() - self.t0) % 90.0) / 90.0

    def rpm(self):
        if not self.args.drive:
            return 750
        p = self._phase()
        return int(clamp(750 + 2600 * (0.5 - abs(p - 0.5)) * 2, 650, 3400))

    def speed(self):
        if not self.args.drive:
            return 0
        return int(clamp(self.rpm() / 45.0 - 12, 0, 110))

    def coolant(self):
        # Warms 40 -> 88 C over the first ~5 min, then holds.
        warm = clamp((time.time() - self.t0) / 300.0, 0, 1)
        return int(40 + 48 * warm)

    def maf(self):
        base = 4.5 if not self.args.drive else 4.5 + (self.rpm() - 750) / 90.0
        return clamp(base, 2.0, 55.0)

    def load(self):
        return clamp(18 + (self.rpm() - 750) / 30.0, 12, 92)

    # --- OBD line builders (mode-01 "41 PID <bytes>") ------------------------
    def pid_line(self, cmd):
        if cmd == "010C":
            v = int(self.rpm() * 4)
            return f"41 0C {v >> 8:02X} {v & 0xFF:02X}"
        if cmd == "010D":
            return f"41 0D {int(self.speed()) & 0xFF:02X}"
        if cmd == "0105":
            return f"41 05 {int(self.coolant()) + 40 & 0xFF:02X}"
        if cmd == "0104":
            return f"41 04 {int(self.load() * 255 / 100) & 0xFF:02X}"
        if cmd == "0110":
            v = int(self.maf() * 100)
            return f"41 10 {v >> 8:02X} {v & 0xFF:02X}"
        if cmd == "0101":
            # A: bit7 MIL, low bits stored-code count.
            a = (0x80 | len(self.mil_codes)) if self.mil_codes else 0x00
            return f"41 01 {a:02X} 07 E5 00"
        return None

    def mode03(self):
        if not self.mil_codes:
            return "43 00 00"
        out = ["43"]
        for code in self.mil_codes:
            letter = "PCBU".index(code[0].upper())
            num = int(code[1:], 16)
            b1 = (letter << 6) | ((num >> 8) & 0x3F)
            b2 = num & 0xFF
            out.append(f"{b1:02X}")
            out.append(f"{b2:02X}")
        return " ".join(out)

    def freeze(self, cmd):
        # cmd like "020C00": mode 02, pid 0C, frame 00.
        pid = cmd[2:4]
        latched = {
            "0C": f"42 0C 00 {int(2200 * 4) >> 8:02X} {int(2200 * 4) & 0xFF:02X}",
            "05": "42 05 00 5F",   # 95 C - hot when the code set
            "0D": "42 0D 00 2D",   # 45 km/h
            "04": "42 04 00 66",   # 40% load
            "10": "42 10 00 0B B8",
            "06": "42 06 00 80",
            "07": "42 07 00 85",
            "0F": "42 0F 00 3C",
        }
        return latched.get(pid.upper()) if self.mil_codes else "NO DATA"


def respond(car, cmd):
    """Return the data line for a command, or None to force a NO DATA."""
    u = cmd.upper()
    if u in BASE:
        return BASE[u]
    live = car.pid_line(u)
    if live is not None:
        return live
    if u == "03":
        return car.mode03()
    if u.startswith("02") and len(u) >= 4:
        return car.freeze(u)
    if u == "0902":
        return None if car.args.no_vin else \
            "49 02 01 31 4A 34 46 4A 32 38 53 33 57 4C 35 34 32 31 39 39"
    if u.startswith("AT"):
        return "OK"      # tolerate any other AT config command
    return None          # -> NO DATA


def handle_client(conn, addr, car, args):
    conn.settimeout(120)
    echo = True
    searched = False
    buf = b""
    if not args.quiet:
        print(f"[+] {addr} connected")
    try:
        while True:
            data = conn.recv(256)
            if not data:
                break
            buf += data
            while b"\r" in buf or b"\n" in buf:
                sep = min((buf.find(b"\r") if b"\r" in buf else 1 << 30),
                          (buf.find(b"\n") if b"\n" in buf else 1 << 30))
                raw, buf = buf[:sep], buf[sep + 1:]
                cmd = raw.decode(errors="ignore").strip()
                if not cmd:
                    continue

                if cmd.upper() == "ATZ":
                    echo, searched = True, False
                    _send(conn, ("ATZ\r" if echo else "") + "ELM327 v1.5\r\r>")
                    if not args.quiet:
                        print("    ATZ -> reset")
                    continue
                if cmd.upper() == "ATE0":
                    echo = False
                if cmd.upper() == "ATE1":
                    echo = True

                # --- failure injection -----------------------------------
                if args.drop and cmd.upper() == args.drop.upper():
                    if not args.quiet:
                        print(f"    {cmd} -> DROPPED (no reply)")
                    continue
                if args.flaky and cmd.upper().startswith(("01", "02", "03")) \
                        and random.randint(1, args.flaky) == 1:
                    if not args.quiet:
                        print(f"    {cmd} -> flaky drop")
                    continue

                body = respond(car, cmd)
                if body is None:
                    body = "NO DATA"
                if args.truncate and cmd.upper().startswith("01"):
                    body = body[: max(1, len(body) // 2)]   # chop a data line

                prefix = ""
                if not searched and cmd.upper().startswith("01"):
                    searched = True
                    prefix = "SEARCHING...\r"

                out = ("{}\r".format(cmd) if echo else "") + prefix + body + "\r\r>"
                _send(conn, out)
                if not args.quiet:
                    print(f"    {cmd} -> {body}")
    except (socket.timeout, ConnectionResetError, OSError):
        pass
    finally:
        conn.close()
        if not args.quiet:
            print(f"[-] {addr} disconnected")


def _send(conn, text):
    conn.sendall(text.encode())


def main():
    ap = argparse.ArgumentParser(description="1998 Jeep XJ ELM327 emulator")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=35000)
    ap.add_argument("--drive", action="store_true", help="vary rpm/speed/coolant/MAF over time")
    ap.add_argument("--no-vin", action="store_true", help="mode-09 VIN -> NO DATA (realistic for '98)")
    ap.add_argument("--drop", metavar="PID", help="never answer this command (timeout injection)")
    ap.add_argument("--flaky", type=int, metavar="N", help="drop ~1 in N PID replies")
    ap.add_argument("--truncate", action="store_true", help="chop mode-01 data lines in half")
    ap.add_argument("--mil", type=lambda s: [c.strip() for c in s.split(",") if c.strip()],
                    metavar="CODES", help="MIL on with stored DTCs, e.g. P0301,P0171")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    car = Car(args)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(5)
    mode = "DRIVE" if args.drive else "IDLE"
    print(f"XJ ELM327 emulator on {args.host}:{args.port} [{mode}]"
          + (f"  MIL={','.join(args.mil)}" if args.mil else "")
          + "  (Ctrl-C to stop)")
    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=handle_client, args=(conn, addr, car, args),
                             daemon=True).start()
    except KeyboardInterrupt:
        print("\nbye")
    finally:
        srv.close()


if __name__ == "__main__":
    main()
