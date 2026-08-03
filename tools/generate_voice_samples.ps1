# Generates the bundled voice-audition clips for the companion voice picker
# (settings/companions). Ported from MIDNIGHT_AI 2026-08-02.
#
# res/raw/ came across EMPTY in the port, so all 30 curated voices were silent
# in the picker - you could read "Vindemiatrix - Gentle" but never hear it,
# which is useless for actually choosing a companion's voice.
#
# This machine's execution policy refuses unsigned script FILES even with
# -ExecutionPolicy Bypass, so run it through Invoke-Expression:
#     Invoke-Expression (Get-Content -Raw tools/generate_voice_samples.ps1)
# GEMINI_API_KEY comes from the env var or local.properties (gitignored, so a
# key placed there is never committed). Rebuild afterwards to bundle.
# Run once from the project (re-run any time to refresh):
#     .\tools\generate_voice_samples.ps1
# Reads GEMINI_API_KEY from the env var or local.properties, calls Gemini TTS for
# each curated voice, and writes WAVs to app/src/main/res/raw/voice_sample_<name>.wav.
# Rebuild the app afterwards so the clips are bundled. If your key only has access
# to a different TTS model, pass -Model (e.g. gemini-3.1-flash-preview-tts).
param(
    [string]$Model = "gemini-2.5-flash-preview-tts",
    # Neutral on purpose: one line serves 30 voices and both registers, so it
    # must not sound like Alfred OR Dorothy - and it must not sound like a car
    # app, since that product ended in the 2026-07-31 pivot.
    [string]$Line  = "Good evening. Everything is in order, and I am ready when you are."
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# (Get-Location), not $PSScriptRoot - the latter is empty when the script is
# piped through Invoke-Expression, which is the only way it runs on this machine.
$root = (Get-Location).Path
$localProps = Join-Path $root "local.properties"
$rawDir = Join-Path $root "app\src\main\res\raw"

# --- API key (env var wins, else local.properties) ---
$key = $env:GEMINI_API_KEY
if (-not $key -and (Test-Path $localProps)) {
    $kv = Get-Content $localProps | Where-Object { $_ -match '^\s*GEMINI_API_KEY\s*=' } | Select-Object -First 1
    if ($kv) { $key = ($kv -split '=', 2)[1].Trim() }
}
if (-not $key) { throw "GEMINI_API_KEY not found (set the env var or add it to local.properties)." }

# Must stay in sync with CURATED_VOICES in app/.../ai/Voices.kt (widened 2026-07-22
# to the full ~30 prebuilt voices).
$voices = @(
    "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede",
    "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
    "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
    "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
    "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
)

New-Item -ItemType Directory -Force -Path $rawDir | Out-Null
$uri = "https://generativelanguage.googleapis.com/v1beta/models/${Model}:generateContent"

# Wraps raw 16-bit mono PCM (Gemini TTS returns 24 kHz L16) in a WAV container.
function Write-Wav([byte[]]$pcm, [string]$path, [int]$rate = 24000) {
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([System.Text.Encoding]::ASCII.GetBytes("RIFF"))
    $bw.Write([int](36 + $pcm.Length))
    $bw.Write([System.Text.Encoding]::ASCII.GetBytes("WAVE"))
    $bw.Write([System.Text.Encoding]::ASCII.GetBytes("fmt "))
    $bw.Write([int]16)            # fmt chunk size
    $bw.Write([int16]1)           # audio format = PCM
    $bw.Write([int16]1)           # channels = mono
    $bw.Write([int]$rate)         # sample rate
    $bw.Write([int]($rate * 2))   # byte rate = rate * channels * bytesPerSample
    $bw.Write([int16]2)           # block align
    $bw.Write([int16]16)          # bits per sample
    $bw.Write([System.Text.Encoding]::ASCII.GetBytes("data"))
    $bw.Write([int]$pcm.Length)
    $bw.Write($pcm)
    $bw.Flush()
    [System.IO.File]::WriteAllBytes($path, $ms.ToArray())
    $bw.Dispose()
}

# Free-tier TTS rate-limits hard: a straight 30-call loop died with HTTP 429 on
# the 13th voice (2026-08-02). So: skip clips that already exist (making a
# re-run a resume rather than a redo, and never paying twice for the same
# voice), pace the calls, and back off on 429 instead of losing the run.
$pauseSeconds = 6
$maxAttempts = 5

foreach ($voice in $voices) {
    $out = Join-Path $rawDir ("voice_sample_" + $voice.ToLower() + ".wav")
    if (Test-Path $out) { Write-Host "Skipping $voice (already generated)"; continue }
    Write-Host "Generating $voice ..."
    $body = @"
{
  "contents": [{ "parts": [{ "text": "$Line" }] }],
  "generationConfig": {
    "responseModalities": ["AUDIO"],
    "speechConfig": { "voiceConfig": { "prebuiltVoiceConfig": { "voiceName": "$voice" } } }
  }
}
"@
    $resp = $null
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        try {
            $resp = Invoke-RestMethod -Method Post -Uri $uri -Headers @{ "x-goog-api-key" = $key } -ContentType "application/json" -Body $body
            break
        } catch {
            $code = 0
            if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
            if ($code -eq 429 -and $attempt -lt $maxAttempts) {
                $wait = [math]::Pow(2, $attempt) * 15
                Write-Host "  rate limited, waiting $wait s (attempt $attempt/$maxAttempts)"
                Start-Sleep -Seconds $wait
                continue
            }
            Write-Warning "  $voice failed (HTTP $code) - re-run to retry just this one."
            break
        }
    }
    if (-not $resp) { continue }
    $b64 = $resp.candidates[0].content.parts[0].inlineData.data
    if (-not $b64) { Write-Warning "No audio returned for $voice; skipping."; continue }
    $pcm = [System.Convert]::FromBase64String($b64)
    Write-Wav $pcm $out
    Write-Host "  -> $out"
    Start-Sleep -Seconds $pauseSeconds
}

Write-Host "Done. Rebuild the app to bundle the new clips."
