"""Publish a built APK as a LEGION release (alfred-ota ticket 04, map decision 4).

    python tools/release_apk.py --app phone --apk app/build/outputs/apk/fast/app-fast.apk \\
        --notes "fixes the double announce"
    python tools/release_apk.py --app phone --apk <path> --notes "..." --dry-run
    python tools/release_apk.py --app headunit \\
        --apk headunit/app/build/outputs/apk/fast/app-arm64-v8a-fast.apk --notes "..."

It builds nothing. In order, it:
  1. checks the APK's signer certificate SHA-256 against `deploy/release/signer.sha256`
     (apksigner, Android SDK build-tools); a different key is refused, because the device could
     never install it over the running app. Both `fast` builds (phone and head unit) are signed
     with this laptop's debug key, so one pin serves both; the head unit's `release.jks` signs
     only `assembleRelease`, which is not what goes on the car;
  1b. checks the APK's package is the one `--app` names (aapt2): the phone and head unit share
     the debug key, so the signer alone cannot tell their APKs apart;
  1c. refuses an APK whose BuildConfig carries a non-empty GEMINI_API_KEY or AIRNOW_API_KEY
     (read from the dex files in pure Python, no SDK tool). A build made without `-Pnokey` bakes
     the laptop's dev keys from local.properties, and anyone holding the APK can read them out.
     `--allow-baked-keys` overrides it, with a warning, until the phone runs on a pasted key
     (architecture review 2026-11-10, item 6). The Mapbox `pk.` token is public by design and
     is not checked;
  2. reads the APK's versionCode and versionName (aapt2) and checks the code is higher than the
     newest release on the engine;
  3. computes the APK's sha256;
  4. uploads it with `gcloud storage cp` to gs://<bucket>/<app>/<versionCode>-<sha12>.apk;
  5. POSTs the release to the engine with the owner's device token;
  6. prints what it did.

`--dry-run` does 1 to 3 on the real file and prints 4 and 5 without making any network call;
it never contacts the engine, so the "higher than the newest" check is skipped and said so.

Configuration, from the environment (or the flags of the same name):
  LEGION_ENGINE_URL    the engine's base URL, no `/api` (the same name `tools/mcp/engine` uses)
  LEGION_ENGINE_TOKEN  an OWNER's device token. Never committed, never printed.
  LEGION_APK_BUCKET    the bucket name, e.g. legion-apk-legion-engine-260906
  ANDROID_HOME / ANDROID_SDK_ROOT  where build-tools live (default: %LOCALAPPDATA%/Android/Sdk)

Kevin runs it via `!`. The worker's `legion-chores` skill may run it, but only after Kevin's
yes: publishing counts as a send (map rules).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
PINNED_SIGNER = REPO / "deploy" / "release" / "signer.sha256"
APPS = ("phone", "headunit")
# The applicationId each app's APK must carry (app/ and headunit/app/build.gradle.kts).
PACKAGES = {"phone": "com.kevin.legion", "headunit": "com.kevin.midnightai"}

_SIGNER_RE = re.compile(r"certificate SHA-256 digest:\s*([0-9a-fA-F:]{64,95})")
_BADGING_RE = re.compile(r"versionCode='(\d+)'\s+versionName='([^']*)'")
_PACKAGE_RE = re.compile(r"package: name='([^']+)'")


class Refused(Exception):
    """Stop, in words. Nothing past the failing step ran."""


def normalise_sha256(text: str) -> str:
    """keytool prints `FD:81:...`, apksigner `fd81...`. Both become 64 lowercase hex."""
    value = text.strip().replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise Refused(f"{text.strip()!r} is not a SHA-256 fingerprint.")
    return value


def read_pinned_signer(path: Path = PINNED_SIGNER) -> str:
    if not path.exists():
        raise Refused(
            f"{path} is missing. Pin your own signing key's certificate SHA-256 there first "
            f"(keytool -list -v -keystore <keystore> -alias <alias>)."
        )
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    lines = [line for line in lines if not line.lstrip().startswith("#")]
    if len(lines) != 1:
        raise Refused(f"{path} must hold exactly one fingerprint.")
    return normalise_sha256(lines[0])


def parse_signers(apksigner_output: str) -> set[str]:
    return {normalise_sha256(m) for m in _SIGNER_RE.findall(apksigner_output)}


def parse_badging(aapt2_output: str) -> tuple[int, str]:
    match = _BADGING_RE.search(aapt2_output)
    if not match:
        raise Refused("aapt2 printed no versionCode for this APK; is it an APK?")
    return int(match.group(1)), match.group(2)


def parse_package(aapt2_output: str) -> str:
    match = _PACKAGE_RE.search(aapt2_output)
    if not match:
        raise Refused("aapt2 printed no package name for this APK; is it an APK?")
    return match.group(1)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def object_name_for(app: str, version_code: int, sha256: str) -> str:
    return f"{app}/{version_code}-{sha256[:12]}.apk"


# -----------------------------------------------------------------------------
# Baked keys: BuildConfig's static String values, read straight from the dex files
# -----------------------------------------------------------------------------

# The BuildConfig fields that hold a SECRET key. MAPBOX_ACCESS_TOKEN is a public `pk.` token,
# shipped inside every Mapbox app by design, so it is not here.
SECRET_FIELDS = ("GEMINI_API_KEY", "AIRNOW_API_KEY")
BUILDCONFIG = {
    "phone": "Lcom/kevin/legion/BuildConfig;",
    "headunit": "Lcom/kevin/midnightai/BuildConfig;",
}


def _uleb128(data: bytes, pos: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, pos
        shift += 7


def _dex_string(data: bytes, string_ids_off: int, index: int) -> str:
    (data_off,) = struct.unpack_from("<I", data, string_ids_off + 4 * index)
    _, pos = _uleb128(data, data_off)  # utf16 length, unused
    end = data.index(b"\x00", pos)
    # MUTF-8. An API key is ASCII, and anything else only has to be told apart from empty.
    return data[pos:end].decode("utf-8", errors="replace")


def _encoded_value(data: bytes, pos: int) -> tuple[int | None, int]:
    """One dex encoded_value: (string index, next pos) for a string, (None, next pos) otherwise."""
    head = data[pos]
    pos += 1
    value_type, value_arg = head & 0x1F, head >> 5
    if value_type in (0x1E, 0x1F):  # null, boolean: no payload
        return None, pos
    if value_type == 0x1C:  # nested array
        size, pos = _uleb128(data, pos)
        for _ in range(size):
            _, pos = _encoded_value(data, pos)
        return None, pos
    if value_type == 0x1D:
        raise ValueError("annotation in a static value array")
    width = value_arg + 1
    raw = data[pos:pos + width]
    pos += width
    if value_type == 0x17:
        return int.from_bytes(raw, "little"), pos
    return None, pos


def dex_static_strings(data: bytes, class_descriptor: str) -> dict[str, str] | None:
    """One class's static String field values in one dex file; None when the class is not in it.

    Only fields with an initial value in the dex (`static_values`) appear; a `static final String`
    constant such as a BuildConfig field always has one."""
    if data[:4] != b"dex\n":
        raise ValueError("not a dex file")
    (string_ids_off,) = struct.unpack_from("<I", data, 0x3C)
    (type_ids_off,) = struct.unpack_from("<I", data, 0x44)
    (field_ids_off,) = struct.unpack_from("<I", data, 0x54)
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 0x60)
    for i in range(class_defs_size):
        base = class_defs_off + 32 * i
        (class_idx,) = struct.unpack_from("<I", data, base)
        (descriptor_idx,) = struct.unpack_from("<I", data, type_ids_off + 4 * class_idx)
        if _dex_string(data, string_ids_off, descriptor_idx) != class_descriptor:
            continue
        class_data_off, static_values_off = struct.unpack_from("<II", data, base + 24)
        if not class_data_off:
            return {}
        static_count, pos = _uleb128(data, class_data_off)
        for _ in range(3):  # instance fields, direct methods, virtual methods
            _, pos = _uleb128(data, pos)
        names = []
        field_idx = 0
        for _ in range(static_count):
            diff, pos = _uleb128(data, pos)
            _, pos = _uleb128(data, pos)  # access flags
            field_idx += diff
            (name_idx,) = struct.unpack_from("<I", data, field_ids_off + 8 * field_idx + 4)
            names.append(_dex_string(data, string_ids_off, name_idx))
        values: dict[str, str] = {}
        if static_values_off:
            size, pos = _uleb128(data, static_values_off)
            for name in names[:size]:
                string_idx, pos = _encoded_value(data, pos)
                if string_idx is not None:
                    values[name] = _dex_string(data, string_ids_off, string_idx)
        return values
    return None


def apk_buildconfig_strings(apk: Path, class_descriptor: str) -> dict[str, str] | None:
    """BuildConfig's static String values, from whichever classes*.dex in the APK holds it."""
    with zipfile.ZipFile(apk) as archive:
        for name in sorted(archive.namelist()):
            if re.fullmatch(r"classes\d*\.dex", name):
                found = dex_static_strings(archive.read(name), class_descriptor)
                if found is not None:
                    return found
    return None


def baked_secret_fields(apk: Path, app: str) -> list[str]:
    """Names (never values) of the SECRET_FIELDS that carry a non-empty value in this APK."""
    try:
        values = apk_buildconfig_strings(apk, BUILDCONFIG[app])
    except (zipfile.BadZipFile, ValueError, IndexError, struct.error) as exc:
        raise Refused(f"Could not read BuildConfig from {apk.name} ({exc}); is it an APK?") from exc
    if values is None:
        raise Refused(f"{apk.name} has no {BUILDCONFIG[app]} class, so its keys cannot be checked.")
    return [name for name in SECRET_FIELDS if values.get(name, "").strip()]


# -----------------------------------------------------------------------------
# The outside world, behind small functions the tests replace
# -----------------------------------------------------------------------------


def _sdk_dir() -> Path:
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(name):
            return Path(os.environ[name])
    return Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"


def find_build_tool(name: str) -> str:
    build_tools = _sdk_dir() / "build-tools"
    versions = sorted(
        (p for p in build_tools.iterdir() if p.is_dir()) if build_tools.is_dir() else [],
        key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)],
        reverse=True,
    )
    for directory in versions:
        for candidate in (f"{name}.bat", f"{name}.exe", name):
            if (directory / candidate).exists():
                return str(directory / candidate)
    found = shutil.which(name)
    if found:
        return found
    raise Refused(f"{name} not found under {build_tools}. Set ANDROID_HOME to the Android SDK.")


def _run(command: list[str]) -> str:
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise Refused(f"{Path(command[0]).name} failed: {(result.stderr or result.stdout).strip()}")
    return result.stdout


def apksigner_output(apk: Path) -> str:
    return _run([find_build_tool("apksigner"), "verify", "--print-certs", str(apk)])


def aapt2_output(apk: Path) -> str:
    return _run([find_build_tool("aapt2"), "dump", "badging", str(apk)])


def engine_request(method: str, url: str, token: str, body: dict | None = None) -> tuple[int, dict]:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={"Authorization": f"Token {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as exc:
        try:
            payload = json.loads(exc.read() or b"{}")
        except ValueError:
            payload = {}
        return exc.code, payload


def upload(apk: Path, destination: str) -> None:
    gcloud = shutil.which("gcloud") or shutil.which("gcloud.cmd")
    if not gcloud:
        raise Refused("gcloud not found. Install the Cloud SDK and sign in first.")
    command = [gcloud, "storage", "cp", str(apk), destination]
    if os.name == "nt" and gcloud.lower().endswith(".cmd"):
        result = subprocess.run(subprocess.list2cmdline(command), shell=True, check=False)
    else:
        result = subprocess.run(command, check=False)
    if result.returncode != 0:
        raise Refused(f"The upload to {destination} failed; nothing was published.")


# -----------------------------------------------------------------------------
# The steps
# -----------------------------------------------------------------------------


@dataclass
class Plan:
    app: str
    apk: Path
    version_code: int
    version_name: str
    sha256: str
    size_bytes: int
    signer_sha256: str
    object_name: str
    notes: str


def inspect(
    app: str, apk: Path, notes: str, pinned: str, allow_baked_keys: bool = False, out=print
) -> Plan:
    """Steps 1 to 3: everything that needs only the file."""
    if app not in APPS:
        raise Refused(f"--app must be one of {', '.join(APPS)}.")
    if not apk.is_file():
        raise Refused(f"{apk} is not a file.")
    signers = parse_signers(apksigner_output(apk))
    if not signers:
        raise Refused(f"apksigner found no signer on {apk.name}; it is not signed.")
    if signers != {pinned}:
        raise Refused(
            f"{apk.name} is signed by {', '.join(sorted(signers))}, not the pinned key {pinned} "
            f"(deploy/release/signer.sha256). The {app} could not install it over the running "
            f"app. Nothing was uploaded."
        )
    badging = aapt2_output(apk)
    package = parse_package(badging)
    if package != PACKAGES[app]:
        raise Refused(
            f"{apk.name} is {package}, not the {app} app ({PACKAGES[app]}). Nothing was uploaded."
        )
    baked = baked_secret_fields(apk, app)
    if baked and not allow_baked_keys:
        raise Refused(
            f"{apk.name} carries a baked {' and '.join(baked)} in BuildConfig: anyone with the "
            f"APK can read it out. Build with -Pnokey (the {app} then uses the key pasted in "
            f"Setup), or pass --allow-baked-keys to ship it anyway. Nothing was uploaded."
        )
    if baked:
        out(f"WARNING      baked {', '.join(baked)} shipped on --allow-baked-keys")
    version_code, version_name = parse_badging(badging)
    sha256 = sha256_of(apk)
    return Plan(
        app=app,
        apk=apk,
        version_code=version_code,
        version_name=version_name,
        sha256=sha256,
        size_bytes=apk.stat().st_size,
        signer_sha256=pinned,
        object_name=object_name_for(app, version_code, sha256),
        notes=notes,
    )


def check_newer(plan: Plan, engine: str, token: str) -> int | None:
    """Step 2's engine half. The newest release's version code, or None when there is none."""
    status, body = engine_request("GET", f"{engine}/api/releases/latest?app={plan.app}", token)
    if status == 404:
        return None
    if status != 200:
        raise Refused(
            f"The engine answered {status} for the newest release: {body.get('detail', body)}"
        )
    newest = int(body["version_code"])
    if plan.version_code <= newest:
        raise Refused(
            f"versionCode {plan.version_code} is not higher than the newest {plan.app} release, "
            f"{newest}. Build again; nothing was uploaded."
        )
    return newest


def publish_body(plan: Plan) -> dict:
    return {
        "app": plan.app,
        "version_code": plan.version_code,
        "version_name": plan.version_name,
        "sha256": plan.sha256,
        "size_bytes": plan.size_bytes,
        "object_name": plan.object_name,
        "notes": plan.notes,
        "signer_sha256": plan.signer_sha256,
    }


def run(args, out=print) -> int:
    pinned = read_pinned_signer(Path(args.signer_file) if args.signer_file else PINNED_SIGNER)
    plan = inspect(
        args.app,
        Path(args.apk),
        args.notes,
        pinned,
        allow_baked_keys=getattr(args, "allow_baked_keys", False),
        out=out,
    )
    out(f"signer       {plan.signer_sha256} (matches the pin)")
    out(f"version      {plan.version_code} ({plan.version_name})")
    out(f"sha256       {plan.sha256}")
    out(f"size         {plan.size_bytes} bytes")

    bucket = args.bucket or os.environ.get("LEGION_APK_BUCKET", "").strip()
    engine = (args.engine or os.environ.get("LEGION_ENGINE_URL", "")).strip().rstrip("/")
    destination = f"gs://{bucket or '<LEGION_APK_BUCKET>'}/{plan.object_name}"

    if args.dry_run:
        out("--dry-run: the file checks above ran; nothing below did.")
        out("[skipped] the engine's newest release was not read, so the versionCode is unchecked")
        out(f"[would]   gcloud storage cp {plan.apk} {destination}")
        out(f"[would]   POST {engine or '<LEGION_ENGINE_URL>'}/api/releases/ {publish_body(plan)}")
        return 0

    token = (args.token or os.environ.get("LEGION_ENGINE_TOKEN", "")).strip()
    missing = [
        name
        for name, value in (
            ("LEGION_APK_BUCKET", bucket),
            ("LEGION_ENGINE_URL", engine),
            ("LEGION_ENGINE_TOKEN", token),
        )
        if not value
    ]
    if missing:
        raise Refused(f"{', '.join(missing)} not set. Nothing was uploaded.")

    newest = check_newer(plan, engine, token)
    out(f"newest       {newest if newest is not None else 'none yet'} on the engine")
    upload(plan.apk, destination)
    out(f"uploaded     {destination}")
    status, body = engine_request("POST", f"{engine}/api/releases/", token, publish_body(plan))
    if status != 201:
        raise Refused(
            f"The engine refused the release ({status}): {body.get('detail', body)} "
            f"The APK is in the bucket at {destination} but no release was recorded."
        )
    out(f"published    {plan.app} {plan.version_code} as release {body.get('id')}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--app", required=True, choices=APPS)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--notes", required=True, help="what the release fixes, one line")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--engine", default=None, help="overrides LEGION_ENGINE_URL")
    parser.add_argument("--token", default=None, help="overrides LEGION_ENGINE_TOKEN")
    parser.add_argument("--bucket", default=None, help="overrides LEGION_APK_BUCKET")
    parser.add_argument(
        "--signer-file", default=None, help="overrides deploy/release/signer.sha256"
    )
    parser.add_argument(
        "--allow-baked-keys",
        action="store_true",
        help="publish even if BuildConfig carries a GEMINI/AIRNOW key (temporary override)",
    )
    args = parser.parse_args(argv)
    try:
        return run(args)
    except Refused as exc:
        print(f"Refused: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
