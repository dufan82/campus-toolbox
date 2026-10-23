param([ValidateSet('build','test','check','install')][string]$Task = 'build')
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$gradleArgs = @($args)
if (@($gradleArgs | Where-Object { $_ -match '[\s"&|<>^%!()]' }).Count) {
    throw 'Complex arguments with whitespace, double quotes or shell characters are not supported; use gradle.properties.'
}
$wrapperName = if ([Environment]::OSVersion.Platform -eq 'Win32NT') { 'gradlew.bat' } else { 'gradlew' }
$gradleCommand = Join-Path $PSScriptRoot $wrapperName
if (!(Test-Path -LiteralPath $gradleCommand)) { throw "Missing Gradle Wrapper: $wrapperName" }
Push-Location $PSScriptRoot
try {
    $gradleTasks = @(switch ($Task) {
        'test' { @(':watermark-core:test', ':app:testDebugUnitTest') }
        'check' { @(':watermark-core:test', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug') }
        'install' { @(':app:installDebug') }
        default { @(':app:assembleDebug') }
    })
    & $gradleCommand @gradleTasks --console=plain @GradleArgs
    $buildExitCode = $LASTEXITCODE
} finally { Pop-Location }
exit $buildExitCode
