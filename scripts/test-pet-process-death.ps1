param(
    [Parameter(Mandatory)][string]$Serial,
    [Parameter(Mandatory)][string]$Adb
)
$ErrorActionPreference = 'Stop'
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
$repository = Split-Path -Parent $PSScriptRoot
$run = [guid]::NewGuid().ToString()
$package = 'io.deepseekharness.mobile'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$suite = "$package.companion.PetDisruptionDeviceTest"
$logDir = Join-Path $repository 'build'
[void][IO.Directory]::CreateDirectory($logDir)
$job = Start-Job -ArgumentList $adbPath,$Serial,$suite,$run,$runner -ScriptBlock {
    param($binary,$device,$class,$token,$target)
    & $binary -s $device shell am instrument -w -r -e petPhase prepare -e petRun $token -e class "$class#prepareDuringMissingReceipt" $target 2>&1
}
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(85)
    do {
        & $adbPath -s $Serial shell run-as $package test -f "cache/pet-kill-$run/ready"
        if ($LASTEXITCODE -eq 0) { break }
        if ($job.State -ne 'Running') { throw "准备失败。$(Receive-Job $job -Keep | Out-String)" }
        if ([DateTime]::UtcNow -gt $deadline) { throw '准备超时，未发送强杀指令。' }
        Start-Sleep -Milliseconds 500
    } while ($true)
    & $adbPath -s $Serial shell am force-stop $package
    if ($LASTEXITCODE -ne 0) { throw '无法终止测试进程' }
    $ended = Wait-Job $job -Timeout 15
    if (!$ended) { throw '准备进程未按期退出' }
    Receive-Job $job | Set-Content -LiteralPath (Join-Path $logDir 'pet-kill-prepare.log')
    $result = & $adbPath -s $Serial shell am instrument -w -r -e petPhase recover -e petRun $run -e class "$suite#recoverAfterKill" $runner
    $result | Set-Content -LiteralPath (Join-Path $logDir 'pet-kill-recover.log')
    if (($result -join "`n") -notmatch 'OK \(1 test\)') { throw ($result -join "`n") }
    $result | Select-String 'killedPid|recoveredPid|executions=|OK \('
} finally {
    Stop-Job $job
    Remove-Job $job
    & $adbPath -s $Serial shell am start -n "$package/.MainActivity" | Out-Null
}
