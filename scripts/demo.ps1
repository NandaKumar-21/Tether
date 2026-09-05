<#
  Tether demo. One command, no improvisation.

      powershell -ExecutionPolicy Bypass -File scripts\demo.ps1

  Sequence:
    1. airplane mode on, wifi off, mobile data off
    2. prove there is no network at all
    3. cold start the app, wait for the model to load
    4. one OpenAI-compatible request, answered on the phone

  -SkipClear   keep the running process (skips the ~25s model load)
  -Prompt      override the demo question
#>

param(
    [switch]$SkipClear,
    [string]$Prompt = "What is a race condition? Two sentences."
)

# Deliberately NOT "Stop": adb writes to stderr on success, and the connectivity
# checks below are supposed to fail. PowerShell would treat both as fatal.
$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.tether.app"
$port = 8080

function Step($n, $text) {
    Write-Host ""
    Write-Host "  [$n] $text" -ForegroundColor Cyan
    Write-Host "  ---------------------------------------------------------------"
}
function Good($t) { Write-Host "      $t" -ForegroundColor Green }
function Info($t) { Write-Host "      $t" -ForegroundColor Gray }
function Bad($t)  { Write-Host "      $t" -ForegroundColor Red }

Clear-Host
Write-Host ""
Write-Host "   TETHER - your phone is the AI runtime" -ForegroundColor White
Write-Host "   Gemma 3 1B int4 . MediaPipe . GPU . OpenAI-compatible on :$port" -ForegroundColor DarkGray

# ---------------------------------------------------------------- device
Step 0 "Device"
$devices = & $adb devices | Select-String "device$"
if (-not $devices) { Bad "No device. Plug the phone in and unlock it."; exit 1 }
$model = (& $adb shell getprop ro.product.model).Trim()
$android = (& $adb shell getprop ro.build.version.release).Trim()
Good "$model  .  Android $android"

# ------------------------------------------------------------- go offline
Step 1 "Going offline"
& $adb shell cmd connectivity airplane-mode enable | Out-Null
& $adb shell svc wifi disable  | Out-Null
& $adb shell svc data disable  | Out-Null
Start-Sleep -Seconds 4
Good "airplane mode ON, wifi OFF, mobile data OFF"

# --------------------------------------------------------------- prove it
Step 2 "Proving there is no network"
$net = (& $adb shell dumpsys connectivity | Select-String "Active default network").ToString().Trim()
Info $net
$wifi = (& $adb shell dumpsys wifi | Select-String "Wi-Fi is" | Select-Object -First 1).ToString().Trim()
Info $wifi
# Redirect on the device, not on Windows, so adb never writes to stderr here.
$ping = & $adb shell "ping -c 1 -W 2 8.8.8.8 2>&1" | Out-String
if ($ping -match "unreachable|100% packet loss|unknown host") {
    Good "ping 8.8.8.8 -> Network is unreachable"
} else {
    Bad "WARNING: the phone still has a route to the internet."
    Info $ping.Trim()
}
$dns = & $adb shell "ping -c 1 -W 2 huggingface.co 2>&1" | Out-String
if ($dns -match "unknown host|unreachable") { Good "DNS dead: huggingface.co does not resolve" }

# ------------------------------------------------------------- cold start
if (-not $SkipClear) {
    Step 3 "Cold start (wiping app state so nothing is cached)"
    & $adb shell pm clear $pkg | Out-Null
    # pm clear also wipes runtime grants. Re-grant all three or SCAN and MIC
    # will throw a permission dialog on stage.
    & $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS | Out-Null
    & $adb shell pm grant $pkg android.permission.CAMERA | Out-Null
    & $adb shell pm grant $pkg android.permission.RECORD_AUDIO | Out-Null
} else {
    Step 3 "Reusing the running process (-SkipClear)"
}

& $adb shell am start -n "$pkg/.MainActivity" | Out-Null
& $adb forward "tcp:$port" "tcp:$port" | Out-Null
Info "port $port forwarded from laptop to phone over USB"

Write-Host -NoNewline "      loading 529 MB model onto the GPU "
$loaded = $false
$sw = [System.Diagnostics.Stopwatch]::StartNew()
for ($i = 0; $i -lt 90; $i++) {
    try {
        $h = Invoke-RestMethod "http://127.0.0.1:$port/health" -TimeoutSec 3
        if ($h.model_loaded) { $loaded = $true; break }
    } catch { }
    Write-Host -NoNewline "." -ForegroundColor DarkGray
    Start-Sleep -Seconds 1
}
Write-Host ""
if (-not $loaded) { Bad "Model did not load. Check: adb logcat -s TetherLLM"; exit 1 }
Good ("model ready in {0:N0}s  .  backend {1}" -f $sw.Elapsed.TotalSeconds, $h.backend)

# ---------------------------------------------------------------- the ask
Step 4 "One OpenAI-compatible request, answered on the phone"
Write-Host ""
Write-Host "      POST http://127.0.0.1:$port/v1/chat/completions" -ForegroundColor Yellow
Write-Host "      $Prompt" -ForegroundColor White
Write-Host ""

$body = @{ model = "gemma-3-1b-it-int4"; messages = @(@{ role = "user"; content = $Prompt }) } |
        ConvertTo-Json -Depth 5 -Compress

$sw = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $resp = Invoke-RestMethod "http://127.0.0.1:$port/v1/chat/completions" `
            -Method Post -ContentType "application/json" -Body $body -TimeoutSec 240
} catch {
    Bad "Request failed: $_"; exit 1
}
$elapsed = $sw.Elapsed.TotalSeconds

Write-Host "      " -NoNewline
Write-Host $resp.choices[0].message.content -ForegroundColor Green
Write-Host ""

$h = Invoke-RestMethod "http://127.0.0.1:$port/health" -TimeoutSec 5
Info ("{0:N1}s  .  {1:N1} tok/s  .  {2} prompt + {3} completion tokens" -f `
      $elapsed, $h.tokens_per_sec, $resp.usage.prompt_tokens, $resp.usage.completion_tokens)
Info "model: $($resp.model)   object: $($resp.object)   finish: $($resp.choices[0].finish_reason)"

Write-Host ""
Write-Host "  ---------------------------------------------------------------"
Write-Host "   No network. No cloud. That answer was generated on the phone." -ForegroundColor White
Write-Host ""
