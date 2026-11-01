<#
.SYNOPSIS
  Install the LEGION Claude session worker on this laptop (map claude-session, ticket 04).

.DESCRIPTION
  Kevin runs this by hand, once per laptop. It is a system change (a Scheduled Task), so nothing
  runs it for him.

  1. Asks the laptop's spoken name ("Alienware" or "work laptop") and scope (full or listed).
  2. Signs in to LEGION once (POST <engine>/api/auth/login) and keeps the device token it returns
     in %APPDATA%\legion-claude\config.json. The password is never written anywhere.
  3. Optional: Alfred's Gmail address, its app password and Kevin's own address, for
     send_mail.py (ruling 14). Press Enter to skip; mail then stays off. On the work laptop
     (ruling 18) send_mail.py only sends text to Kevin's own address, no attachments.
  4. Checks the config with `worker.py --check` (no Claude call).
  5. Registers the Scheduled Task "LEGION Claude session": at logon, only while Kevin is logged on
     (so it keeps running through Win+L), not elevated, no time limit, restarts on failure,
     runs on battery. Uses pythonw.exe so no window opens.

  Re-running keeps the existing token and mail settings unless -NewToken / -NewMail is given.

.PARAMETER Name
  Spoken name: "Alienware" or "work laptop".
.PARAMETER Scope
  full (every fixed drive) or listed (only -ReposRoot, summaries only).
.PARAMETER Engine
  The LEGION engine's base URL. Defaults to $env:LEGION_SERVER.
.PARAMETER ReposRoot
  listed scope only: the repos folder.
.PARAMETER StartNow
  Start the task right after registering it.
.PARAMETER SessionHooks
  Add the sessions reporter hooks (ticket 06) to ~/.claude/settings.json without asking.
.PARAMETER NoSessionHooks
  Do not ask about the sessions reporter hooks.

.EXAMPLE
  .\tools\claude_session\install.ps1 -Name Alienware -Scope full -StartNow
  .\tools\claude_session\install.ps1 -Name "work laptop" -Scope listed -ReposRoot C:\repos
#>
[CmdletBinding()]
param(
    [string]$Name,
    [ValidateSet('full', 'listed', '')]
    [string]$Scope = '',
    [string]$Engine = $env:LEGION_SERVER,
    [string]$ReposRoot,
    [switch]$NewToken,
    [switch]$NewMail,
    [switch]$StartNow,
    [switch]$SessionHooks,
    [switch]$NoSessionHooks
)

$ErrorActionPreference = 'Stop'
$TaskName = 'LEGION Claude session'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Worker = Join-Path $Here 'worker.py'
$ConfigDir = Join-Path $env:APPDATA 'legion-claude'
$ConfigPath = Join-Path $ConfigDir 'config.json'

function Read-Plain([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

# --- Python 3.11+ and the Claude CLI -------------------------------------------------------------
$py = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $py) { throw 'Python 3.11 or newer is not on PATH. Install it, then run this again.' }
$ver = & $py -c "import sys; print('%d.%d' % sys.version_info[:2])"
if ([version]$ver -lt [version]'3.11') { throw "Python $ver found; the worker needs 3.11 or newer." }
$pyw = Join-Path (Split-Path -Parent $py) 'pythonw.exe'
if (-not (Test-Path $pyw)) { throw "pythonw.exe not found next to $py." }
if (-not (Get-Command claude -ErrorAction SilentlyContinue)) {
    throw 'The claude CLI is not on PATH. Install Claude Code and log in (claude, then /login) first.'
}
if ([Environment]::GetEnvironmentVariable('ANTHROPIC_API_KEY', 'User') -or
    [Environment]::GetEnvironmentVariable('ANTHROPIC_API_KEY', 'Machine')) {
    Write-Warning 'ANTHROPIC_API_KEY is set. The worker strips it so the CLI uses the logged-in account (ruling 1).'
}

# --- Config ----------------------------------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
$cfg = @{}
if (Test-Path $ConfigPath) {
    $old = Get-Content $ConfigPath -Raw | ConvertFrom-Json
    foreach ($p in $old.PSObject.Properties) { $cfg[$p.Name] = $p.Value }
}

if (-not $Name) { $Name = if ($cfg.name) { $cfg.name } else { Read-Host 'Spoken name for this laptop (Alienware or work laptop)' } }
if (-not $Scope) {
    $Scope = if ($cfg.scope) { $cfg.scope } else { Read-Host 'Scope: full (every fixed drive) or listed (repos folder only)' }
}
if ($Scope -notin @('full', 'listed')) { throw "Scope must be full or listed, not '$Scope'." }
if (-not $Engine) { $Engine = if ($cfg.engine_url) { $cfg.engine_url } else { Read-Host 'LEGION engine URL (https://...)' } }
$Engine = $Engine.TrimEnd('/')
if ($Scope -eq 'listed') {
    if (-not $ReposRoot) { $ReposRoot = if ($cfg.repos_root) { $cfg.repos_root } else { Read-Host 'Repos folder (the only folder this laptop may read)' } }
    if (-not (Test-Path $ReposRoot -PathType Container)) { throw "Repos folder not found: $ReposRoot" }
    $cfg.repos_root = (Resolve-Path $ReposRoot).Path
}
$cfg.name = $Name
$cfg.scope = $Scope
$cfg.engine_url = $Engine

if ($NewToken -or -not $cfg.token) {
    $email = Read-Host 'LEGION email'
    $plain = Read-Plain 'LEGION password'
    try {
        $payload = @{ email = $email; password = $plain; device_name = "claude-session-$Name" } | ConvertTo-Json
        try {
            $reply = Invoke-RestMethod -Method Post -Uri "$Engine/api/auth/login" -ContentType 'application/json' -Body $payload
        } catch {
            throw "Sign-in to $Engine was refused or unreachable: $($_.Exception.Message)"
        }
    } finally { $plain = $null; $payload = $null }
    if ([string]::IsNullOrWhiteSpace($reply.token)) { throw 'The engine signed you in but sent no token.' }
    $cfg.token = $reply.token
    Write-Host "Device token issued as 'claude-session-$Name'. Revoke it from LEGION's devices list if this laptop is retired."
}

if ($NewMail -or -not $cfg.mail) {
    $addr = Read-Host "Alfred's Gmail address for sending task mail (Enter to skip; mail stays off)"
    if ($addr) {
        $appPw = Read-Plain "App password for $addr (Google account > Security > App passwords)"
        $kevin = Read-Host "Kevin's own address (cc'd on every send, and the only 'email it to me' target)"
        $cfg.mail = @{ address = $addr; app_password = $appPw; kevin = $kevin; smtp_host = 'smtp.gmail.com'; smtp_port = 465 }
        $appPw = $null
    }
}

$json = $cfg | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText($ConfigPath, $json, (New-Object Text.UTF8Encoding $false))
# Only Kevin's account may read the file (it holds the device token and the app password).
& icacls $ConfigPath /inheritance:r /grant:r "$($env:USERNAME):(F)" | Out-Null
$json = $null

# --- Check -----------------------------------------------------------------------------------------
& $py $Worker --check
if ($LASTEXITCODE -ne 0) { throw 'worker.py --check failed; fix the config above and run this again.' }

# --- Scheduled Task --------------------------------------------------------------------------------
$user = "$env:USERDOMAIN\$env:USERNAME"
$action = New-ScheduledTaskAction -Execute $pyw -Argument "`"$Worker`"" -WorkingDirectory $Here
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew -StartWhenAvailable
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal `
    -Settings $settings -Description 'LEGION: Alfred talks to Claude Code on this laptop (tools/claude_session).' -Force | Out-Null
Write-Host "Registered '$TaskName' (at logon, runs through a lock, not elevated)."

if ($StartNow) {
    Start-ScheduledTask -TaskName $TaskName
    Write-Host 'Started. Check with .\tools\claude_session\status.ps1'
}

# --- Interactive sessions reporter hooks (ticket 06) ----------------------------------------------
# Merges SessionStart/UserPromptSubmit/Notification/Stop/SessionEnd hooks into Kevin's own
# ~/.claude/settings.json. Keeps his hooks, writes a .bak-<stamp> copy first, safe to re-run.
$UserSettings = Join-Path $env:USERPROFILE '.claude\settings.json'
$Hook = Join-Path $Here 'session_hook.py'
$addHooks = $SessionHooks
if (-not $addHooks -and -not $NoSessionHooks) {
    $ans = Read-Host "Report your own Claude Code sessions to LEGION (hooks in $UserSettings)? [y/N]"
    $addHooks = $ans -match '^(y|yes)$'
}
if ($addHooks) {
    $hookCmd = "`"$pyw`" `"$Hook`""
    & $py $Hook --install-settings $UserSettings $hookCmd
    if ($LASTEXITCODE -ne 0) { throw "Could not merge the session hooks into $UserSettings." }
}

Write-Host ''
Write-Host 'By hand, once: Control Panel > Power Options > Choose what closing the lid does >'
Write-Host '  Plugged in: Do nothing. (The worker keeps the PC awake on mains; it cannot change the lid.)'
