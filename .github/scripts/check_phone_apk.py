"""The phone APK checks the laptop ship runs, as one script the cloud release runs too.

    python .github/scripts/check_phone_apk.py --apk app-fast.apk --sha <40-hex commit> \
        [--speaker-sha <sha256>] [--newest-vc <int>]

Checks, in order (each failure is named; exit 1 when any failed, 0 when all passed):
  1. every file in app/models.sha256 is in the APK at assets/<path>, byte for byte as pinned;
  2. the speaker model is in the APK at assets/<path from app/speaker-model.pin> with the sha256 the
     pin says a build ships (official when release_ok=yes, else the interim copy), or --speaker-sha;
  3. BuildConfig's MAPBOX_ACCESS_TOKEN is the public `pk.` token or empty, and no entry in the APK
     holds an `sk.eyJ` (Mapbox secret token) string. Only the token's kind is printed, never a value;
  4. versionName ends with `-<first 7 of --sha>`, so the APK is the commit it claims to be;
  5. with --newest-vc: versionCode is above the newest published release.
Signer pin, package name and the baked Gemini/AirNow key scan are tools/release_apk.py's own checks
(`--dry-run`), run beside this one. Stdlib only, plus tools/release_apk.py for the dex and aapt2
readers.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import release_apk  # noqa: E402



def manifest_entries(text: str) -> list[tuple[str, str]]:
    out = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        sha, rel = line.split(None, 1)
        out.append((rel.lstrip("*"), sha))
    return out


def pin_values(text: str) -> dict[str, str]:
    vals = {}
    for raw in text.splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            vals[k.strip()] = v.strip()
    return vals


def check(apk: Path, sha: str, speaker_sha: str | None, newest_vc: int | None, tree: Path = REPO) -> list[str]:
    problems: list[str] = []
    models = manifest_entries((tree / "app" / "models.sha256").read_text(encoding="utf-8"))
    if not models:
        problems.append("app/models.sha256 lists no model")
    pin = pin_values((tree / "app" / "speaker-model.pin").read_text(encoding="utf-8"))
    # The file a cloud build ships: the official one once the pin says release_ok=yes, else the interim copy.
    pinned = pin["sha256"] if pin.get("release_ok") == "yes" else pin["interim_sha256"]
    speaker = (pin["path"], speaker_sha or pinned)
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        for rel, want in [*models, speaker]:
            entry = f"assets/{rel}"
            if entry not in names:
                problems.append(f"missing {entry}")
                continue
            got = hashlib.sha256(z.read(entry)).hexdigest()
            if got != want:
                problems.append(f"{entry} has sha256 {got}, pinned {want}")
            else:
                print(f"ok   {entry} ({got[:12]})")
        hits = [n for n in sorted(names) if z.read(n).find(b"sk.eyJ") >= 0]
    values = release_apk.apk_buildconfig_strings(apk, release_apk.BUILDCONFIG["phone"]) or {}
    tok = values.get("MAPBOX_ACCESS_TOKEN", "").strip()
    kind = "empty" if not tok else "pk." if tok.startswith("pk.") else "sk." if tok.startswith("sk.") else "other"
    print(f"Mapbox token in BuildConfig: {kind}; entries holding an sk.eyJ string: {len(hits)}")
    if kind in ("sk.", "other"):
        problems.append(f"BuildConfig MAPBOX_ACCESS_TOKEN is {kind}, not the public pk. token")
    if hits:
        problems.append(f"{len(hits)} APK entries hold an sk.eyJ (Mapbox secret) string")
    # Text assets are LF everywhere (.gitattributes): a CR byte means a CRLF checkout was packaged.
    with zipfile.ZipFile(apk) as z:
        crlf = [n for n in sorted(z.namelist())
                if n.startswith("assets/") and n.rsplit(".", 1)[-1] in ("json", "csv", "md", "txt")
                and b"\r" in z.read(n)]
    if crlf:
        problems.append(f"text assets with a CR byte (CRLF checkout packaged): {crlf[:10]}")
    else:
        print("ok   no CR byte in any text asset")
    vc, vname = release_apk.parse_badging(release_apk.aapt2_output(apk))
    print(f"version {vc} ({vname})")
    if not vname.endswith(f"-{sha[:7]}"):
        problems.append(f"versionName {vname} does not end with -{sha[:7]}")
    if newest_vc is not None:
        if vc <= newest_vc:
            problems.append(f"versionCode {vc} is not above the newest published {newest_vc}")
        else:
            print(f"ok   versionCode {vc} > newest published {newest_vc}")
    return problems


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--apk", required=True)
    ap.add_argument("--sha", required=True, help="the commit the APK was built from (40 hex)")
    ap.add_argument("--speaker-sha", help="expected speaker model sha256; default the pin's official one")
    ap.add_argument("--newest-vc", type=int, help="the newest published versionCode")
    ap.add_argument("--tree", default=str(REPO), help="a directory holding the BUILT commit's app/models.sha256 and app/speaker-model.pin (default: this checkout)")
    a = ap.parse_args(argv)
    if not re.fullmatch(r"[0-9a-f]{40}", a.sha):
        print("--sha must be 40 lowercase hex characters", file=sys.stderr)
        return 2
    problems = check(Path(a.apk), a.sha, a.speaker_sha, a.newest_vc, Path(a.tree))
    for p in problems:
        print(f"FAIL {p}")
    print("APK checks: all passed" if not problems else f"APK checks: {len(problems)} failed")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
