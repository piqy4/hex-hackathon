#requires -Version 7.0
param(
    [string]$Adb = 'adb',
    [string]$Output = (Join-Path (Split-Path $PSScriptRoot -Parent) 'device-results'),
    [switch]$RequireOffline
)
$ErrorActionPreference = 'Stop'
if ((& $Adb get-state) -ne 'device') { throw 'Connect one unlocked Android device and authorize USB debugging.' }
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$airplane = (& $Adb shell settings get global airplane_mode_on).Trim()
$wifi = (& $Adb shell settings get global wifi_on).Trim()
if ($RequireOffline -and ($airplane -ne '1' -or $wifi -ne '0')) { throw 'Offline verification requested, but device is connected. This script never changes connectivity.' }
@{ airplane_mode = $airplane; wifi = $wifi } | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $Output 'network.json')
foreach ($case in @(
    @{ Name='test1'; Audio='test1.mp3'; Rumble='false' },
    @{ Name='test2'; Audio='test2.mp3'; Rumble='false' },
    @{ Name='test3'; Audio='test3.mp3'; Rumble='false' },
    @{ Name='test1-rumble'; Audio='test1.mp3'; Rumble='true' }
)) {
    Write-Output "Running $($case.Name)"
    $result = & $Adb shell am instrument -w -e class com.hex.stenographer.eval.IntegratedPipelineTest -e audio $case.Audio -e rumble $case.Rumble com.hex.stenographer.test/androidx.test.runner.AndroidJUnitRunner
    $result | Set-Content -Encoding utf8 (Join-Path $Output "$($case.Name)-instrumentation.txt")
    if (($result -join "`n") -notmatch 'OK \(1 test\)') { throw "Failed $($case.Name): $result" }
    $suffix = if ($case.Rumble -eq 'true') { 'rumble' } else { 'baseline' }
    foreach ($part in @('', '-evidence')) {
        $data = & $Adb exec-out run-as com.hex.stenographer cat "files/integrated-$suffix$part.json"
        if ($LASTEXITCODE -ne 0) { throw 'Could not read device result' }
        $null = ($data -join "`n") | ConvertFrom-Json
        $data | Set-Content -Encoding utf8 (Join-Path $Output "$($case.Name)$part.json")
    }
    $evidence = Get-Content (Join-Path $Output "$($case.Name)-evidence.json") -Raw | ConvertFrom-Json
    Write-Output "$($case.Name): duration=$($evidence.duration_ms) ms, speakers=$($evidence.speakers), RTF=$($evidence.rtf)"
}
