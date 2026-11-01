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
    $sessionPid = (Get-Content $pidFile -Raw | ConvertFrom-Json).pid
    $proc = Get-Process -Id $sessionPid -ErrorAction SilentlyContinue
    if ($proc -and $proc.ProcessName -in @('claude', 'node')) {
        & taskkill /PID $sessionPid /T /F | Out-Null
        Write-Host 'Stopped the main Claude session.'
    }
}

if ($Purge -and (Test-Path $ConfigDir)) {
    Remove-Item -Recurse -Force -Path $ConfigDir -Confirm:$false
    Write-Host "Deleted $ConfigDir. Revoke the device token in LEGION too."
}
