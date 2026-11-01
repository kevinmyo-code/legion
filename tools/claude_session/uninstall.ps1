<#
.SYNOPSIS
  Remove the LEGION Claude session worker from this laptop.

.DESCRIPTION
  Stops and unregisters the "LEGION Claude session" Scheduled Task and stops the main Claude
  session it started. With -Purge also deletes %APPDATA%\legion-claude (config with the device
  token and Alfred's app password, logs, task work folders). Revoke the device token from
  LEGION's devices list as well; deleting the file does not revoke it.
#>
[CmdletBinding()]
param([switch]$Purge)

$ErrorActionPreference = 'Stop'
$TaskName = 'LEGION Claude session'
$ConfigDir = Join-Path $env:APPDATA 'legion-claude'

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "Removed '$TaskName'."
} else {
    Write-Host "'$TaskName' was not registered."
}

$pidFile = Join-Path $ConfigDir 'session.pid'
if (Test-Path $pidFile) {
    $info = Get-Content $pidFile -Raw | ConvertFrom-Json
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$info.pid)" -ErrorAction SilentlyContinue
    # Pids are recycled: only stop the process whose command line carries the worker's session id,
    # never Kevin's own claude.
    if ($proc -and $info.session -and $proc.CommandLine -and $proc.CommandLine.Contains([string]$info.session)) {
        & taskkill /PID $info.pid /T /F | Out-Null
        Write-Host 'Stopped the main Claude session.'
    }
}

if ($Purge -and (Test-Path $ConfigDir)) {
    Remove-Item -Recurse -Force -Path $ConfigDir -Confirm:$false
    Write-Host "Deleted $ConfigDir. Revoke the device token in LEGION too."
}
