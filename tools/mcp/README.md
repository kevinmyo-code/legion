# MCP servers

Five MCP servers every Claude Code session and subagent in this repo gets. `.mcp.json` at the
repo root declares them (project scope, committed); `.claude/agents/*.md` lists which seat may
call which tool. Installed 2026-09-05.

| Server | Source | Tools | Needs |
|---|---|---|---|
| `pg` | `postgres-mcp` (crystaldba), `--access-mode=restricted` | `list_schemas`, `list_objects`, `get_object_details`, `explain_query`, `analyze_workload_indexes`, `analyze_query_indexes`, `analyze_db_health`, `get_top_queries`, `execute_sql` (read-only transaction) | `LEGION_PG_URL` |
| `mobile` | `@mobilenext/mobile-mcp` 1.0.2 | 27 tools: list devices, launch, terminate, install (`adb install -r`), screenshot, tap, swipe, type, elements on screen, orientation, screen recording, crash logs, plus cloud-device and uninstall tools this repo does not use | `adb` on PATH, a device on `adb devices` |
| `gradle` | `tools/mcp/gradle/` (ours) | `run_task`, `test_totals`, `detekt_summary` | nothing |
| `canvas` | `tools/mcp/canvas/` (ours) | `courses`, `assignments`, `discussion_topics`, `todo` | `CANVAS_TOKEN` |
| `board` | `tools/mcp/board/` (ours) | `ready`, `blocked`, `ticket`, `map` | nothing |

Everything is read-only except `gradle.run_task` (starts Gradle, writes under `app/build/` and
`build/mcp-logs/`) and the `mobile` tools that touch the phone (install, tap, type, launch).
`mobile_uninstall_app` is denied in `.claude/settings.json` and absent from the device agent's
list: an uninstall destroyed the Keystore key and the receipt photos once, and that rule is not
the MCP server's to relax.

## How a server starts

Every entry in `.mcp.json` runs through `tools/mcp/launch.py`:

```
python tools/mcp/launch.py [--require VAR] [--alias TARGET=SOURCE] -- <server command>
```

It reads `.claude/mcp.env` into the environment, refuses to start a server whose `--require`d
variable is still missing (one line on stderr saying what to put where), copies an alias for
third-party servers that want their own variable name (`DATABASE_URI` for postgres-mcp), sets
the working directory to the repo root, and runs the command with stdio passed through. Stdlib
only; `python` must be on PATH. The three servers we wrote run under `uv run --project
tools/mcp/<name>`, which creates `tools/mcp/<name>/.venv` on first start (gitignored) from the
committed `uv.lock`.

Why a launcher and not `"env": {"X": "${X}"}` in `.mcp.json`: Claude Code expands `${X}` from
the environment Claude Code itself was started with. A secret would have to be exported in every
shell on every machine, and one `export` in a committed script is one secret in a public repo.
The file is the one place, and it is gitignored.

## Secrets: `.claude/mcp.env`

The repo is public. No token, password or connection string ever lands in a tracked file.
`.claude/mcp.env` is gitignored (`.gitignore`, MCP servers block) and is `KEY=VALUE` per line;
`#` comments, blank lines and a leading `export ` are fine. A variable already set in the shell
wins over the file.

```
# .claude/mcp.env - never committed
LEGION_PG_URL=postgresql://legion_reader:<password>@db.gccxiqusqxkjmjmaadpz.supabase.co:5432/postgres?sslmode=require
CANVAS_TOKEN=<token from Canvas > Account > Settings > New Access Token>
```

**`LEGION_PG_URL`.** The `legion_reader` role does not exist yet; Kevin creates it and its
password himself. Two facts about the host, both checked 2026-09-05 from this machine:
`db.gccxiqusqxkjmjmaadpz.supabase.co` publishes only an AAAA (IPv6) record, and psycopg on this
machine failed to resolve it (`getaddrinfo failed`). If the direct host does not connect, use the
Supabase session pooler instead, which has an IPv4 address: host
`aws-0-<region>.pooler.supabase.com`, port `5432`, user `legion_reader.gccxiqusqxkjmjmaadpz`
(role dot project ref). The Supabase dashboard's Connect panel prints the exact pooler host.

**`CANVAS_TOKEN`.** Not generated yet. The `canvas` server starts and lists its tools without
it; every call then returns `{"ok": false, "error": "CANVAS_TOKEN not set. ..."}` and makes no
request. Base URL is `https://uhv.instructure.com`; override with `CANVAS_BASE_URL` if it moves.

Alternative to the file: `claude mcp add --scope user <name> -e KEY=value -- <command>` stores
the value in Claude Code's user-scope store outside the repo. The project entry in `.mcp.json`
would then be duplicated by the user-scope one; pick one. The file is the documented path.

## First run

`.mcp.json` servers need a one-time approval: run `claude` interactively in the repo and accept
the prompt. Until then `claude mcp list` shows them as `Pending approval`. Restart the session
after editing `.claude/mcp.env`; servers read it at start.

## Smoke tests

One line per server. Each starts the server over stdio, completes the MCP handshake, lists
tools and calls one harmless read. `smoke.py` is the generic client used on 2026-09-05; it is
not in the repo, so the shortest check with what IS in the repo is below.

```
# all five at once: health per server (needs the one-time approval first)
claude mcp list

# board: the real board, no secrets
python tools/mcp/launch.py -- uv run --project tools/mcp/board python -c "import sys; sys.path.insert(0,'tools/mcp/board'); import server; print(server.ready()['count'], 'ready')"

# gradle: totals from the XML on disk, no Gradle run
python tools/mcp/launch.py -- uv run --project tools/mcp/gradle python -c "import sys; sys.path.insert(0,'tools/mcp/gradle'); import server; t=server.test_totals(); print(t['tests'], t['failures'], t['errors'])"

# canvas: without a token this prints the not-set message and makes no request
python tools/mcp/launch.py -- uv run --project tools/mcp/canvas python -c "import sys; sys.path.insert(0,'tools/mcp/canvas'); import server; print(server.courses())"

# pg: exits with the launcher's one-line message until LEGION_PG_URL is set
python tools/mcp/launch.py --require LEGION_PG_URL --alias DATABASE_URI=LEGION_PG_URL -- uvx --python 3.13 --with "mcp<2" postgres-mcp --help

# mobile: lists the attached phone
npx -y @mobilenext/mobile-mcp@1.0.2 --help
```

Two pins in the `pg` command are deliberate. `--python 3.13`: `postgres-mcp` depends on
`pglast`, which has no wheel for the 3.14 interpreter `uv` picks by default and fails to build
from source here. `--with "mcp<2"`: `postgres-mcp` 0.3.x imports `mcp.server.fastmcp`, which
`mcp` 2.x renamed, so an unpinned resolve starts and immediately crashes. Our three servers pin
`mcp>=1.2,<2` in their `pyproject.toml` for the same reason.

## Writing another one

`tools/mcp/<name>/pyproject.toml` (`dependencies = ["mcp>=1.2,<2"]`, `[tool.uv] package =
false`) and a `server.py` using `mcp.server.fastmcp.FastMCP`. Read-only by default. A write tool
is named so it is obviously a write and its result says what it did or did not do (CLAUDE.md
section 7: nothing claims success unless the underlying action ran). Register it in `.mcp.json`
through the launcher, add the tool names to the agent files that may call it, add a row here.
