$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectRoot = Split-Path $PSScriptRoot -Parent
$fixtureRoot = Join-Path $projectRoot ('verification/build-script-tests/' + [guid]::NewGuid().ToString('N'))
$fixture = Join-Path $fixtureRoot 'project with spaces'
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'build.ps1') -Destination $fixture
$isWindowsHost = [Environment]::OSVersion.Platform -eq 'Win32NT'
$hostBinary = (Get-Process -Id $PID).Path
$probe = @'
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
$result = @{ Arguments=$Arguments; Directory=(Get-Location).Path }
foreach ($key in @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','GRADLE_USER_HOME','TEMP','TMP')) {
    $result[$key] = [Environment]::GetEnvironmentVariable($key)
}
$result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $env:BUILD_PROBE_OUT
exit ([int]$env:BUILD_PROBE_EXIT)
'@
[IO.File]::WriteAllText((Join-Path $fixture 'probe.ps1'), $probe)
$caller = @'
param([string]$CasePath)
$caseData = Get-Content -LiteralPath $CasePath -Raw | ConvertFrom-Json
$PSNativeCommandUseErrorActionPreference = [bool]$caseData.NativeErrors
$callerDirectory = (Get-Location).Path
$extraArguments = @($caseData.Extra)
& (Join-Path $PSScriptRoot 'build.ps1') -Task $caseData.Task @extraArguments
$callerExitCode = $LASTEXITCODE
if ((Get-Location).Path -ne $callerDirectory) { throw 'The caller directory was not restored' }
[IO.File]::WriteAllText(($env:BUILD_PROBE_OUT + '.returned'), 'restored')
exit $callerExitCode
'@
[IO.File]::WriteAllText((Join-Path $fixture 'caller.ps1'), $caller)
if ($isWindowsHost) {
    $wrapper = '@"%BUILD_PROBE_HOST%" -NoProfile -File "%~dp0probe.ps1" %*' + "`r`n@exit /b %ERRORLEVEL%`r`n"
    [IO.File]::WriteAllText((Join-Path $fixture 'gradlew.bat'), $wrapper)
} else {
    $wrapper = @'
#!/bin/sh
"$BUILD_PROBE_HOST" -NoProfile -File "$(dirname "$0")/probe.ps1" "$@"
exit $?
'@
    [IO.File]::WriteAllText((Join-Path $fixture 'gradlew'), $wrapper + "`n")
    & chmod +x (Join-Path $fixture 'gradlew')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot make test wrapper executable' }
}
$keys = @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','GRADLE_USER_HOME','TEMP','TMP','BUILD_PROBE_HOST','BUILD_PROBE_OUT','BUILD_PROBE_EXIT')
$savedEnvironment = @{}
foreach ($key in $keys) { $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key) }
$failures = @()
$cases = @(
    @{ Task='build'; Expected=@(':app:assembleDebug'); Extra=@(); Exit=0 },
    @{ Task='test'; Expected=@(':watermark-core:test',':app:testDebugUnitTest'); Extra=@(); Exit=0 },
    @{ Task='check'; Expected=@(':watermark-core:test',':app:testDebugUnitTest',':app:lintDebug',':app:assembleDebug'); Extra=@(); Exit=0 },
    @{ Task='install'; Expected=@(':app:installDebug'); Extra=@(); Exit=0 },
    @{ Task='build'; Expected=@(':app:assembleDebug'); Extra=@('--offline','-PuseChinaMirrors=true'); Exit=0 },
    @{ Task='build'; Expected=@(':app:assembleDebug'); Extra=@(); Exit=7 },
    @{ Task='build'; Expected=@(); Extra=@('-Pmessage=hello world'); Exit=1; RejectComplex=$true },
    @{ Task='build'; Expected=@(':app:assembleDebug'); Extra=@(); Exit=7; NativeErrors=$true },
    @{ Task='build'; Expected=@(); Extra=@('-Pjson={"a":1}'); Exit=1; RejectComplex=$true },
    @{ Task='build'; Expected=@(); Extra=@('-Pmessage=a&b'); Exit=1; RejectComplex=$true }
)
try {
    foreach ($key in @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','GRADLE_USER_HOME','TEMP','TMP')) {
        [Environment]::SetEnvironmentVariable($key, (Join-Path $fixtureRoot "configured $key"))
    }
    $env:BUILD_PROBE_HOST = $hostBinary
    $index = 0
    foreach ($case in $cases) {
        $index++
        $env:BUILD_PROBE_OUT = Join-Path $fixtureRoot "result-$index.json"
        $env:BUILD_PROBE_EXIT = [string]$case.Exit
        $casePath = Join-Path $fixtureRoot "case-$index.json"
        [IO.File]::WriteAllText($casePath, ($case | ConvertTo-Json -Depth 4))
        $invokeArgs = @('-NoProfile','-File',(Join-Path $fixture 'caller.ps1'),'-CasePath',$casePath)
        try {
            $ErrorActionPreference = 'Continue'
            $output = & $hostBinary @invokeArgs 2>&1
            $exitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = 'Stop' }
        try {
            if ($case.RejectComplex) {
                if ($exitCode -eq 0 -or (Test-Path -LiteralPath $env:BUILD_PROBE_OUT)) { throw 'Complex argument was silently forwarded instead of rejected' }
                if (($output -join ' ') -notmatch 'use gradle.properties') { throw 'Unsupported argument did not get a clear explanation' }
                Write-Output "PASS case $index (unsupported complex argument rejected)"
                continue
            }
            if (!(Test-Path -LiteralPath $env:BUILD_PROBE_OUT)) { throw "The project wrapper was not invoked: $($output -join ' ')" }
            $actual = Get-Content -LiteralPath $env:BUILD_PROBE_OUT -Raw | ConvertFrom-Json
            if ($exitCode -ne $case.Exit) { throw "Expected exit $($case.Exit), got $exitCode" }
            $expectedArguments = @($case.Expected) + @('--console=plain') + @($case.Extra)
            if (($actual.Arguments -join '|') -cne ($expectedArguments -join '|')) { throw 'Wrong Gradle tasks or forwarded arguments' }
            if ($actual.Directory -ne $fixture) { throw 'Gradle did not run in the project root' }
            if (!(Test-Path -LiteralPath ($env:BUILD_PROBE_OUT + '.returned'))) { throw 'The caller did not resume after the build script' }
            foreach ($key in @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','GRADLE_USER_HOME','TEMP','TMP')) {
                if ($actual.$key -ne (Join-Path $fixtureRoot "configured $key")) { throw "The configured $key was overwritten" }
            }
            Write-Output "PASS case $index ($($case.Task), exit $($case.Exit))"
        } catch {
            $failures += "case $index : $($_.Exception.Message)"
            Write-Output "FAIL $($failures[-1])"
        }
    }
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key]) }
}
if ($failures.Count) { throw "$($failures.Count) of $($cases.Count) build-script checks failed" }
Write-Output "PASS all $($cases.Count) build-script checks"
exit 0
