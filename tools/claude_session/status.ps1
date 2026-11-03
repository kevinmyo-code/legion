<#
.SYNOPSIS
  Show the LEGION Claude session worker's state on this laptop. Read only.

.DESCRIPTION
  The Scheduled Task's state and last result, the config (never the token or app password), the
  main session id and date, how many tasks are running, and the tail of the worker log (which
  holds ids and statuses only, never requests, summaries or paths).
#>
[CmdletBinding()]
param([int]$Lines = 20)

$TaskName = 'LEGION Claude session'
$ConfigDir = Join-Path $env:APPDATA 'legion-claude'

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) {
    $info = Get-ScheduledTaskInfo -TaskName $TaskName
    Write-Host "Task:        $($task.State), last run $($info.LastRunTime), last result $($info.LastTaskResult)"
} else {
    Write-Host 'Task:        not registered (run install.ps1)'
}

$cfgPath = Join-Path $ConfigDir 'config.json'
if (Test-Path $cfgPath) {
    $cfg = Get-Content $cfgPath -Raw | ConvertFrom-Json
    $mail = if ($cfg.mail) { 'on' } else { 'off' }
    Write-Host "Machine:     $($cfg.name), scope $($cfg.scope), engine $($cfg.engine_url), mail $mail"
} else {
    Write-Host "Config:      none at $cfgPath"
}

$statePath = Join-Path $ConfigDir 'state.json'
if (Test-Path $statePath) {
    $st = Get-Content $statePath -Raw | ConvertFrom-Json
    $tasks = @($st.tasks.PSObject.Properties | ForEach-Object { $_.Value })
    $running = @($tasks | Where-Object { $_.status -eq 'running' })
    Write-Host "Session:     $($st.session_id) ($($st.session_date))"
    Write-Host "Tasks:       $($running.Count) running, $($tasks.Count) kept, $(@($st.outbox).Count) results waiting for the engine"
}

$pidFile = Join-Path $ConfigDir 'session.pid'
if (Test-Path $pidFile) {
    $sessionPid = (Get-Content $pidFile -Raw | ConvertFrom-Json).pid
    $alive = [bool](Get-Process -Id $sessionPid -ErrorAction SilentlyContinue)
    Write-Host "Main session process $sessionPid running: $alive"
}

$log = Join-Path $ConfigDir 'logs\worker.log'
if (Test-Path $log) {
    Write-Host ''
    Get-Content $log -Tail $Lines
}
