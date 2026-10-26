<#
.SYNOPSIS
Validate an already signed APK and prepare local stable-channel assets. Never signs or publishes.
.DESCRIPTION
Required: ApkPath, OutputDirectory, ExpectedSignerSha256 (64 hex digits), PreviousVersionCode.
SDK: BuildToolsPath, otherwise SdkPath / ANDROID_HOME / ANDROID_SDK_ROOT / local.properties.
Java: JavaHome / JAVA_HOME / PATH. TempRoot must be an existing ASCII path for aapt.
Dot-source to load functions without running preparation (used by the command-boundary tests).
#>
[CmdletBinding()]
param(
    [string]$ApkPath,
    [Alias('OutputDir')][string]$OutputDirectory,
    [string]$ExpectedSignerSha256,
    [string]$PreviousVersionCode,
    [string]$SdkPath,
    [string]$BuildToolsPath,
    [string]$JavaHome,
    [switch]$LegacyCompatibility,
    [string]$TempRoot = [IO.Path]::GetTempPath()
)

function Invoke-ReleaseTool([string]$Command, [string[]]$Arguments) {
    # Invoke executables with an argument array, never through cmd / a constructed command string.
    # Java runs apksigner.jar directly, so paths need no fragile .bat argument forwarding.
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    $lines = @(& $Command @Arguments 2>&1)
    return @{ ExitCode=$LASTEXITCODE; Text=($lines -join "`n") }
}

function Invoke-ReleasePreparation {
    [CmdletBinding()]
    param(
        [string]$ApkPath,
        [Alias('OutputDir')][string]$OutputDirectory,
        [string]$ExpectedSignerSha256,
        [string]$PreviousVersionCode,
        [string]$SdkPath,
        [string]$BuildToolsPath,
        [string]$JavaHome,
        [switch]$LegacyCompatibility,
        [string]$TempRoot = [IO.Path]::GetTempPath()
    )
    $ErrorActionPreference = 'Stop'
    $PSNativeCommandUseErrorActionPreference = $false
    $maximumCode = 2100000000
    $previousCode = 0
    if ($PreviousVersionCode -cnotmatch '\A[0-9]+\z' -or ![int]::TryParse($PreviousVersionCode, [ref]$previousCode) -or $previousCode -gt $maximumCode) {
        throw 'PreviousVersionCode must be an integer from 0 to 2100000000; use 0 only before the first published version.'
    }
    if ($ExpectedSignerSha256 -cnotmatch '\A[0-9A-Fa-f]{64}\z') { throw 'ExpectedSignerSha256 must be the fixed trusted certificate SHA-256 (64 hex digits, no colons).' }
    $expectedSigner = $ExpectedSignerSha256.ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { throw 'OutputDirectory is required.' }
    $outputRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
    if (!$ApkPath -or !(Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw 'An existing signed APK file is required.' }
    $inputApk = Get-Item -LiteralPath $ApkPath
    if ($inputApk.Extension -ine '.apk' -or $inputApk.Length -le 0 -or $inputApk.Length -gt 512MB) { throw 'APK file must have the .apk extension and contain 1 byte to 512 MiB of data.' }
    if ((Test-Path -LiteralPath $outputRoot) -and !(Test-Path -LiteralPath $outputRoot -PathType Container)) { throw 'OutputDirectory must be a directory.' }

    $windowsHost = [Environment]::OSVersion.Platform -eq 'Win32NT'
    $aaptName = if ($windowsHost) { 'aapt.exe' } else { 'aapt' }
    $javaName = if ($windowsHost) { 'java.exe' } else { 'java' }
    if (!$BuildToolsPath) {
        if (!$SdkPath) { $SdkPath = $env:ANDROID_HOME }
        if (!$SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
        if (!$SdkPath) {
            $localProperties = Join-Path (Split-Path $PSScriptRoot -Parent) 'local.properties'
            if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
                foreach ($line in [IO.File]::ReadAllLines($localProperties)) {
                    if ($line -match '^\s*sdk\.dir\s*[=:]\s*(.*)$') {
                        # Decode Java-properties path escapes without reading/logging other properties.
                        $SdkPath = [regex]::Replace($Matches[1].Trim(), '\\(u[0-9a-fA-F]{4}|.)', {
                            param($match)
                            $escape = $match.Groups[1].Value
                            if ($escape -cmatch '^u[0-9a-fA-F]{4}$') { return [string][char][Convert]::ToInt32($escape.Substring(1), 16) }
                            switch -CaseSensitive ($escape) { 't' { "`t" }; 'r' { "`r" }; 'n' { "`n" }; 'f' { "`f" }; default { $escape } }
                        })
                        break
                    }
                }
            }
        }
        if (!$SdkPath -and $env:LOCALAPPDATA) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
        if (!$SdkPath -or !(Test-Path -LiteralPath (Join-Path $SdkPath 'build-tools') -PathType Container)) { throw 'Android SDK Build Tools not found; set -SdkPath or -BuildToolsPath.' }
        $versions = @(Get-ChildItem -LiteralPath (Join-Path $SdkPath 'build-tools') -Directory |
            Where-Object { $_.Name -match '^\d+\.\d+\.\d+(?:\.\d+)?$' } |
            Sort-Object { [version]$_.Name } -Descending)
        foreach ($version in $versions) {
            if ((Test-Path -LiteralPath (Join-Path $version.FullName $aaptName) -PathType Leaf) -and (Test-Path -LiteralPath (Join-Path $version.FullName 'lib/apksigner.jar') -PathType Leaf)) {
                $BuildToolsPath = $version.FullName; break
            }
        }
    }
    if (!$BuildToolsPath -or !(Test-Path -LiteralPath $BuildToolsPath -PathType Container)) { throw 'Android Build Tools with aapt and lib/apksigner.jar are required.' }
    $toolsRoot = (Resolve-Path -LiteralPath $BuildToolsPath).ProviderPath
    $aapt = Join-Path $toolsRoot $aaptName
    $apksigner = Join-Path $toolsRoot 'lib/apksigner.jar'
    if (!(Test-Path -LiteralPath $aapt -PathType Leaf) -or !(Test-Path -LiteralPath $apksigner -PathType Leaf)) { throw 'Android Build Tools must contain aapt and lib/apksigner.jar.' }
    if (!$JavaHome) { $JavaHome = $env:JAVA_HOME }
    if ($JavaHome) {
        $java = Join-Path $JavaHome ('bin/' + $javaName)
        if (!(Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Java executable not found in the configured JDK; set -JavaHome.' }
        $java = (Resolve-Path -LiteralPath $java).ProviderPath
    } else {
        $javaCommand = Get-Command $javaName -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if (!$javaCommand) { throw 'Java not found; set -JavaHome or JAVA_HOME to a compatible JDK.' }
        $java = $javaCommand.Source
    }
    if (!$TempRoot -or !(Test-Path -LiteralPath $TempRoot -PathType Container)) { throw 'TempRoot must be an existing ASCII directory.' }
    $resolvedTemp = (Resolve-Path -LiteralPath $TempRoot).ProviderPath
    if ($resolvedTemp -match '[^\x20-\x7e]') { throw 'aapt requires an ASCII temporary APK path; set -TempRoot to an existing ASCII directory.' }
    $workspace = Join-Path $resolvedTemp ('campus-release-' + [guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($workspace) | Out-Null
    try {
        # Validate, hash and deliver this same immutable snapshot, never a later read of the source.
        $snapshot = Join-Path $workspace 'input.apk'
        [IO.File]::Copy($inputApk.FullName, $snapshot, $false)
        $badging = Invoke-ReleaseTool $aapt @('dump', 'badging', $snapshot)
        if ($badging.ExitCode -ne 0) { throw 'aapt could not read APK metadata.' }
        $signature = Invoke-ReleaseTool $java @('-jar', $apksigner, 'verify', '--verbose', '--print-certs', $snapshot)
        if ($signature.ExitCode -ne 0 -or $signature.Text -notmatch '(?m)^Verifies\r?$') { throw 'APK signature verification failed; unsigned or invalid APKs are never signed by this script.' }
        $signerCounts = [regex]::Matches($signature.Text, '(?m)^Number of signers: ([0-9]+)\r?$')
        $signerHashes = [regex]::Matches($signature.Text, '(?m)^Signer #([0-9]+) certificate SHA-256 digest: ([0-9a-fA-F]{64})\r?$')
        $signerNames = [regex]::Matches($signature.Text, '(?m)^Signer #([0-9]+) certificate DN: ([^\r\n]+)\r?$')
        if ($signerCounts.Count -ne 1 -or $signerCounts[0].Groups[1].Value -cne '1' -or $signerHashes.Count -ne 1 -or $signerNames.Count -ne 1 -or $signerHashes[0].Groups[1].Value -cne '1' -or $signerNames[0].Groups[1].Value -cne '1') {
            throw "Expected exactly one signer with a complete certificate identity (count records=$($signerCounts.Count), digest records=$($signerHashes.Count), name records=$($signerNames.Count))."
        }
        $actualSigner = $signerHashes[0].Groups[2].Value.ToLowerInvariant()
        $certificateName = $signerNames[0].Groups[2].Value
        $legacyDebugSigner = 'f6b9669399e0f791f16542dff896474f803408645e84a07650bdcd98e0b83eb1'
        if ($LegacyCompatibility) {
            if ($actualSigner -cne $legacyDebugSigner -or $expectedSigner -cne $legacyDebugSigner) {
                throw 'LegacyCompatibility accepts only the pinned legacy certificate; it is not a general debug-signing bypass.'
            }
        } elseif ($certificateName -match '(?i)\bCN\s*=\s*"?Android\s+Debug\b' -or $actualSigner -ceq $legacyDebugSigner) {
            throw 'Android Debug certificate is forbidden for formal artifacts, even when debuggable is false. See docs/RELEASING.md for the separate compatibility transition.'
        }
        if ($actualSigner -cne $expectedSigner) { throw 'APK signer does not match ExpectedSignerSha256; no signer replacement or debug fallback is allowed.' }
        $packages = [regex]::Matches($badging.Text, "(?m)^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'[^\r\n]*\r?$")
        if ($packages.Count -ne 1 -or $packages[0].Groups[1].Value -cne 'cn.campuslife.assistant') { throw 'Expected exactly one APK package cn.campuslife.assistant.' }
        $codeText = $packages[0].Groups[2].Value
        $versionCode = 0
        if ($codeText -cnotmatch '\A[0-9]+\z' -or ![int]::TryParse($codeText, [ref]$versionCode) -or $versionCode -lt 1 -or $versionCode -gt $maximumCode) { throw 'APK versionCode must be an integer from 1 to 2100000000.' }
        if ($versionCode -le $previousCode) { throw 'APK versionCode must be strictly greater than PreviousVersionCode.' }
        $versionName = $packages[0].Groups[3].Value
        if ($versionName -cnotmatch '\A[A-Za-z0-9][A-Za-z0-9._-]{0,39}\z') { throw 'APK versionName must be 1-40 safe ASCII letters, digits, dots, underscores or hyphens, starting with a letter or digit.' }
        $sdkRecords = [regex]::Matches($badging.Text, "(?m)^sdkVersion:'([0-9]+)'\r?$")
        if ($sdkRecords.Count -ne 1 -or $sdkRecords[0].Groups[1].Value -cne '26') { throw 'APK minSdk must be exactly 26 for this stable release protocol.' }
        if ($badging.Text -match '(?m)^application-debuggable(?:\s|$)') { throw 'A debuggable APK cannot be a formal artifact.' }
        $assetName = 'campus-toolbox-' + $versionName + '.apk'
        $assetPath = Join-Path $workspace $assetName
        [IO.File]::Move($snapshot, $assetPath)
        $size = (Get-Item -LiteralPath $assetPath).Length
        if ($size -le 0 -or $size -gt 512MB) { throw 'APK file snapshot must contain 1 byte to 512 MiB of data.' }
        $sha256 = (Get-FileHash -LiteralPath $assetPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $metadata = [ordered]@{
            schemaVersion=1; packageName='cn.campuslife.assistant'; channel='stable'
            versionCode=$versionCode; versionName=$versionName; tag=('v' + $versionName)
            minSdk=26; assetName=$assetName; size=$size; sha256=$sha256
        }
        $utf8 = New-Object Text.UTF8Encoding($false)
        $jsonPath = Join-Path $workspace 'update.json'
        [IO.File]::WriteAllText($jsonPath, (($metadata | ConvertTo-Json -Compress) + "`n"), $utf8)
        $jsonHash = (Get-FileHash -LiteralPath $jsonPath -Algorithm SHA256).Hash.ToLowerInvariant()
        [IO.File]::WriteAllText((Join-Path $workspace 'SHA256SUMS.txt'), "$sha256  $assetName`n$jsonHash  update.json`n", $utf8)
        $names = @($assetName, 'update.json', 'SHA256SUMS.txt')
        # Preflight the entire set before creating any output; never truncate existing files.
        foreach ($name in $names) {
            $destination = Join-Path $outputRoot $name
            if (Test-Path -LiteralPath $destination) {
                if (!(Test-Path -LiteralPath $destination -PathType Leaf) -or (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -cne (Get-FileHash -LiteralPath (Join-Path $workspace $name) -Algorithm SHA256).Hash) {
                    throw "Refusing to overwrite differing output: $name. Use a new output directory."
                }
            }
        }
        [IO.Directory]::CreateDirectory($outputRoot) | Out-Null
        foreach ($name in $names) {
            $destination = Join-Path $outputRoot $name
            if (!(Test-Path -LiteralPath $destination)) { [IO.File]::Copy((Join-Path $workspace $name), $destination, $false) }
        }
        [pscustomobject]@{ OutputDirectory=$outputRoot; AssetName=$assetName; VersionCode=$versionCode; Sha256=$sha256 }
    } finally {
        # The only recursive deletion is this invocation's exact GUID child.
        $exactWorkspace = [IO.Path]::GetFullPath($workspace)
        if ([IO.Path]::GetDirectoryName($exactWorkspace).TrimEnd([IO.Path]::DirectorySeparatorChar) -ne $resolvedTemp.TrimEnd([IO.Path]::DirectorySeparatorChar) -or [IO.Path]::GetFileName($exactWorkspace) -notmatch '^campus-release-[0-9a-f]{32}$') { throw 'Unsafe release cleanup path.' }
        Remove-Item -LiteralPath $exactWorkspace -Recurse -Force
    }
}

if ($MyInvocation.InvocationName -ne '.') { Invoke-ReleasePreparation @PSBoundParameters }
