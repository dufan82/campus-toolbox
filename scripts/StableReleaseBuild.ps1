# Source/validation input receipts. Raw local.properties, signing passwords and tokens
# are never written to receipts/logs. A new source requires a signed incremental build.
function Get-StableVersion([string]$ProjectRoot) {
    $text=[IO.File]::ReadAllText((Join-Path $ProjectRoot 'app/build.gradle.kts'))
    $codes=[regex]::Matches($text,'(?m)^\s*versionCode\s*=\s*([0-9]+)\s*$')
    $names=[regex]::Matches($text,'(?m)^\s*versionName\s*=\s*"([A-Za-z0-9][A-Za-z0-9._-]{0,39})"\s*$')
    if($codes.Count -ne 1 -or $names.Count -ne 1){throw 'Cannot uniquely identify App version.'}
    @{Code=[int]$codes[0].Groups[1].Value;Name=$names[0].Groups[1].Value}
}
function Get-StableFingerprint {
    param([string]$ProjectRoot,[ValidateSet('Build','Validation')][string]$Scope='Build',[string]$ExpectedSignerSha256,[string]$BuildToolsPath)
    $root=[IO.Path]::GetFullPath($ProjectRoot);$paths=[Collections.Generic.List[string]]::new()
    $directories=@('app/src/main','app/src/release','watermark-core/src/main','gradle','buildSrc')
    if($Scope -eq 'Validation'){$directories+=@('app/src/test','app/src/androidTest','app/src/debug','watermark-core/src/test','scripts','.github/workflows')}
    foreach($dir in $directories){$path=Join-Path $root $dir;if(Test-Path -LiteralPath $path){Get-ChildItem -LiteralPath $path -Recurse -File|Where-Object{$_.FullName -notmatch '[\\/](build|\.gradle|\.verification|original-backup[^\\/]*)[\\/]'}|ForEach-Object{$paths.Add($_.FullName)}}}
    foreach($name in @('app/build.gradle.kts','app/proguard-rules.pro','watermark-core/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat','local.properties')){$p=Join-Path $root $name;if(Test-Path -LiteralPath $p){$paths.Add($p)}}
    $gradleHome=if($env:GRADLE_USER_HOME){$env:GRADLE_USER_HOME}else{Join-Path $HOME '.gradle'}
    foreach($p in @((Join-Path $gradleHome 'gradle.properties'),(Join-Path $gradleHome 'init.gradle'),(Join-Path $gradleHome 'init.gradle.kts'))){if(Test-Path -LiteralPath $p){$paths.Add($p)}}
    $init=Join-Path $gradleHome 'init.d';if(Test-Path -LiteralPath $init){Get-ChildItem -LiteralPath $init -Recurse -File|ForEach-Object{$paths.Add($_.FullName)}}
    # Toolchain binaries and signing input participate, not just the Git commit.
    $aapt=if($IsWindows){'aapt.exe'}else{'aapt'}
    foreach($p in @((Join-Path $BuildToolsPath $aapt),(Join-Path $BuildToolsPath 'lib/apksigner.jar'),$env:CAMPUS_SIGNING_STORE_FILE)) {if($p -and (Test-Path -LiteralPath $p)){$paths.Add([IO.Path]::GetFullPath($p))}}
    $sdk=Split-Path (Split-Path $BuildToolsPath -Parent) -Parent
    $androidJar=Join-Path $sdk 'platforms/android-36/android.jar';if(Test-Path -LiteralPath $androidJar){$paths.Add($androidJar)}
    $parts=[Collections.Generic.List[string]]::new();$parts.Add('stable-inputs-v1');$parts.Add($Scope);$parts.Add($ExpectedSignerSha256)
    foreach($p in ($paths|Sort-Object -Unique)){
        $item=Get-Item -LiteralPath $p
        if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Build input symlink/reparse point requires explicit review.'}
        $parts.Add($p+':'+(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash)
    }
    foreach($key in @('JAVA_HOME','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','GRADLE_OPTS','ANDROID_HOME','ANDROID_SDK_ROOT','GRADLE_USER_HOME','AMAP_ANDROID_KEY','CAMPUS_CNB_REPOSITORY','STORY_API_URL','STORY_AUTH_URL','CAMPUS_SIGNING_KEY_ALIAS')){
        $parts.Add($key+'='+[Environment]::GetEnvironmentVariable($key))
    }
    Get-ChildItem Env:|Where-Object{$_.Name -like 'ORG_GRADLE_PROJECT_*'}|Sort-Object Name|ForEach-Object{$parts.Add($_.Name+'='+$_.Value)}
    $javaInfo=@(& java -version 2>&1)-join "`n";if($LASTEXITCODE){throw 'Java toolchain unavailable.'};$parts.Add($javaInfo)
    # Only one aggregate digest is persisted; no individual secret hashes/config values.
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($parts -join "`n")))).ToLowerInvariant()
}
function Get-StableReuse {
    param($Receipt,[string]$BuildFingerprint,[string]$ValidationFingerprint,[string]$ApkPath,[string]$Signer)
    $build=$false;$validation=$false
    if($Receipt -and $Receipt.SchemaVersion -eq 1 -and $Receipt.BuildFingerprint -ceq $BuildFingerprint -and
       $Receipt.ExpectedSigner -ceq $Signer -and (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
        $build=((Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $Receipt.ApkSha256)
        $validation=$build -and $Receipt.ValidationFingerprint -ceq $ValidationFingerprint -and $Receipt.ValidationPassed -eq $true
    }
    @{Build=$build;Validation=$validation}
}
function Assert-StableApkContents([string]$ApkPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $names=@($zip.Entries|ForEach-Object{$_.FullName})
        if(@($names|Where-Object{$_ -match '(?i)suiji_private_review|(^|/)(?:local\.properties|.*\.(?:keystore|jks|pem))$'}).Count){throw 'Private review/signing assets cannot be published.'}
        foreach($abi in @('armeabi-v7a','arm64-v8a')){foreach($lib in @('libAMapSDK_MAP_v11_3_100.so','libapssdk.so')){if($names -cnotcontains "lib/$abi/$lib"){throw 'Required dual ARM SDK libraries are missing.'}}}
        if($names -cnotcontains 'assets/pingtan-reference.png'){throw 'Required campus reference asset missing.'}
    }finally{$zip.Dispose()}
}
function New-StableUnsignedTestFixture([string]$Source,[string]$Destination) {
    # Test-Release needs an unsigned rejection fixture, not a second Gradle build.
    # Repack a disposable copy: signing blocks/META-INF signatures are deliberately
    # absent. This file is NEVER put into the three-file publication directory.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $inputZip=[IO.Compression.ZipFile]::OpenRead($Source)
    $stream=[IO.File]::Open($Destination,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $outputZip=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create,$true)
    try {
        foreach($entry in $inputZip.Entries){
            if($entry.FullName -match '^META-INF/.*\.(?:RSA|DSA|EC|SF|MF)$'){continue}
            $copy=$outputZip.CreateEntry($entry.FullName,[IO.Compression.CompressionLevel]::NoCompression)
            $read=$entry.Open();$write=$copy.Open();try{$read.CopyTo($write)}finally{$read.Dispose();$write.Dispose()}
        }
    }finally{$outputZip.Dispose();$stream.Dispose();$inputZip.Dispose()}
}
function Invoke-StableLocalPreparation {
    param([string]$ProjectRoot,[string]$OutputDirectory,[string]$ExpectedSignerSha256,[string]$BuildToolsPath,
          [int]$PreviousVersionCode=-1,[switch]$LegacyCompatibility,[switch]$Offline)
    $clock=[Diagnostics.Stopwatch]::StartNew();$version=Get-StableVersion $ProjectRoot
    $legacy='f6b9669399e0f791f16542dff896474f803408645e84a07650bdcd98e0b83eb1'
    if($ExpectedSignerSha256 -cnotmatch '^[a-fA-F0-9]{64}$'){throw 'Invalid trusted signer digest.'}
    if(($LegacyCompatibility -and $ExpectedSignerSha256 -ine $legacy) -or (!$LegacyCompatibility -and $ExpectedSignerSha256 -ieq $legacy)){throw 'Historical compatibility certificate requires explicit -LegacyCompatibility; it cannot bypass another signer.'}
    [IO.Directory]::CreateDirectory($OutputDirectory)|Out-Null
    $guard=[IO.File]::Open((Join-Path $OutputDirectory 'prepare.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try {
        $build=Get-StableFingerprint $ProjectRoot Build $ExpectedSignerSha256 $BuildToolsPath
        $validation=Get-StableFingerprint $ProjectRoot Validation $ExpectedSignerSha256 $BuildToolsPath
        $receiptPath=Join-Path $OutputDirectory 'receipt.json';$receipt=$null
        if(Test-Path -LiteralPath $receiptPath){try{$receipt=Get-Content -LiteralPath $receiptPath -Raw|ConvertFrom-Json}catch{}}
        $assetDir=Join-Path $OutputDirectory 'assets';$apk=Join-Path $assetDir "campus-toolbox-$($version.Name).apk"
        $reuse=Get-StableReuse $receipt $build $validation $apk $ExpectedSignerSha256
        $gradleMs=0L;$prepareMs=0L
        if(!$reuse.Validation){
            $tasks=@(':watermark-core:test',':app:testDebugUnitTest',':app:lintDebug',':app:lintRelease')
            if(!$reuse.Build){
                foreach($key in @('STORE_FILE','STORE_PASSWORD','KEY_ALIAS','KEY_PASSWORD')){if(![Environment]::GetEnvironmentVariable("CAMPUS_SIGNING_$key")){throw 'New build requires all four existing CAMPUS_SIGNING_* values; no signing fallback or password prompt.'}}
                $tasks+=':app:assembleRelease'
            }
            $log=Join-Path $OutputDirectory ('validation-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff')+'.log')
            Push-Location $ProjectRoot
            try {
                $sw=[Diagnostics.Stopwatch]::StartNew();$arguments=@('--console=plain')+$tasks;if($Offline){$arguments+='--offline'}
                $wrapper=if($IsWindows){'gradlew.bat'}else{'gradlew'}
                & (Join-Path $ProjectRoot $wrapper) @arguments *> $log
                if($LASTEXITCODE){throw 'Gradle validation/build failed; inspect local validation log.'}
                foreach($test in @('Test-Build.ps1','Test-Release.ps1','Test-StablePublish.ps1','Test-StableReceipt.ps1','Test-StableReleaseTransport.ps1')){
                    $script=Join-Path $ProjectRoot "scripts/$test";if(!(Test-Path -LiteralPath $script)){throw 'Required release test script missing.'}
                    $params=@('-NoProfile','-File',$script);$unsigned=$null
                    try {
                        if($test -eq 'Test-Release.ps1'){
                            $unsigned=Join-Path $OutputDirectory ('test-unsigned-'+[guid]::NewGuid().ToString('N')+'.apk')
                            $fixtureSource=if($reuse.Build){$apk}else{Join-Path $ProjectRoot 'app/build/outputs/apk/release/app-release.apk'}
                            New-StableUnsignedTestFixture $fixtureSource $unsigned
                            $params+=@('-BuildToolsPath',$BuildToolsPath,'-UnsignedApkPath',$unsigned)
                        }
                        & (Get-Process -Id $PID).Path @params >> $log 2>&1
                        if($LASTEXITCODE){throw "Release test failed: $test. Inspect validation log."}
                    }finally{if($unsigned -and (Test-Path -LiteralPath $unsigned)){Remove-Item -LiteralPath $unsigned}}
                }
                $sw.Stop();$gradleMs=$sw.ElapsedMilliseconds
            }finally{Pop-Location}
            # Refuse to certify a source tree edited while Gradle was running.
            if($build -cne (Get-StableFingerprint $ProjectRoot Build $ExpectedSignerSha256 $BuildToolsPath) -or $validation -cne (Get-StableFingerprint $ProjectRoot Validation $ExpectedSignerSha256 $BuildToolsPath)){throw 'Inputs changed during validation; no success receipt written.'}
        }
        if(!$reuse.Build -or !$reuse.Validation){
            $sw=[Diagnostics.Stopwatch]::StartNew()
            $inputApk=if($reuse.Build){$apk}else{Join-Path $ProjectRoot 'app/build/outputs/apk/release/app-release.apk'}
            Assert-StableApkContents $inputApk
            if($PreviousVersionCode -lt 0){
                $PreviousVersionCode=0;$history=Join-Path $ProjectRoot '交付/发布附件'
                if(Test-Path -LiteralPath $history){foreach($m in Get-ChildItem -LiteralPath $history -Filter update.json -Recurse -File){try{$old=Get-Content -LiteralPath $m.FullName -Raw|ConvertFrom-Json;if($old.packageName -ceq 'cn.campuslife.assistant' -and $old.versionCode -lt $version.Code){$PreviousVersionCode=[Math]::Max($PreviousVersionCode,[int]$old.versionCode)}}catch{}}}
            }
            # Invoke in its own scope. Dot-sourcing a script with identically named
            # param() variables would silently reset this caller's signer/code inputs.
            $prepareArgs=@{ApkPath=$inputApk;OutputDirectory=$assetDir;ExpectedSignerSha256=$ExpectedSignerSha256;PreviousVersionCode=$PreviousVersionCode;BuildToolsPath=$BuildToolsPath;LegacyCompatibility=$LegacyCompatibility}
            $null=& (Join-Path $ProjectRoot 'scripts/Prepare-Release.ps1') @prepareArgs
            $sw.Stop();$prepareMs=$sw.ElapsedMilliseconds
        }
        # Validate all three sidecars before replacing the receipt, also on cache hits.
        $verified=Get-StablePlan -AssetDirectory $assetDir -TargetCommit ('0'*40)
        $metadata=Get-Content -LiteralPath (Join-Path $assetDir 'update.json') -Raw|ConvertFrom-Json
        if($metadata.versionCode -ne $version.Code -or $metadata.versionName -cne $version.Name){throw 'Artifact version does not match source.'}
        $receipt=@{SchemaVersion=1;BuildFingerprint=$build;ValidationFingerprint=$validation;ValidationPassed=$true;ExpectedSigner=$ExpectedSignerSha256;ApkSha256=$verified.Files[0].Sha256;VersionCode=$version.Code;VersionName=$version.Name;CreatedUtc=[DateTime]::UtcNow.ToString('o')}
        # A cache hit is reuse of the recorded verification, not a new verification
        # time. Keep the original receipt bytes/timestamp and existing three assets.
        if(!$reuse.Validation){Save-StableState $receipt $receiptPath}
        @{AssetDirectory=$assetDir;ReceiptPath=$receiptPath;BuildReused=$reuse.Build;ValidationReused=$reuse.Validation;ValidationMilliseconds=$gradleMs;PreparationMilliseconds=$prepareMs;TotalMilliseconds=$clock.ElapsedMilliseconds;VersionCode=$version.Code;VersionName=$version.Name}
    }finally{$guard.Dispose()}
}
