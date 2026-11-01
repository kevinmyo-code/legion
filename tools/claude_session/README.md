# Claude session worker

Map `.scratch/claude-session/`, ticket 04. One worker per laptop lets Alfred hand tasks to Claude
Code on that laptop, on whichever account its `claude` CLI is logged into (ruling 1). Python 3.11+,
stdlib only. No `ANTHROPIC_API_KEY` (the worker strips it from the child), never
`bypassPermissions`.

## Shape

```
Task Scheduler "LEGION Claude session" (at logon, runs through a lock, pythonw.exe)
  worker.py           heartbeat 20 s, claim, keep-awake on mains, cancel, 30 min cap, uploads
    session.py        claude -p --input-format stream-json --output-format stream-json --verbose
                        --permission-mode dontAsk --permission-prompts none
                        --settings %APPDATA%\legion-claude\worker-settings.json  (absolute)
                        --setting-sources project,local   (Kevin's user hooks/plugins stay out)
                        --append-system-prompt <main_session_prompt.md>
                        --add-dir <each fixed drive | the repos root>
                        --allowedTools Bash PowerShell Read Write Edit Glob Grep Agent TaskStop [WebFetch WebSearch]
                        --session-id <uuid> (new day) | --resume <uuid> (restart)
      helpers finish with:  python report.py --task N --status done|failed|needs_confirmation ...
      helpers send mail with: python send_mail.py --task N --token T ...   (only with a token)
    asklist_hook.py   PreToolUse hook, only in worker-settings.json, never in ~/.claude/settings.json
```

| File | What |
|---|---|
| `worker.py` | The loop. `--check` validates the config and prints the command shape. |
| `session.py` | Builds the command, child env (strips `CLAUDECODE`, `CLAUDE_CODE_*`, `ANTHROPIC_API_KEY`), reads stream-json. |
| `engine_client.py` | `/api/claude/` worker endpoints (heartbeat, claim, finish, files). |
| `report.py` | How a helper finishes a task. Refuses `needs_confirmation` without `--question`. |
| `send_mail.py` | Alfred's Gmail over SMTP. Refuses without the worker's confirmation token; Kevin cc'd. |
| `asklist_hook.py` | Ruling 6 ask list and ruling 5 work-laptop scope. Fails closed. |
| `main_session_prompt.md` | The main session's appended system prompt. |
| `install.ps1` / `uninstall.ps1` / `status.ps1` | Kevin runs these by hand. |
| `fake_claude.py`, `fake_engine.py`, `test_*.py` | Tests. Never the real CLI, never real mail. |

## Install (Kevin, by hand)

1. Claude Code installed and logged in (`claude`, then `/login`) as Kevin's Windows user.
2. Python 3.11+ on PATH (`pythonw.exe` beside `python.exe`).
3. From the repo root:

   ```
   .\tools\claude_session\install.ps1 -Name Alienware -Scope full -StartNow
   .\tools\claude_session\install.ps1 -Name "work laptop" -Scope listed -ReposRoot C:\repos -StartNow
   ```

   It asks for the LEGION email and password once and trades them for a device token at
   `POST <engine>/api/auth/login` with `{email, password, device_name: "claude-session-<name>"}`
   (`server/household/views.py` `LoginView`; the reply's `token` is shown once and only its hash is
   stored server-side). The token goes into `%APPDATA%\legion-claude\config.json`, readable by
   Kevin's account only. Revoke it from LEGION's devices list (`/api/auth/devices`) when a laptop
   is retired.
4. Optional: Alfred's Gmail address, a Google app password for it, and Kevin's own address.
   Without them `send_mail.py` refuses and says so. The work laptop only mails Kevin himself,
   summary text only, no attachments (ruling 18).
5. **Lid:** Control Panel > Power Options > Choose what closing the lid does > Plugged in:
   **Do nothing.** The worker holds `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)`
   while on mains, which stops idle sleep but not lid-close sleep.

`status.ps1` shows the task, config (no secrets), session and the log tail. `uninstall.ps1
[-Purge]` removes the task (and with `-Purge` the config folder).

## Config (`%APPDATA%\legion-claude\config.json`)

| Key | |
|---|---|
| `name` | Spoken name: `Alienware`, `work laptop` |
| `scope` | `full` (every fixed drive, read and write) or `listed` (repos root only, summaries only) |
| `engine_url`, `token` | Engine base URL and device token |
| `repos_root` | `listed` only |
| `mail` | `{address, app_password, kevin, smtp_host, smtp_port}`; on `listed` only text to `kevin` |
| optional | `claude_cmd`, `heartbeat_s`, `task_cap_s`, `hang_s`, `add_dirs`, `keep_awake` |

## Rules it enforces

- **report.py is the truth**, not the main session's chat. A helper that ends without reporting
  becomes `failed` "The helper stopped without reporting a result."
- **Cancel and the 30 minute cap** send `[cancel N]` / `[stop N]` asking the main session to
  `TaskStop` that one helper, and finish the task in words (`cancelled` or `failed`). Kill and `--resume` only when
  the main session does not answer for 10 minutes.
- **Restart:** the worker resumes the same session id the same day; tasks running at the restart
  are finished `failed` (helpers die with the process). Not re-sent: a re-run could repeat a send.
- **Rotation:** a new session id each day, at the first idle moment. Follow-ups carry the parent
  task's request, result or question in the message, so continuity does not need the old session.
- **Confirmations:** a follow-up with `parent` and `confirms: true` (the phone sets it when Kevin
  answered a `needs_confirmation` task) gets a `kind: yes` token for that task: the hook lets that task's helper through the ask list and
  `send_mail.py` may send once, Kevin cc'd. A task with `deliver: "email_self"` (ruling 17) gets a
  `kind: email_self` token: one mail to Kevin's own address, nothing else.
- **Uploads:** only files the engine marks requested **and** the task listed with `--found`, at
  most 25 MB. The work laptop never uploads and never sends `found`.
- **Heartbeat `progress`:** each running task's latest helper step (`system/task_progress`
  description), 120 characters at most, held in memory only.
- **Logs** (`logs\worker.log`, `logs\hook.log`) hold ids, statuses, counts, exit codes. Never a
  request, summary, question, progress text or path. `state.json` does keep requests and
  summaries locally (48 h) so follow-ups can carry context; it is not a log.

## Tests

```
python -m unittest discover -s tools/claude_session -p "test_*.py" -v
```

Fake engine on 127.0.0.1, fake `claude` that emits the ticket 02 event shapes and runs the real
`report.py`. Fake SMTP. About 30 s.
