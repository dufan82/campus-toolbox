[CmdletBinding()]
param(
    [string]$DebugApkPath,
    [string]$UnsignedApkPath,
    [string]$SdkPath,
    [string]$BuildToolsPath,
    [string]$JavaHome,
    [string]$TempRoot = [IO.Path]::GetTempPath()
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectRoot = Split-Path $PSScriptRoot -Parent
$preparer = Join-Path $PSScriptRoot 'Prepare-Release.ps1'
if (!$DebugApkPath) { $DebugApkPath = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk' }
if (!$UnsignedApkPath) { $UnsignedApkPath = Join-Path $projectRoot 'app/build/outputs/apk/release/app-release-unsigned.apk' }
foreach ($apk in @($DebugApkPath, $UnsignedApkPath)) {
    if (!(Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'Existing debug and unsigned APK fixtures are required; this test never builds them.' }
}
$resolvedTemp = (Resolve-Path -LiteralPath $TempRoot).ProviderPath
if ($resolvedTemp -match '[^\x20-\x7e]') { throw 'Choose an existing ASCII -TempRoot for the real aapt tests.' }
$fixtureRoot = Join-Path $resolvedTemp ('campus-release-tests-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($fixtureRoot) | Out-Null
$unicodeName = [string][char]0x4e2d + [char]0x6587
$fixture = Join-Path $fixtureRoot ($unicodeName + ' paths with spaces')
[IO.Directory]::CreateDirectory($fixture) | Out-Null
$common = @{
    ApkPath = $DebugApkPath; OutputDirectory = (Join-Path $fixture 'real output')
    ExpectedSignerSha256 = ('a' * 64); PreviousVersionCode = '0'; TempRoot = $fixtureRoot
}
foreach ($key in @('SdkPath','BuildToolsPath','JavaHome')) {
    if (Get-Variable -Name $key -ValueOnly) { $common[$key] = Get-Variable -Name $key -ValueOnly }
}
$script:passed = 0
$script:failures = @()
function Assert-True([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Test-Case([string]$CaseName, [scriptblock]$Action) {
    try { & $Action; $script:passed++; Write-Output "PASS $CaseName" }
    catch { $script:failures += "$CaseName : $($_.Exception.Message)"; Write-Output "FAIL $($script:failures[-1])" }
}
function Assert-Rejected([hashtable]$Parameters, [string]$Reason, [switch]$Boundary) {
    $message = ''
    try {
        if ($Boundary) { Invoke-ReleasePreparation @Parameters | Out-Null }
        else { & $preparer @Parameters | Out-Null }
    } catch { $message = $_.Exception.Message }
    Assert-True ($message -match $Reason) "Expected rejection '$Reason'; got '$message'"
    if ($Parameters.OutputDirectory) { Assert-True (!(Test-Path -LiteralPath $Parameters.OutputDirectory)) 'A rejected input created output.' }
}
$environmentKeys = @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','GRADLE_USER_HOME','TEMP','TMP')
$savedEnvironment = @{}
foreach ($key in $environmentKeys) { $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key) }
$callerDirectory = (Get-Location).Path
try {
    Test-Case 'real Android Debug certificate is rejected even with a valid signature' {
        Assert-Rejected $common 'Android Debug certificate'
    }
    Test-Case 'real Android Debug certificate is rejected even when explicitly pinned' {
        $parameters = $common.Clone(); $parameters.ExpectedSignerSha256 = 'f6b9669399e0f791f16542dff896474f803408645e84a07650bdcd98e0b83eb1'
        Assert-Rejected $parameters 'Android Debug certificate'
    }
    Test-Case 'real unsigned release is rejected' {
        $parameters = $common.Clone(); $parameters.ApkPath = $UnsignedApkPath
        Assert-Rejected $parameters 'signature verification failed'
    }
    Test-Case 'real debug APK in a Chinese path is rejected after ASCII staging' {
        $path = Join-Path $fixture ($unicodeName + ' signed app.apk')
        [IO.File]::Copy((Get-Item -LiteralPath $DebugApkPath).FullName, $path)
        $parameters = $common.Clone(); $parameters.ApkPath = $path
        Assert-Rejected $parameters 'Android Debug certificate'
    }
    $badArguments = @(
        @{ Key='ExpectedSignerSha256'; Value='abc'; Reason='ExpectedSignerSha256' },
        @{ Key='ExpectedSignerSha256'; Value=('aa:' * 31 + 'aa'); Reason='ExpectedSignerSha256' },
        @{ Key='PreviousVersionCode'; Value='1.5'; Reason='PreviousVersionCode' },
        @{ Key='PreviousVersionCode'; Value='-1'; Reason='PreviousVersionCode' },
        @{ Key='PreviousVersionCode'; Value='1e2'; Reason='PreviousVersionCode' },
        @{ Key='PreviousVersionCode'; Value='2100000001'; Reason='PreviousVersionCode' },
        @{ Key='ApkPath'; Value=(Join-Path $fixture 'missing.apk'); Reason='APK file' },
        @{ Key='OutputDirectory'; Value=''; Reason='OutputDirectory' },
        @{ Key='SdkPath'; Value=(Join-Path $fixture 'missing-sdk'); Reason='SDK|Build Tools' },
        @{ Key='JavaHome'; Value=(Join-Path $fixture 'missing-jdk'); Reason='Java|JDK' }
    )
    foreach ($case in $badArguments) {
        Test-Case ("bad argument: " + $case.Key + ' = ' + $case.Value) {
            $parameters = $common.Clone(); $parameters[$case.Key] = $case.Value
            if ($case.Key -eq 'SdkPath') { $parameters.Remove('BuildToolsPath') }
            Assert-Rejected $parameters $case.Reason
        }
    }
    if (!(Test-Path -LiteralPath $preparer -PathType Leaf)) { throw 'Prepare-Release.ps1 has not been implemented (expected red stage).' }

    # Replace only the native command boundary. Parsing, checks, staging, hashing,
    # output writes and cleanup below are the real preparer. No keys or APKs are generated.
    . $preparer
    $syntheticApk = Join-Path $fixture 'synthetic input.apk'
    [IO.File]::WriteAllText($syntheticApk, 'External-command fixture only. This is not an installable APK.', [Text.Encoding]::ASCII)
    $syntheticHash = (Get-FileHash -LiteralPath $syntheticApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $syntheticSize = (Get-Item -LiteralPath $syntheticApk).Length
    $syntheticTools = Join-Path $fixture 'fake sdk/build-tools/35.0.0'
    $syntheticJdk = Join-Path $fixture 'fake jdk'
    [IO.Directory]::CreateDirectory((Join-Path $syntheticTools 'lib')) | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $syntheticJdk 'bin')) | Out-Null
    $windowsHost = [Environment]::OSVersion.Platform -eq 'Win32NT'
    $aaptName = if ($windowsHost) { 'aapt.exe' } else { 'aapt' }
    $javaName = if ($windowsHost) { 'java.exe' } else { 'java' }
    foreach ($path in @((Join-Path $syntheticTools $aaptName), (Join-Path $syntheticTools 'lib/apksigner.jar'), (Join-Path $syntheticJdk ('bin/' + $javaName)))) {
        [IO.File]::WriteAllText($path, 'Not executable; intercepted external-command fixture.')
    }
    $base = @{
        ApkPath=$syntheticApk; OutputDirectory=(Join-Path $fixture 'synthetic output')
        ExpectedSignerSha256=('a' * 64); PreviousVersionCode='17'; TempRoot=$fixtureRoot
        BuildToolsPath=$syntheticTools; JavaHome=$syntheticJdk
    }
    $badging = "package: name='cn.campuslife.assistant' versionCode='18' versionName='1.7.1' platformBuildVersionName='16'`nsdkVersion:'26'`ntargetSdkVersion:'35'`napplication-label:'Campus Toolbox'"
    $certificates = "Verifies`nVerified using v1 scheme (JAR signing): false`nVerified using v2 scheme (APK Signature Scheme v2): true`nNumber of signers: 1`nSigner #1 certificate DN: CN=Campus Toolbox, O=Example`nSigner #1 certificate SHA-256 digest: $('a' * 64)`nSigner #1 public key SHA-256 digest: $('c' * 64)"
    $script:releaseToolFixture = @{ Badging=$badging; Certificates=$certificates; AaptExit=0; JavaExit=0 }
    function Invoke-ReleaseTool([string]$Command, [string[]]$Arguments) {
        $snapshot = $Arguments[-1]
        Assert-True ($snapshot -notmatch '[^\x20-\x7e]') 'Native tools received a non-ASCII APK path.'
        Assert-True ($snapshot -ne $syntheticApk -and (Test-Path -LiteralPath $snapshot -PathType Leaf)) 'Native tools did not receive a private APK snapshot.'
        Assert-True ((Get-FileHash -LiteralPath $snapshot -Algorithm SHA256).Hash.ToLowerInvariant() -eq $syntheticHash) 'Staging changed the input bytes.'
        if ([IO.Path]::GetFileName($Command) -eq $aaptName) {
            Assert-True (($Arguments[0..1] -join '|') -eq 'dump|badging' -and $Arguments.Count -eq 3) 'Unexpected aapt arguments.'
            Assert-True ($Command -eq (Join-Path $syntheticTools $aaptName)) 'Configured Build Tools were ignored.'
            return @{ ExitCode=$script:releaseToolFixture.AaptExit; Text=$script:releaseToolFixture.Badging }
        }
        Assert-True ($Command -eq (Join-Path $syntheticJdk ('bin/' + $javaName))) 'Configured JDK was ignored.'
        Assert-True ($Arguments.Count -eq 6 -and $Arguments[0] -eq '-jar' -and $Arguments[1] -eq (Join-Path $syntheticTools 'lib/apksigner.jar') -and ($Arguments[2..4] -join '|') -eq 'verify|--verbose|--print-certs') 'Unexpected signature verification arguments.'
        return @{ ExitCode=$script:releaseToolFixture.JavaExit; Text=$script:releaseToolFixture.Certificates }
    }
    Test-Case 'synthetic tool outputs produce the exact protocol and unchanged APK bytes' {
        Invoke-ReleasePreparation @base | Out-Null
        $names = @((Get-ChildItem -LiteralPath $base.OutputDirectory).Name | Sort-Object)
        Assert-True (($names -join '|') -eq 'campus-toolbox-1.7.1.apk|SHA256SUMS.txt|update.json') 'Unexpected output files.'
        $metadataPath = Join-Path $base.OutputDirectory 'update.json'
        $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
        Assert-True (($metadata.PSObject.Properties.Name -join '|') -ceq 'schemaVersion|packageName|channel|versionCode|versionName|tag|minSdk|assetName|size|sha256') 'Wrong JSON schema.'
        Assert-True ($metadata.schemaVersion -eq 1 -and $metadata.packageName -ceq 'cn.campuslife.assistant' -and $metadata.channel -ceq 'stable') 'Wrong protocol identity.'
        Assert-True ($metadata.versionCode -eq 18 -and $metadata.versionCode -is [ValueType] -and $metadata.versionName -ceq '1.7.1' -and $metadata.tag -ceq 'v1.7.1' -and $metadata.minSdk -eq 26) 'Wrong version fields or numeric types.'
        Assert-True ($metadata.assetName -ceq 'campus-toolbox-1.7.1.apk' -and $metadata.size -eq $syntheticSize -and $metadata.sha256 -ceq $syntheticHash) 'Wrong APK metadata.'
        Assert-True ((Get-FileHash -LiteralPath (Join-Path $base.OutputDirectory $metadata.assetName)).Hash.ToLowerInvariant() -ceq $syntheticHash) 'Copied APK differs.'
        $jsonHash = (Get-FileHash -LiteralPath $metadataPath).Hash.ToLowerInvariant()
        $sums = [IO.File]::ReadAllLines((Join-Path $base.OutputDirectory 'SHA256SUMS.txt'))
        Assert-True ($sums.Count -eq 2 -and $sums[0] -ceq "$syntheticHash  campus-toolbox-1.7.1.apk" -and $sums[1] -ceq "$jsonHash  update.json") 'Wrong checksum file.'
    }
    Test-Case 'identical rerun is idempotent and does not rewrite files' {
        $times = @{}; foreach ($file in (Get-ChildItem -LiteralPath $base.OutputDirectory)) { $times[$file.Name] = $file.LastWriteTimeUtc.Ticks }
        Invoke-ReleasePreparation @base | Out-Null
        foreach ($file in (Get-ChildItem -LiteralPath $base.OutputDirectory)) { Assert-True ($file.LastWriteTimeUtc.Ticks -eq $times[$file.Name]) 'An identical file was rewritten.' }
    }
    Test-Case 'relative output uses the PowerShell caller directory' {
        $processDirectory = [Environment]::CurrentDirectory
        [Environment]::CurrentDirectory = $fixtureRoot
        Push-Location $fixture
        try {
            $parameters = $base.Clone(); $parameters.OutputDirectory = 'relative output'
            Invoke-ReleasePreparation @parameters | Out-Null
            Assert-True (Test-Path -LiteralPath (Join-Path $fixture 'relative output/update.json')) 'Relative output was resolved against the process directory instead of the caller directory.'
        } finally { Pop-Location; [Environment]::CurrentDirectory = $processDirectory }
    }
    $negativeCases = @(
        @{ Name='debug certificate with non-debuggable manifest'; Cert=$certificates.Replace('CN=Campus Toolbox','CN=Android Debug'); Reason='Android Debug certificate' },
        @{ Name='known existing debug signer even with a changed DN'; Cert=$certificates.Replace(('a' * 64),'f6b9669399e0f791f16542dff896474f803408645e84a07650bdcd98e0b83eb1'); Reason='Android Debug certificate' },
        @{ Name='debuggable with non-debug signer'; Badge=($badging + "`napplication-debuggable"); Reason='debuggable' },
        @{ Name='mismatched signer'; Cert=$certificates.Replace(('a' * 64),('b' * 64)); Reason='signer.*match' },
        @{ Name='multiple signers'; Cert=$certificates.Replace('Number of signers: 1','Number of signers: 2'); Reason='one signer' },
        @{ Name='incomplete certificate output'; Cert='Verifies'; Reason='certificate|one signer' },
        @{ Name='invalid signature'; JavaExit=1; Reason='signature verification failed' },
        @{ Name='aapt failure'; AaptExit=9; Reason='aapt' },
        @{ Name='wrong package'; Badge=$badging.Replace('cn.campuslife.assistant','com.example.other'); Reason='package' },
        @{ Name='missing minSdk'; Badge=$badging.Replace("sdkVersion:'26'",''); Reason='minSdk' },
        @{ Name='unexpected minSdk'; Badge=$badging.Replace("sdkVersion:'26'","sdkVersion:'27'"); Reason='minSdk' },
        @{ Name='equal versionCode'; Badge=$badging.Replace("versionCode='18'","versionCode='17'"); Reason='greater' },
        @{ Name='downgrade'; Badge=$badging.Replace("versionCode='18'","versionCode='16'"); Reason='greater' },
        @{ Name='fractional versionCode'; Badge=$badging.Replace("versionCode='18'","versionCode='18.5'"); Reason='versionCode' },
        @{ Name='oversized versionCode'; Badge=$badging.Replace("versionCode='18'","versionCode='2100000001'"); Reason='versionCode' },
        @{ Name='non-ASCII versionName'; Badge=$badging.Replace('1.7.1',$unicodeName); Reason='versionName' },
        @{ Name='path traversal versionName'; Badge=$badging.Replace('1.7.1','../evil'); Reason='versionName' },
        @{ Name='empty versionName'; Badge=$badging.Replace("versionName='1.7.1'","versionName=''"); Reason='versionName' },
        @{ Name='ambiguous package records'; Badge=($badging + "`n" + $badging); Reason='package' }
    )
    foreach ($case in $negativeCases) {
        Test-Case ('boundary rejects ' + $case.Name) {
            $script:releaseToolFixture = @{ Badging=$badging; Certificates=$certificates; AaptExit=0; JavaExit=0 }
            if ($case.ContainsKey('Badge')) { $script:releaseToolFixture.Badging = $case.Badge }
            if ($case.ContainsKey('Cert')) { $script:releaseToolFixture.Certificates = $case.Cert }
            foreach ($key in @('AaptExit','JavaExit')) { if ($case.ContainsKey($key)) { $script:releaseToolFixture[$key] = $case[$key] } }
            $parameters = $base.Clone(); $parameters.OutputDirectory = Join-Path $fixture ('reject-' + [guid]::NewGuid().ToString('N'))
            Assert-Rejected $parameters $case.Reason -Boundary
        }
    }
    $script:releaseToolFixture = @{ Badging=$badging; Certificates=$certificates; AaptExit=0; JavaExit=0 }
    foreach ($name in @('campus-toolbox-1.7.1.apk','update.json','SHA256SUMS.txt')) {
        Test-Case ('differing ' + $name + ' blocks all writes') {
            $parameters = $base.Clone(); $parameters.OutputDirectory = Join-Path $fixture ('conflict-' + [guid]::NewGuid().ToString('N'))
            [IO.Directory]::CreateDirectory($parameters.OutputDirectory) | Out-Null
            $existing = Join-Path $parameters.OutputDirectory $name
            [IO.File]::WriteAllText($existing, 'Keep existing content')
            $message = ''
            try { Invoke-ReleasePreparation @parameters | Out-Null } catch { $message = $_.Exception.Message }
            Assert-True ($message -match 'different|differing|overwrite') 'Conflicting output was not rejected.'
            Assert-True ((Get-ChildItem -LiteralPath $parameters.OutputDirectory).Count -eq 1 -and [IO.File]::ReadAllText($existing) -ceq 'Keep existing content') 'A conflict overwrote or partially wrote output.'
        }
    }
    Test-Case 'SDK autodetection honors ANDROID_HOME with Chinese and space paths' {
        $savedSdk = $env:ANDROID_HOME
        try {
            $env:ANDROID_HOME = Join-Path $fixture 'fake sdk'
            $parameters = $base.Clone(); $parameters.Remove('BuildToolsPath'); $parameters.OutputDirectory = Join-Path $fixture 'auto sdk output'
            Invoke-ReleasePreparation @parameters | Out-Null
            Assert-True (Test-Path -LiteralPath (Join-Path $parameters.OutputDirectory 'update.json')) 'Autodetected SDK did not produce metadata.'
        } finally { $env:ANDROID_HOME = $savedSdk }
    }
    Test-Case 'temporary workspaces are removed without deleting neighboring files' {
        Assert-True (@(Get-ChildItem -LiteralPath $fixtureRoot -Directory -Filter 'campus-release-*').Count -eq 0) 'A preparer workspace leaked.'
        Assert-True ((Get-FileHash -LiteralPath $syntheticApk).Hash.ToLowerInvariant() -ceq $syntheticHash) 'Input was modified or removed.'
        Assert-True (Test-Path -LiteralPath $DebugApkPath) 'Existing debug APK was removed.'
    }
    Test-Case 'caller environment and directory are preserved' {
        foreach ($key in $environmentKeys) { Assert-True ([Environment]::GetEnvironmentVariable($key) -ceq $savedEnvironment[$key]) "Environment changed: $key" }
        Assert-True ((Get-Location).Path -ceq $callerDirectory) 'Caller directory changed.'
    }
} finally {
    # Delete only this run's resolved GUID child, never the user-supplied TempRoot.
    $exactRoot = [IO.Path]::GetFullPath($fixtureRoot)
    if ([IO.Path]::GetDirectoryName($exactRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) -ne $resolvedTemp.TrimEnd([IO.Path]::DirectorySeparatorChar) -or [IO.Path]::GetFileName($exactRoot) -notmatch '^campus-release-tests-[0-9a-f]{32}$') { throw 'Unsafe test cleanup path.' }
    Remove-Item -LiteralPath $exactRoot -Recurse -Force
}
Write-Output "Release checks: $script:passed passed, $($script:failures.Count) failed. Real formally signed positive APK: NOT TESTED."
if ($script:failures.Count) { throw "$($script:failures.Count) release checks failed." }
