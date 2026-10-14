# Generates the bundled voice-audition clips for the onboarding voice picker.
# Run once from the project (re-run any time to refresh):
#     .\tools\generate_voice_samples.ps1
# Reads GEMINI_API_KEY from the env var or local.properties, calls Gemini TTS for
# each curated voice, and writes WAVs to app/src/main/res/raw/voice_sample_<name>.wav.
# Rebuild the app afterwards so the clips are bundled. If your key only has access
# to a different TTS model, pass -Model (e.g. gemini-3.1-flash-preview-tts).
param(
    [string]$Model = "gemini-2.5-flash-preview-tts",
    [string]$Line  = "Hey there, I'm your co-pilot. Let's hit the road."
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root = Split-Path -Parent $PSScriptRoot
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

foreach ($voice in $voices) {
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
    $resp = Invoke-RestMethod -Method Post -Uri $uri -Headers @{ "x-goog-api-key" = $key } -ContentType "application/json" -Body $body
    $b64 = $resp.candidates[0].content.parts[0].inlineData.data
    if (-not $b64) { Write-Warning "No audio returned for $voice; skipping."; continue }
    $pcm = [System.Convert]::FromBase64String($b64)
    $out = Join-Path $rawDir ("voice_sample_" + $voice.ToLower() + ".wav")
    Write-Wav $pcm $out
    Write-Host "  -> $out"
}

Write-Host "Done. Rebuild the app to bundle the new clips."
