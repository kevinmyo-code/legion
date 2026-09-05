"""Starts an MCP stdio server with the repo's private env loaded.

`.mcp.json` is committed and the repo is public, so no secret can live there. Secrets live in
`.claude/mcp.env` (gitignored, KEY=VALUE per line). This launcher reads that file into the
environment, then runs the server command it was given with stdin/stdout passed straight
through, so the MCP framing is untouched.

    python tools/mcp/launch.py [--require VAR ...] [--alias TARGET=SOURCE ...] -- <command> [args...]

`--require LEGION_PG_URL` refuses to start the server when that variable is still unset after
the file is loaded, and says in one line what to put where, instead of letting the server die
with a traceback. `--alias DATABASE_URI=LEGION_PG_URL` copies one variable into another name
after the file is loaded, for third-party servers that expect their own variable name. A
variable already set in the process environment is never overwritten by the file: the shell
wins over the file.

The working directory is set to the repo root before the server starts, so `.mcp.json` can use
repo-relative paths regardless of where Claude Code launched from.

Stdlib only. Nothing here needs `uv`; the servers it launches may.
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENV_FILE = os.path.join(ROOT, ".claude", "mcp.env")


def load_env_file(path: str) -> int:
    """Loads KEY=VALUE lines into os.environ without overriding existing keys.

    Returns how many keys were set. Blank lines and `#` comments are skipped. A leading
    `export ` is tolerated so the same file can be sourced by a shell. Surrounding single or
    double quotes on the value are stripped.
    """
    if not os.path.isfile(path):
        return 0
    count = 0
    with open(path, encoding="utf-8-sig") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            if line.startswith("export "):
                line = line[len("export "):].lstrip()
            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            if key and key not in os.environ:
                os.environ[key] = value
                count += 1
    return count


def main(argv: list[str]) -> int:
    aliases: list[tuple[str, str]] = []
    required: list[str] = []
    i = 0
    while i < len(argv) and argv[i] != "--":
        if argv[i] == "--require" and i + 1 < len(argv):
            required.append(argv[i + 1])
            i += 2
            continue
        if argv[i] == "--alias" and i + 1 < len(argv) and "=" in argv[i + 1]:
            target, source = argv[i + 1].split("=", 1)
            aliases.append((target, source))
            i += 2
            continue
        sys.stderr.write("launch.py: unknown option %r (expected --require, --alias or --)\n" % argv[i])
        return 2
    command = argv[i + 1:] if i < len(argv) else []
    if not command:
        sys.stderr.write("launch.py: no server command after --\n")
        return 2

    load_env_file(ENV_FILE)
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        sys.stderr.write(
            "launch.py: %s not set. Put %s=<value> in %s (gitignored) or export it in the shell "
            "that starts Claude Code, then restart the MCP server. Server not started.\n"
            % (", ".join(missing), missing[0], ENV_FILE))
        return 1
    for target, source in aliases:
        if target not in os.environ and source in os.environ:
            os.environ[target] = os.environ[source]

    # On Windows `npx` and `uvx` are .cmd / .exe shims that a bare CreateProcess will not find.
    resolved = shutil.which(command[0])
    if resolved is None:
        sys.stderr.write("launch.py: %r not found on PATH\n" % command[0])
        return 127
    command[0] = resolved

    os.chdir(ROOT)
    try:
        return subprocess.call(command)
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
