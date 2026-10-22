<#
.SYNOPSIS
  Redo a feed's saved login (Canvas, WebAssign, Drive) in one step.

.DESCRIPTION
  Why this exists: tools/connect_session.py never asks for a password, by design. You log in to
  the upstream site yourself in a real browser, and the script hands that session to the LEGION
  server. What it DOES need is a LEGION device token for an owner of the household, and getting
  one means logging in to LEGION itself. This helper does that part: it asks for your LEGION email
  and password, trades them for a device token at POST <server>/api/auth/login, hands the token to
  connect_session.py through $env:LEGION_TOKEN for that one run, then removes it.

  The password and token are never printed, logged, or written to disk.

  Nothing is hardcoded: the server and the school's Canvas address come from parameters or the
  LEGION_SERVER / LEGION_CANVAS_URL environment variables, so a clone works for any household.

.PARAMETER Source
  canvas (default), webassign or drive. Drive also needs the Google client variables that
  connect_session.py reads from the environment (LEGION_GOOGLE_CLIENT_ID, LEGION_GOOGLE_CLIENT_SECRET).

.PARAMETER Server
  The LEGION server's base URL. Defaults to $env:LEGION_SERVER.

.PARAMETER BaseUrl
  Canvas only: your Canvas address. Defaults to $env:LEGION_CANVAS_URL.

.EXAMPLE
  .\tools\relogin.ps1
  .\tools\relogin.ps1 -Source webassign
#>
[CmdletBinding()]
param(
    [ValidateSet('canvas', 'webassign', 'drive')]
    [string]$Source = 'canvas',
    [string]$Server = $env:LEGION_SERVER,
    [string]$BaseUrl = $env:LEGION_CANVAS_URL
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Server)) {
    throw 'No server. Pass -Server https://your-legion-host or set LEGION_SERVER.'
}
if ($Source -eq 'canvas' -and [string]::IsNullOrWhiteSpace($BaseUrl)) {
    throw 'Canvas needs your school''s Canvas address. Pass -BaseUrl https://canvas.school.edu or set LEGION_CANVAS_URL.'
}
$Server = $Server.TrimEnd('/')

$email = Read-Host 'LEGION email'
$secure = Read-Host 'LEGION password' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    $payload = @{ email = $email; password = $plain; device_name = "relogin-$env:COMPUTERNAME" } | ConvertTo-Json
    try {
        $reply = Invoke-RestMethod -Method Post -Uri "$Server/api/auth/login" -ContentType 'application/json' -Body $payload
    } catch {
        # Say what failed without echoing the request body, which holds the password.
        throw "Sign-in to $Server was refused or unreachable: $($_.Exception.Message)"
    }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    $plain = $null
    $payload = $null
}
if ([string]::IsNullOrWhiteSpace($reply.token)) {
    throw 'The server signed you in but sent no token.'
}

$env:LEGION_TOKEN = $reply.token
$reply = $null
try {
    $script = Join-Path $PSScriptRoot 'connect_session.py'
    $argList = @($script, $Source, '--server', $Server)
    if ($Source -eq 'canvas') { $argList += @('--base-url', $BaseUrl) }
    & python @argList
    if ($LASTEXITCODE -ne 0) { throw "connect_session.py exited with code $LASTEXITCODE." }
} finally {
    Remove-Item Env:\LEGION_TOKEN -ErrorAction SilentlyContinue
}
