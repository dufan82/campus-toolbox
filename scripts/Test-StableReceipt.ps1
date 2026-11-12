$ErrorActionPreference='Stop'
. "$PSScriptRoot/StableReleaseCore.ps1"
. "$PSScriptRoot/StableReleaseBuild.ps1"
$root=Join-Path ([IO.Path]::GetTempPath()) ('stable-receipt-'+[guid]::NewGuid().ToString('N'))
$count=0
function Check($Condition,$Name){if(!$Condition){throw $Name};$script:count++;Write-Output "PASS $Name"}
try {
    [IO.Directory]::CreateDirectory((Join-Path $root 'app/src/main'))|Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $root 'app/src/test'))|Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $root 'tools'))|Out-Null
    [IO.File]::WriteAllText((Join-Path $root 'app/build.gradle.kts'),"versionCode = 105`nversionName = `"10.5.0`"`n")
    [IO.File]::WriteAllText((Join-Path $root 'app/src/main/App.kt'),'initial')
    [IO.File]::WriteAllText((Join-Path $root 'app/src/test/AppTest.kt'),'test')
    $fp={param($scope)Get-StableFingerprint $root $scope ('a'*64) (Join-Path $root 'tools')}
    $build=& $fp Build;$validation=& $fp Validation
    Check ((Get-StableVersion $root).Code -eq 105) 'versionCode automatically detected'
    [IO.File]::WriteAllText((Join-Path $root 'STATUS.md'),'documentation change only')
    Check (($build -ceq (& $fp Build)) -and ($validation -ceq (& $fp Validation))) 'unrelated documentation does not invalidate build/tests'
    [IO.File]::WriteAllText((Join-Path $root 'app/src/test/AppTest.kt'),'test changed')
    Check (($build -ceq (& $fp Build)) -and ($validation -cne (& $fp Validation))) 'test change invalidates validation, not release build'
    [IO.File]::WriteAllText((Join-Path $root 'local.properties'),'AMAP_ANDROID_KEY=synthetic-a')
    Check ($build -cne (& $fp Build)) 'private build configuration changes invalidate artifact without logging values'
    Remove-Item -LiteralPath (Join-Path $root 'local.properties')
    [IO.File]::WriteAllText((Join-Path $root 'app/src/main/New.kt'),'new untracked source')
    Check ($build -cne (& $fp Build)) 'new untracked App source invalidates build'
    $apk=Join-Path $root 'test.apk';[IO.File]::WriteAllText($apk,'synthetic')
    $receipt=@{SchemaVersion=1;BuildFingerprint=$build;ValidationFingerprint=$validation;ExpectedSigner=('a'*64);ApkSha256=(Get-FileHash $apk).Hash.ToLowerInvariant();ValidationPassed=$true}
    $reuse=Get-StableReuse $receipt $build $validation $apk ('a'*64)
    Check ($reuse.Build -and $reuse.Validation) 'same verified bytes and inputs reusable'
    Check (!(Get-StableReuse $receipt $build $validation $apk ('b'*64)).Build) 'signer expectation change invalidates cache'
    $receipt.ValidationPassed=$false
    Check (!(Get-StableReuse $receipt $build $validation $apk ('a'*64)).Validation) 'failed/incomplete validation never reused'
    $receipt.ValidationPassed=$true;[IO.File]::AppendAllText($apk,'tamper')
    Check (!(Get-StableReuse $receipt $build $validation $apk ('a'*64)).Build) 'APK tamper invalidates cached receipt'
    $noPublish=Join-Path $root 'must-not-exist'
    $result=& (Get-Process -Id $PID).Path -NoProfile -File "$PSScriptRoot/Publish-Stable.ps1" -Mode Publish -ProjectRoot $root -OutputDirectory $noPublish 2>&1
    Check ($LASTEXITCODE -ne 0 -and !(Test-Path -LiteralPath $noPublish)) 'no authorization means no prepare/network/publication'
    Check (($result -join ' ') -notmatch 'synthetic-a') 'failure output does not expose private configuration'
    Write-Output "PASS $count receipt/entrypoint tests"
}finally{if([IO.Path]::GetFileName($root) -match '^stable-receipt-[0-9a-f]{32}$'){Remove-Item -LiteralPath $root -Recurse -Force}}
