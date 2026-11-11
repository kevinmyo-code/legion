"""Hardened launcher for the laptop voice service (Windows, Kevin's own account).

    python -m laptop_voice.run                 start the service (generates the token on first run)
    python -m laptop_voice.run rotate-token    write a new token (the phone must get the new one)
    python -m laptop_voice.run token-path      print WHERE the token file is (never the token)

What it does before the service starts:
- Token: one random 256-bit token in %LOCALAPPDATA%\\legion-voice\\token, outside the repo, ACL'd to the
  current user only (icacls, inheritance removed). Never printed or logged. Rotate with the command above.
- Environment: the child gets an ALLOWLISTED environment only (see ENV_ALLOW). Every other variable
  is dropped, which removes cloud credentials and tokens (GOOGLE_APPLICATION_CREDENTIALS,
  CLOUDSDK_CONFIG, GEMINI_API_KEY, ANTHROPIC_API_KEY, GITHUB_TOKEN, GH_TOKEN, AWS_*, CLOUDFLARE_*,
  and anything else). Hugging Face runs offline (HF_HUB_OFFLINE=1) so no network fetch happens at
  run time; first-time model download is `--allow-download`.
- Resources: the child runs in a Windows Job Object with a commit (private memory) cap
  (default 8 GB) and kill-on-close, at normal priority.
"""

from __future__ import annotations

import argparse
import ctypes
import os
import secrets
import subprocess
import sys
from ctypes import wintypes
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOKEN_FILE = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local")) / "legion-voice" / "token"
MODELS = Path.home() / ".legion" / "laptop-voice" / "models"

# Variables the child may keep. Everything else is dropped.
ENV_ALLOW = ("SYSTEMROOT", "WINDIR", "SYSTEMDRIVE", "COMSPEC", "PATHEXT", "TEMP", "TMP", "NUMBER_OF_PROCESSORS",
             "PROCESSOR_ARCHITECTURE", "LOCALAPPDATA", "USERNAME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
             "CUDA_VISIBLE_DEVICES")


def ensure_token(rotate: bool = False) -> Path:
    TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    if rotate or not TOKEN_FILE.exists():
        TOKEN_FILE.write_text(secrets.token_urlsafe(32), encoding="ascii")
    if os.name == "nt":
        user = os.environ.get("USERNAME") or os.getlogin()
        for target in (TOKEN_FILE.parent, TOKEN_FILE):
            subprocess.run(["icacls", str(target), "/inheritance:r", "/grant:r", f"{user}:(F)"],
                           check=True, capture_output=True)
    else:
        TOKEN_FILE.chmod(0o600)
    return TOKEN_FILE


def child_env(allow_download: bool, hf_home: str | None = None) -> dict:
    env = {k: os.environ[k] for k in ENV_ALLOW if k in os.environ}
    sysroot = os.environ.get("SYSTEMROOT", r"C:\Windows")
    scripts = Path(sys.executable).parent
    env["PATH"] = os.pathsep.join([str(scripts), str(Path(sysroot) / "System32"), sysroot])
    env["LEGION_VOICE_TOKEN_FILE"] = str(TOKEN_FILE)
    env["LEGION_VOICE_MODELS"] = str(MODELS)
    env["HF_HOME"] = hf_home or str(MODELS / "hf-home")
    env["NO_TORCH_COMPILE"] = "1"  # Kyutai/moshi: torch.compile needs Triton, absent on Windows
    env["HF_HUB_DISABLE_TELEMETRY"] = "1"
    if not allow_download:
        env["HF_HUB_OFFLINE"] = "1"
    env["PYTHONNOUSERSITE"] = "1"
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    return env


# ---- Windows Job Object (memory cap, kill on close) ----
class IO_COUNTERS(ctypes.Structure):
    _fields_ = [(n, ctypes.c_ulonglong) for n in ("ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                                                   "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]


class JOBOBJECT_BASIC_LIMIT_INFORMATION(ctypes.Structure):
    _fields_ = [("PerProcessUserTimeLimit", ctypes.c_longlong), ("PerJobUserTimeLimit", ctypes.c_longlong),
                ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD), ("SchedulingClass", wintypes.DWORD)]


class JOBOBJECT_EXTENDED_LIMIT_INFORMATION(ctypes.Structure):
    _fields_ = [("BasicLimitInformation", JOBOBJECT_BASIC_LIMIT_INFORMATION), ("IoInfo", IO_COUNTERS),
                ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t)]


JOB_OBJECT_LIMIT_PROCESS_MEMORY = 0x100
JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
JOB_OBJECT_LIMIT_PRIORITY_CLASS = 0x20
NORMAL_PRIORITY_CLASS = 0x20
JobObjectExtendedLimitInformation = 9


def job_with_cap(cap_bytes: int):
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.CreateJobObjectW.restype = wintypes.HANDLE
    job = k32.CreateJobObjectW(None, None)
    if not job:
        raise OSError(ctypes.get_last_error(), "CreateJobObjectW")
    info = JOBOBJECT_EXTENDED_LIMIT_INFORMATION()
    info.BasicLimitInformation.LimitFlags = (JOB_OBJECT_LIMIT_PROCESS_MEMORY | JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                                             | JOB_OBJECT_LIMIT_PRIORITY_CLASS)
    info.BasicLimitInformation.PriorityClass = NORMAL_PRIORITY_CLASS
    info.ProcessMemoryLimit = cap_bytes
    if not k32.SetInformationJobObject(wintypes.HANDLE(job), JobObjectExtendedLimitInformation,
                                       ctypes.byref(info), ctypes.sizeof(info)):
        raise OSError(ctypes.get_last_error(), "SetInformationJobObject")
    return k32, job


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("command", nargs="?", default="start", choices=["start", "rotate-token", "token-path"])
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", default="8765")
    p.add_argument("--stt", default="whisper", choices=["whisper", "parakeet", "kyutai"])
    p.add_argument("--tts", default="kokoro", choices=["kokoro", "kyutai"])
    p.add_argument("--hf-home", default=None, help="Hugging Face cache holding the models (default <models>/hf-home)")
    p.add_argument("--mem-cap-gb", type=float, default=None,
                   help="process commit cap; default 8 (Whisper/Kokoro) or 16 (Kyutai: measured peak commit "
                        "12.6 GB on Windows, mostly CUDA mappings, working set ~2 GB)")
    p.add_argument("--allow-download", action="store_true", help="first run only: let models download")
    p.add_argument("--eot-threshold", type=float, default=None,
                   help="Kyutai: pause probability that ends a turn (default 0.6; higher = fewer mid-sentence "
                        "splits, later end of turn)")
    a = p.parse_args()
    if a.command == "token-path":
        print(ensure_token())
        return
    if a.command == "rotate-token":
        print(f"new token written to {ensure_token(rotate=True)}; copy it to the phone from that file")
        return
    ensure_token()
    if a.mem_cap_gb is None:
        a.mem_cap_gb = 16.0 if "kyutai" in (a.stt, a.tts) else 8.0
    # -I ignores PYTHON* variables, the user site and the cwd; a tiny bootstrap puts tools/ on the path.
    cmd = [sys.executable, "-I", "-c",
           "import sys; sys.path.insert(0, sys.argv[1]); from laptop_voice import server; sys.argv = sys.argv[1:]; server.main()",
           str(HERE.parent), "--host", a.host, "--port", a.port, "--stt", a.stt, "--tts", a.tts]
    env = child_env(a.allow_download, a.hf_home)
    if a.eot_threshold is not None:
        if not 0.3 <= a.eot_threshold <= 0.99:
            raise SystemExit("--eot-threshold must be 0.3 to 0.99")
        env["LEGION_VOICE_KYUTAI_PAUSE"] = repr(float(a.eot_threshold))
    if os.name == "nt":
        # Put THIS launcher in the job before starting the service, so every process it starts is born
        # inside the job. (A venv's python.exe is a shim that starts the real interpreter as a child;
        # assigning the shim after Popen raced that child, which could escape the cap.)
        k32, job = job_with_cap(int(a.mem_cap_gb * 2**30))
        k32.GetCurrentProcess.restype = wintypes.HANDLE
        if not k32.AssignProcessToJobObject(wintypes.HANDLE(job), wintypes.HANDLE(k32.GetCurrentProcess())):
            raise OSError(ctypes.get_last_error(), "AssignProcessToJobObject")
        proc = subprocess.Popen(cmd, env=env, cwd=str(MODELS), creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
    else:
        proc = subprocess.Popen(cmd, env=env, cwd=str(MODELS))
    try:
        code = proc.wait()
        if code:
            print(f"service exited with code {code}; a silent exit during model load is usually the memory "
                  f"cap (--mem-cap-gb, now {a.mem_cap_gb:g})", file=sys.stderr)
        sys.exit(code)
    except KeyboardInterrupt:
        proc.terminate()
        sys.exit(proc.wait())


if __name__ == "__main__":
    main()
