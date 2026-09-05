<#
  On-stage recovery. Run this if the model returns an empty answer.

      powershell -ExecutionPolicy Bypass -File scripts\fix.ps1

  Why: MediaPipe caches a compiled ~500 MB GPU artifact in the app's cache
  directory. If the process dies mid-generation that cache is left corrupt,
  and every later reply comes back empty with no error. pm clear wipes it.
  Takes about 25 seconds.
#>

$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.tether.app"

Write-Host "clearing corrupt GPU cache..." -ForegroundColor Yellow
& $adb shell pm clear $pkg | Out-Null
& $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS | Out-Null
& $adb shell pm grant $pkg android.permission.CAMERA | Out-Null
& $adb shell pm grant $pkg android.permission.RECORD_AUDIO | Out-Null
& $adb shell am start -n "$pkg/.MainActivity" | Out-Null
& $adb forward tcp:8080 tcp:8080 | Out-Null

Write-Host -NoNewline "reloading model "
for ($i = 0; $i -lt 90; $i++) {
    try {
        $h = Invoke-RestMethod "http://127.0.0.1:8080/health" -TimeoutSec 3
        if ($h.model_loaded) {
            Write-Host ""
            Write-Host "READY - $($h.backend)" -ForegroundColor Green
            exit 0
        }
    } catch { }
    Write-Host -NoNewline "." -ForegroundColor DarkGray
    Start-Sleep -Seconds 1
}
Write-Host ""
Write-Host "FAILED - check: adb logcat -s TetherLLM" -ForegroundColor Red
