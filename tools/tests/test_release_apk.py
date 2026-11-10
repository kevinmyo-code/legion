"""alfred-ota ticket 04: `tools/release_apk.py`, with no SDK, no bucket and no engine.

apksigner, aapt2, gcloud and the engine are replaced with fakes; the APK is a few bytes of fake
file, so the sha256 is computed for real. Run from the repo root:

    python -m pytest tools/tests/test_release_apk.py -p no:cacheprovider
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("release_apk_under_test", TOOLS / "release_apk.py")
ra = importlib.util.module_from_spec(spec)
sys.modules["release_apk_under_test"] = ra
spec.loader.exec_module(ra)

PINNED = "fd819ada7fdbf98f4b072b511d2dff33cb3dc68b720b8a9a0ff862ebd03aac01"
OTHER = "11" * 32

# What apksigner 37.0.0 printed for a real `fast` APK on 2026-11-05 (warnings trimmed).
APKSIGNER_REAL = (
    "V2 Signer: certificate DN: C=US, O=Android, CN=Android Debug\n"
    f"V2 Signer: certificate SHA-256 digest: {PINNED}\n"
    "V2 Signer: certificate SHA-1 digest: 524f39237e8b3b5bc23a76a7eebd16ed82773007\n"
)
BADGING = (
    "package: name='com.kevin.legion' versionCode='263091200' versionName='2026-11-05-abc1234' "
    "platformBuildVersionName='16'\n"
)
# What aapt2 37.0.0 printed for the head unit's `fast` arm64-v8a split on 2026-11-06 (first line).
HEADUNIT_BADGING = (
    "package: name='com.kevin.midnightai' versionCode='263101556' versionName='1.0+72beee95' "
    "platformBuildVersionName='16' platformBuildVersionCode='36'\n"
)


@pytest.fixture
def apk(tmp_path):
    path = tmp_path / "app-fast.apk"
    path.write_bytes(b"PK\x03\x04 not really an apk")
    return path


@pytest.fixture
def pin(tmp_path):
    path = tmp_path / "signer.sha256"
    path.write_text(PINNED + "\n", encoding="utf-8")
    return path


@pytest.fixture
def tools(monkeypatch):
    """Fake apksigner/aapt2/gcloud/engine. Returns the record of what was called."""
    calls = {"uploads": [], "requests": []}
    state = {
        "signer_output": APKSIGNER_REAL,
        "latest": None,
        "post_status": 201,
        "badging": BADGING,
    }
    monkeypatch.setattr(ra, "apksigner_output", lambda apk: state["signer_output"])
    monkeypatch.setattr(ra, "aapt2_output", lambda apk: state["badging"])
    monkeypatch.setattr(ra, "upload", lambda apk, dest: calls["uploads"].append(dest))
    state["baked"] = []
    monkeypatch.setattr(ra, "baked_secret_fields", lambda apk, app: list(state["baked"]))

    def _request(method, url, token, body=None):
        calls["requests"].append((method, url, token, body))
        if method == "GET":
            if state["latest"] is None:
                return 404, {"detail": "No release has been published for phone on this engine."}
            return 200, {"version_code": state["latest"]}
        if state["post_status"] != 201:
            return state["post_status"], {"detail": "Nothing was published. Only a household owner"}
        return 201, {"id": "rel-1", **body}

    monkeypatch.setattr(ra, "engine_request", _request)
    calls["state"] = state
    return calls


def _args(apk, pin, **overrides):
    values = dict(
        app="phone",
        apk=str(apk),
        notes="fixes the double announce",
        dry_run=False,
        engine="https://engine.example",
        token="owner-token",
        bucket="legion-apk-test",
        signer_file=str(pin),
        allow_baked_keys=False,
    )
    values.update(overrides)
    return argparse.Namespace(**values)


def test_fingerprints_normalise_from_both_tools():
    keytool = (
        "FD:81:9A:DA:7F:DB:F9:8F:4B:07:2B:51:1D:2D:FF:33:"
        "CB:3D:C6:8B:72:0B:8A:9A:0F:F8:62:EB:D0:3A:AC:01"
    )
    assert ra.normalise_sha256(keytool) == PINNED
    assert ra.parse_signers(APKSIGNER_REAL) == {PINNED}
    assert ra.parse_signers("Signer #1 certificate SHA-256 digest: " + PINNED.upper()) == {PINNED}


def test_the_committed_pin_is_one_well_formed_fingerprint():
    """Format, not value: a stranger's clone pins its own key there (ticket 04)."""
    pinned = ra.read_pinned_signer()
    assert len(pinned) == 64 and pinned == pinned.lower()
    int(pinned, 16)


def test_dry_run_checks_the_signer_and_hashes_the_file_and_touches_nothing(apk, pin, tools):
    lines = []
    assert ra.run(_args(apk, pin, dry_run=True), out=lines.append) == 0
    text = "\n".join(lines)
    assert f"signer       {PINNED} (matches the pin)" in text
    assert f"sha256       {hashlib.sha256(apk.read_bytes()).hexdigest()}" in text
    assert "version      263091200 (2026-11-05-abc1234)" in text
    assert "versionCode is unchecked" in text
    assert tools["uploads"] == [] and tools["requests"] == []


def test_a_different_signer_is_refused_before_anything_else(apk, pin, tools):
    tools["state"]["signer_output"] = APKSIGNER_REAL.replace(PINNED, OTHER)
    with pytest.raises(ra.Refused, match="not the pinned key"):
        ra.run(_args(apk, pin, dry_run=True), out=lambda line: None)
    assert tools["uploads"] == [] and tools["requests"] == []


def test_an_unsigned_apk_is_refused(apk, pin, tools):
    tools["state"]["signer_output"] = "DOES NOT VERIFY\n"
    with pytest.raises(ra.Refused, match="no signer"):
        ra.run(_args(apk, pin, dry_run=True), out=lambda line: None)


def test_a_real_run_checks_uploads_then_posts(apk, pin, tools):
    tools["state"]["latest"] = 263091100
    lines = []
    assert ra.run(_args(apk, pin), out=lines.append) == 0
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert tools["uploads"] == [f"gs://legion-apk-test/phone/263091200-{sha[:12]}.apk"]
    get, post = tools["requests"]
    assert get[:3] == ("GET", "https://engine.example/api/releases/latest?app=phone", "owner-token")
    assert post[0:2] == ("POST", "https://engine.example/api/releases/")
    assert post[3] == {
        "app": "phone",
        "version_code": 263091200,
        "version_name": "2026-11-05-abc1234",
        "sha256": sha,
        "size_bytes": apk.stat().st_size,
        "object_name": f"phone/263091200-{sha[:12]}.apk",
        "notes": "fixes the double announce",
        "signer_sha256": PINNED,
    }
    assert any("published    phone 263091200" in line for line in lines)
    assert not any("owner-token" in line for line in lines)


def test_the_version_code_must_be_higher_than_the_newest(apk, pin, tools):
    tools["state"]["latest"] = 263091200
    with pytest.raises(ra.Refused, match="not higher than the newest phone release, 263091200"):
        ra.run(_args(apk, pin), out=lambda line: None)
    assert tools["uploads"] == []
    assert [r[0] for r in tools["requests"]] == ["GET"]


def test_the_first_release_has_nothing_to_beat(apk, pin, tools):
    assert ra.run(_args(apk, pin), out=lambda line: None) == 0
    assert len(tools["uploads"]) == 1


def test_an_engine_refusal_after_upload_says_the_file_is_in_the_bucket(apk, pin, tools):
    tools["state"]["post_status"] = 403
    with pytest.raises(ra.Refused, match="no release was recorded"):
        ra.run(_args(apk, pin), out=lambda line: None)


def test_a_real_run_refuses_without_its_configuration(apk, pin, tools, monkeypatch):
    for name in ("LEGION_ENGINE_TOKEN", "LEGION_ENGINE_URL", "LEGION_APK_BUCKET"):
        monkeypatch.delenv(name, raising=False)
    with pytest.raises(ra.Refused, match="LEGION_ENGINE_TOKEN not set"):
        ra.run(_args(apk, pin, token=None), out=lambda line: None)
    assert tools["uploads"] == []


def test_a_pin_file_must_hold_one_fingerprint(tmp_path):
    path = tmp_path / "signer.sha256"
    path.write_text(f"{PINNED}\n{OTHER}\n", encoding="utf-8")
    with pytest.raises(ra.Refused, match="exactly one"):
        ra.read_pinned_signer(path)
    with pytest.raises(ra.Refused, match="missing"):
        ra.read_pinned_signer(tmp_path / "absent")


def test_main_reports_a_refusal_and_exits_1(apk, pin, tools, capsys):
    tools["state"]["signer_output"] = APKSIGNER_REAL.replace(PINNED, OTHER)
    code = ra.main(
        ["--app", "phone", "--apk", str(apk), "--notes", "x", "--dry-run"]
        + ["--signer-file", str(pin)]
    )
    assert code == 1
    assert "Refused:" in capsys.readouterr().err


def test_the_head_unit_publishes_on_the_same_debug_pin(apk, pin, tools):
    """Both `fast` builds are signed with the laptop's debug key (headunit/app/build.gradle.kts)."""
    tools["state"]["badging"] = HEADUNIT_BADGING
    lines = []
    assert ra.run(_args(apk, pin, app="headunit", dry_run=True), out=lines.append) == 0
    text = "\n".join(lines)
    assert f"signer       {PINNED} (matches the pin)" in text
    assert "version      263101556 (1.0+72beee95)" in text
    assert "headunit/263101556-" in text
    assert tools["uploads"] == [] and tools["requests"] == []


def test_an_apk_of_the_other_app_is_refused(apk, pin, tools):
    """The phone and head unit share the debug key, so only the package tells them apart."""
    with pytest.raises(ra.Refused, match="com.kevin.legion, not the headunit app"):
        ra.run(_args(apk, pin, app="headunit", dry_run=True), out=lambda line: None)
    tools["state"]["badging"] = HEADUNIT_BADGING
    with pytest.raises(ra.Refused, match="com.kevin.midnightai, not the phone app"):
        ra.run(_args(apk, pin, dry_run=True), out=lambda line: None)
    assert tools["uploads"] == [] and tools["requests"] == []


# -----------------------------------------------------------------------------
# Baked keys (architecture review 2026-11-10, item 6): a real dex, built by hand
# -----------------------------------------------------------------------------


NUL = bytes(1)
DEX_MAGIC = b"dex" + bytes([10]) + b"035" + NUL


def _uleb(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _dex(descriptor: str, fields: list[tuple[str, object]]) -> bytes:
    """A minimal dex holding one class with static fields and their initial values.

    Only the parts `dex_static_strings` reads are real: header offsets, string_ids, type_ids,
    field_ids, one class_def, class_data and the static value array."""
    import struct

    strings = [descriptor, "Ljava/lang/String;", "Z"] + [name for name, _ in fields]
    value_index = {}
    for _, value in fields:
        if isinstance(value, str):
            value_index[value] = len(strings)
            strings.append(value)
    header, n_str, n_type, n_field = 0x70, len(strings), 3, len(fields)
    string_ids_off = header
    type_ids_off = string_ids_off + 4 * n_str
    field_ids_off = type_ids_off + 4 * n_type
    class_defs_off = field_ids_off + 8 * n_field
    data = bytearray()
    data_off = class_defs_off + 32
    string_offsets = []
    for text in strings:
        string_offsets.append(data_off + len(data))
        raw = text.encode("utf-8")
        data += _uleb(len(text)) + raw + NUL
    class_data_off = data_off + len(data)
    data += _uleb(n_field) + _uleb(0) + _uleb(0) + _uleb(0)
    for i in range(n_field):
        data += _uleb(0 if i == 0 else 1) + _uleb(0x19)
    static_values_off = data_off + len(data)
    data += _uleb(n_field)
    for _, value in fields:
        if isinstance(value, bool):
            data.append((int(value) << 5) | 0x1F)
        else:
            data += bytes([0x17]) + bytes([value_index[value]])
    blob = bytearray(header)
    blob[:8] = DEX_MAGIC
    struct.pack_into("<II", blob, 0x38, n_str, string_ids_off)
    struct.pack_into("<II", blob, 0x40, n_type, type_ids_off)
    struct.pack_into("<II", blob, 0x50, n_field, field_ids_off)
    struct.pack_into("<II", blob, 0x60, 1, class_defs_off)
    for offset in string_offsets:
        blob += struct.pack("<I", offset)
    blob += struct.pack("<III", 0, 1, 2)  # type_ids -> descriptor, String, Z
    for i, (_, value) in enumerate(fields):
        blob += struct.pack("<HHI", 0, 2 if isinstance(value, bool) else 1, 3 + i)
    blob += struct.pack("<IIIIIIII", 0, 1, 0, 0, 0, 0, class_data_off, static_values_off)
    return bytes(blob + data)


def _apk_with(tmp_path, dex: bytes, name="app-fast.apk"):
    import zipfile

    path = tmp_path / name
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"not read")
        archive.writestr("classes.dex", DEX_MAGIC + bytes(0x68))  # empty: no classes
        archive.writestr("classes2.dex", dex)
    return path


FAKE_GEMINI = "AIza" + "x" * 35  # shape only; never a real key
PHONE_BC = "Lcom/kevin/legion/BuildConfig;"


def test_dex_reader_returns_buildconfig_strings_and_skips_non_strings():
    dex = _dex(
        PHONE_BC,
        [("AIRNOW_API_KEY", ""), ("DEBUG", False), ("GEMINI_API_KEY", FAKE_GEMINI)],
    )
    assert ra.dex_static_strings(dex, PHONE_BC) == {
        "AIRNOW_API_KEY": "",
        "GEMINI_API_KEY": FAKE_GEMINI,
    }
    assert ra.dex_static_strings(dex, "Lcom/kevin/midnightai/BuildConfig;") is None


def test_a_baked_key_is_found_by_name_in_a_second_dex(tmp_path):
    apk = _apk_with(
        tmp_path, _dex(PHONE_BC, [("AIRNOW_API_KEY", "ABC-123"), ("GEMINI_API_KEY", FAKE_GEMINI)])
    )
    assert ra.baked_secret_fields(apk, "phone") == ["GEMINI_API_KEY", "AIRNOW_API_KEY"]


def test_a_nokey_build_passes_and_the_public_mapbox_token_is_ignored(tmp_path):
    apk = _apk_with(
        tmp_path,
        _dex(
            PHONE_BC,
            [("AIRNOW_API_KEY", ""), ("GEMINI_API_KEY", ""), ("MAPBOX_ACCESS_TOKEN", "pk.public")],
        ),
    )
    assert ra.baked_secret_fields(apk, "phone") == []


def test_an_apk_without_buildconfig_or_not_a_zip_is_refused(tmp_path):
    apk = _apk_with(tmp_path, _dex("Lcom/other/BuildConfig;", [("GEMINI_API_KEY", "")]))
    with pytest.raises(ra.Refused, match="cannot be checked"):
        ra.baked_secret_fields(apk, "phone")
    junk = tmp_path / "junk.apk"
    junk.write_bytes(b"not a zip")
    with pytest.raises(ra.Refused, match="is it an APK"):
        ra.baked_secret_fields(junk, "phone")


def test_a_baked_key_is_refused_before_hashing_or_upload(apk, pin, tools):
    tools["state"]["baked"] = ["GEMINI_API_KEY"]
    with pytest.raises(ra.Refused, match="baked GEMINI_API_KEY.*-Pnokey"):
        ra.run(_args(apk, pin), out=lambda line: None)
    assert tools["uploads"] == [] and tools["requests"] == []


def test_allow_baked_keys_publishes_with_a_warning_that_names_but_never_prints_the_key(
    apk, pin, tools
):
    tools["state"]["baked"] = ["GEMINI_API_KEY", "AIRNOW_API_KEY"]
    lines = []
    assert ra.run(_args(apk, pin, dry_run=True, allow_baked_keys=True), out=lines.append) == 0
    assert any(
        line.startswith("WARNING") and "GEMINI_API_KEY, AIRNOW_API_KEY" in line for line in lines
    )


def test_main_takes_the_override_flag(apk, pin, tools):
    tools["state"]["baked"] = ["GEMINI_API_KEY"]
    base = ["--app", "phone", "--apk", str(apk), "--notes", "x", "--dry-run"]
    base += ["--signer-file", str(pin)]
    assert ra.main(base) == 1
    assert ra.main(base + ["--allow-baked-keys"]) == 0
