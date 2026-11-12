#requires -Version 7.0
<#
.SYNOPSIS
Check, prepare once, or explicitly publish one immutable signed APK to official channels.
.EXAMPLE
./scripts/Publish-Stable.ps1 -Mode Check -InspectRemote
.EXAMPLE
./scripts/Publish-Stable.ps1 -Mode Prepare -LegacyCompatibility -BuildToolsPath E:/skill/android-sdk/build-tools/36.0.0 -Offline
.EXAMPLE
# Only after user authorizes real publication; existing signing env values are reused.
./scripts/Publish-Stable.ps1 -Mode Publish -PublishAllowed -LegacyCompatibility -BuildToolsPath E:/skill/android-sdk/build-tools/36.0.0
# Default channels github,cnb. -Channels github is an explicit single-source exception.
# No Git commit/push, version editing, credentials prompt, Release deletion or clobber.
#>
[CmdletBinding()]
param([ValidateSet('Check','Prepare','Publish')][string]$Mode='Check',[switch]$PublishAllowed,[switch]$InspectRemote,
 [ValidateSet('github','cnb')][string[]]$Channels=@('github','cnb'),[string]$ProjectRoot=(Split-Path $PSScriptRoot -Parent),
 [string]$OutputDirectory,[string]$ReleaseNotesFile,[string]$BuildToolsPath,[string]$ExistingAssets,[int]$PreviousVersionCode=-1,
 [string]$ExpectedSignerSha256='f6b9669399e0f791f16542dff896474f803408645e84a07650bdcd98e0b83eb1',
 [switch]$LegacyCompatibility,[switch]$Offline,[ValidateRange(1,3)][int]$MaxAttempts=2,[switch]$SequentialUploads)
$ErrorActionPreference='Stop'
try {
    . "$PSScriptRoot/StableReleaseCore.ps1";. "$PSScriptRoot/StableReleaseBuild.ps1"
    if($Mode -eq 'Publish' -and !$PublishAllowed){throw 'Publishing requires explicit user authorization and -PublishAllowed.'}
    $ProjectRoot=(Resolve-Path -LiteralPath $ProjectRoot).Path;$version=Get-StableVersion $ProjectRoot
    if(!$OutputDirectory){$OutputDirectory=Join-Path $ProjectRoot "verification/stable-release/v$($version.Name)"}
    $OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
    $target=(& git -C $ProjectRoot rev-parse HEAD).Trim();if($LASTEXITCODE){throw 'Cannot identify source commit.'}
    if($Mode -eq 'Check'){
        $result=@{Mode=$Mode;VersionName=$version.Name;VersionCode=$version.Code;SourceCommit=$target;Channels=$Channels;NetworkQueried=[bool]$InspectRemote}
        if($InspectRemote){. "$PSScriptRoot/StableReleaseTransport.ps1";$result.Remote=@{};foreach($channel in $Channels){$result.Remote[$channel]=Invoke-StableRemote -Channel $channel -Operation Latest -Plan @{Tag="v$($version.Name)"}}}
        $result|ConvertTo-Json -Depth 8;return
    }
    if(!$BuildToolsPath){$sdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{$env:ANDROID_SDK_ROOT};if(!$sdk){throw 'Specify BuildToolsPath or ANDROID_HOME/ANDROID_SDK_ROOT.'};$BuildToolsPath=(Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory|Sort-Object Name -Descending|Select-Object -First 1).FullName}
    $BuildToolsPath=(Resolve-Path -LiteralPath $BuildToolsPath).Path
    if(!$ReleaseNotesFile){
        $changelog=[IO.File]::ReadAllText((Join-Path $ProjectRoot 'CHANGELOG.md'))
        $match=[regex]::Match($changelog,'(?ms)^## '+[regex]::Escape($version.Name)+'[^\r\n]*\r?\n(.*?)(?=^## |\z)')
        if(!$match.Success){throw 'No changelog entry for this version; specify ReleaseNotesFile.'}
        [IO.Directory]::CreateDirectory($OutputDirectory)|Out-Null
        $ReleaseNotesFile=Join-Path $OutputDirectory 'notes.md';$text=$match.Groups[1].Value.Trim()
        if(!(Test-Path -LiteralPath $ReleaseNotesFile) -or [IO.File]::ReadAllText($ReleaseNotesFile) -cne $text){[IO.File]::WriteAllText($ReleaseNotesFile,$text)}
    }
    if($ExistingAssets){
        if($Offline){throw 'ExistingAssets requires read-only official remote evidence; do not combine it with Offline.'}
        # Recovery is not a new-build shortcut: one official public channel must
        # already serve exactly these three bytes. Never replace that channel's APK.
        $plan=Get-StablePlan $ExistingAssets $ReleaseNotesFile $target
        if($plan.VersionCode -ne $version.Code -or $plan.VersionName -cne $version.Name){throw 'Existing artifact version differs from current requested version.'}
        . "$PSScriptRoot/StableReleaseTransport.ps1"
        $anchor=Get-StablePublishedAnchor $plan {param($c,$o,$p,$r,$f) Invoke-StableRemote -Channel $c -Operation $o -Plan $p -Release $r -File $f} $Channels
        $validated=Join-Path $OutputDirectory 'existing-validated'
        $policy=@($ExpectedSignerSha256,[string][bool]$LegacyCompatibility,($plan.Files.Sha256 -join '|'))
        foreach($p in @("$PSScriptRoot/Prepare-Release.ps1","$PSScriptRoot/StableReleaseBuild.ps1","$PSScriptRoot/StableReleaseCore.ps1",(Join-Path $BuildToolsPath 'lib/apksigner.jar'),(Join-Path $BuildToolsPath $(if($IsWindows){'aapt.exe'}else{'aapt'})))){$policy+=(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash}
        $key=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($policy -join '|')))).ToLowerInvariant()
        $recoveryReceipt=Join-Path $OutputDirectory 'existing-recovery.json';$signatureReused=$false
        if(Test-Path -LiteralPath $recoveryReceipt){
            try{$old=Get-Content -LiteralPath $recoveryReceipt -Raw|ConvertFrom-Json
                if($old.PolicyKey -ceq $key){$saved=Get-StablePlan $validated $ReleaseNotesFile $target;$signatureReused=($saved.Files.Sha256 -join '|') -ceq ($plan.Files.Sha256 -join '|')}
            }catch{$signatureReused=$false}
        }
        # PreviousCode=0 only verifies this ALREADY published recovery artifact;
        # actual newer-version/rollback rules are still enforced by both remotes.
        if(!$signatureReused){Assert-StableApkContents $plan.Files[0].Path
            $null=& "$PSScriptRoot/Prepare-Release.ps1" -ApkPath $plan.Files[0].Path -OutputDirectory $validated -ExpectedSignerSha256 $ExpectedSignerSha256 -PreviousVersionCode 0 -BuildToolsPath $BuildToolsPath -LegacyCompatibility:$LegacyCompatibility
        }
        $local=@{AssetDirectory=$validated;Kind='ExistingOfficialArtifactRecovery';BuildReused=$true;ValidationReused=$signatureReused;Anchor=$anchor;NoNewBuild=$true;PolicyKey=$key}
        if(!$signatureReused){Save-StableState $local $recoveryReceipt}
    }else{
        $local=Invoke-StableLocalPreparation -ProjectRoot $ProjectRoot -OutputDirectory $OutputDirectory -ExpectedSignerSha256 $ExpectedSignerSha256 -BuildToolsPath $BuildToolsPath -PreviousVersionCode $PreviousVersionCode -LegacyCompatibility:$LegacyCompatibility -Offline:$Offline
    }
    $plan=Get-StablePlan $local.AssetDirectory $ReleaseNotesFile $target
    if($Mode -eq 'Prepare'){$local|ConvertTo-Json -Depth 6;return}
    # Source commits stay a separate authorized Git operation. Never tag dirty App inputs.
    $dirty=& git -C $ProjectRoot status --porcelain -- app/src watermark-core/src app/build.gradle.kts watermark-core/build.gradle.kts gradle build.gradle.kts settings.gradle.kts gradle.properties
    if($dirty){throw 'App/build sources are uncommitted; commit them before publishing.'}
    $state=Invoke-StablePipeline -Plan $plan -StatePath (Join-Path $OutputDirectory 'publish-state.json') -Channels $Channels -MaxAttempts $MaxAttempts -ParallelUploads:(!$SequentialUploads)
    $local|ConvertTo-Json -Depth 6
    $state|ConvertTo-Json -Depth 10
    if(!$state.Complete){exit 1}
}catch{
    # No raw PowerShell exception dump: it may contain request URIs/credentials.
    [Console]::Error.WriteLine('Stable release stopped: '+$_.Exception.Message.Split("`n")[0]);exit 1
}
