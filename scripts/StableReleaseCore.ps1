# PowerShell 7. Local release identity, remote reconciliation and publication barrier.
# Dot-sourcing has no network/file side effects. Checkpoints are evidence, not authority.
function Get-StablePlan {
    param([string]$AssetDirectory,[string]$ReleaseNotesFile,[string]$TargetCommit)
    $dir=(Resolve-Path -LiteralPath $AssetDirectory -ErrorAction Stop).Path
    $m=Get-Content -LiteralPath (Join-Path $dir 'update.json') -Raw -Encoding UTF8|ConvertFrom-Json
    if($m.schemaVersion -ne 1 -or $m.packageName -cne 'cn.campuslife.assistant' -or $m.channel -cne 'stable' -or
       $m.versionCode -isnot [long] -and $m.versionCode -isnot [int] -or $m.versionCode -lt 1 -or $m.versionCode -gt 2100000000 -or
       $m.versionName -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$' -or $m.tag -cne "v$($m.versionName)" -or
       $m.assetName -cne "campus-toolbox-$($m.versionName).apk" -or $m.minSdk -ne 26 -or $m.sha256 -cnotmatch '^[a-f0-9]{64}$') {throw 'Invalid stable release metadata.'}
    if($TargetCommit -cnotmatch '^[a-f0-9]{40}$'){throw 'An exact source commit SHA is required.'}
    $files=@($m.assetName,'update.json','SHA256SUMS.txt')|ForEach-Object {
        $p=Get-Item -LiteralPath (Join-Path $dir $_) -ErrorAction Stop
        if($p.PSIsContainer -or $p.Length -le 0 -or $p.Length -gt 512MB -or ($p.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Invalid release file.'}
        [pscustomobject]@{Name=$_;Path=$p.FullName;Size=$p.Length;Sha256=(Get-FileHash -LiteralPath $p.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    if($files[0].Size -ne $m.size -or $files[0].Sha256 -cne $m.sha256){throw 'APK hash/size mismatch.'}
    $sums=@(Get-Content -LiteralPath $files[2].Path -Encoding UTF8|Where-Object{$_.Trim()})
    if($sums.Count -ne 2 -or $sums[0] -cne "$($files[0].Sha256)  $($files[0].Name)" -or $sums[1] -cne "$($files[1].Sha256)  update.json"){throw 'Checksum list mismatch.'}
    $body=if($ReleaseNotesFile){[IO.File]::ReadAllText((Resolve-Path -LiteralPath $ReleaseNotesFile).Path)}else{"校伴 $($m.versionName)"}
    $name="校伴 $($m.versionName)"
    if($ReleaseNotesFile -and [IO.Path]::GetExtension($ReleaseNotesFile) -eq '.json'){$notes=$body|ConvertFrom-Json;$body=$notes.body;$name=$notes.name}
    if([string]::IsNullOrWhiteSpace($body) -or $body.Length -gt 6000 -or [string]::IsNullOrWhiteSpace($name) -or $name.Length -gt 255){throw 'Invalid release notes.'}
    [pscustomobject]@{Tag=$m.tag;VersionName=$m.versionName;VersionCode=[int]$m.versionCode;Name=$name;Body=$body;TargetCommit=$TargetCommit;Files=@($files)}
}
function Assert-StableAssetMatch($RemoteAsset,$File) {
    if(!$RemoteAsset -or $RemoteAsset.Size -ne $File.Size -or $RemoteAsset.Sha256 -cne $File.Sha256 -or
        $RemoteAsset.State -cnotin @('uploaded','available','ready')){throw 'Remote asset conflict, unconfirmed upload or absent SHA256; no overwrite allowed.'}
}
function Assert-StableRelease($Release,$Plan) {
    if(!$Release -or $Release.Tag -cne $Plan.Tag -or $Release.Prerelease){throw 'Unexpected release identity/status.'}
    if($Release.VersionCode -and $Release.VersionCode -ne $Plan.VersionCode){throw 'Remote versionCode mismatch.'}
    foreach($file in $Plan.Files){$assets=@($Release.Assets|Where-Object{$_.Name -ceq $file.Name});if($assets.Count -ne 1){throw 'Missing/duplicate remote asset.'};Assert-StableAssetMatch $assets[0] $file}
}
function Get-StablePublishedAnchor($Plan,$Remote,[string[]]$Channels=@('github','cnb')) {
    foreach($channel in $Channels){
        $r=& $Remote $channel GetTag $Plan $null $null
        if($r -and !$r.Draft -and !$r.Prerelease){Assert-StableRelease $r $Plan;return @{Channel=$channel;ReleaseId=$r.Id;Tag=$r.Tag}}
    }
    throw 'ExistingAssets recovery requires a matching already-public official release; it cannot publish an untested new artifact.'
}
function Save-StableState($State,[string]$Path) {
    $parent=Split-Path -Parent ([IO.Path]::GetFullPath($Path));[IO.Directory]::CreateDirectory($parent)|Out-Null
    $temp=Join-Path $parent ([guid]::NewGuid().ToString('N')+'.tmp')
    try{[IO.File]::WriteAllText($temp,($State|ConvertTo-Json -Depth 15));[IO.File]::Move($temp,$Path,$true)}finally{if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp}}
}
function Invoke-StableRequest {
    param($Remote,[string]$Channel,[string]$Operation,$Plan,$Release,$File,$Events)
    $clock=[Diagnostics.Stopwatch]::StartNew();$ok=$false
    try{$value=& $Remote $Channel $Operation $Plan $Release $File;$ok=$true;return $value}
    finally{$clock.Stop();$Events.Add([pscustomobject]@{Channel=$Channel;Operation=$Operation;Milliseconds=$clock.ElapsedMilliseconds;Success=$ok})}
}
function Test-StableRetry($ErrorRecord){return $ErrorRecord.Exception -is [TimeoutException] -or $ErrorRecord.Exception.Data['Retryable'] -eq $true}
function Get-StableFailureKind($ErrorRecord) {
    $status=$ErrorRecord.Exception.Data['HttpStatus']
    if($status -eq 401){return 'authentication-rejected'};if($status -eq 403){return 'permission-rejected'}
    if($ErrorRecord.Exception -is [TimeoutException]){return 'timeout'}
    if($ErrorRecord.Exception.Message -match 'conflict|SHA256|Duplicate|Mismatch'){return 'verification-conflict'}
    if($ErrorRecord.Exception.Message -match 'versionCode|Latest version'){return 'version-conflict'}
    return 'request-or-verification-failed'
}
function Sync-StableAssets {
    param($Plan,[string]$Channel,$Remote,[int]$MaxAttempts=2,[int]$RetryDelayMilliseconds=1000)
    $events=[Collections.Generic.List[object]]::new();$release=$null
    try {
        foreach($file in $Plan.Files){
            for($attempt=1;$attempt -le $MaxAttempts;$attempt++){
                try {
                    # A timed-out POST may have succeeded. Always query before retrying it.
                    $release=Invoke-StableRequest $Remote $Channel GetTag $Plan $null $null $events
                    if(!$release -or $release.Tag -cne $Plan.Tag -or $release.Prerelease){throw 'Release unavailable or changed.'}
                    $existing=@($release.Assets|Where-Object{$_.Name -ceq $file.Name})
                    if($existing.Count -gt 1){throw 'Duplicate remote asset.'}
                    if($existing.Count){Assert-StableAssetMatch $existing[0] $file;break}
                    if(!$release.Draft){throw 'Cannot add files to an already published release.'}
                    $null=Invoke-StableRequest $Remote $Channel Upload $Plan $release $file $events
                    $release=Invoke-StableRequest $Remote $Channel GetTag $Plan $null $null $events
                    $existing=@($release.Assets|Where-Object{$_.Name -ceq $file.Name});if($existing.Count -ne 1){throw 'Upload was not confirmed.'};Assert-StableAssetMatch $existing[0] $file
                    break
                } catch {
                    if($attempt -ge $MaxAttempts -or !(Test-StableRetry $_)){throw}
                    if($RetryDelayMilliseconds){Start-Sleep -Milliseconds $RetryDelayMilliseconds}
                }
            }
        }
        Assert-StableRelease $release $Plan
        @{Channel=$Channel;Verified=$true;Release=$release;Events=@($events.ToArray());Error=$null}
    } catch {@{Channel=$Channel;Verified=$false;Release=$release;Events=@($events.ToArray());Error=(Get-StableFailureKind $_)}}
}
function Invoke-StablePipeline {
    param($Plan,[string]$StatePath,$Remote,[ValidateSet('github','cnb')][string[]]$Channels=@('github','cnb'),
        [ValidateRange(1,3)][int]$MaxAttempts=2,[int]$RetryDelayMilliseconds=1000,[switch]$ParallelUploads,
        [string]$TransportScript=(Join-Path $PSScriptRoot 'StableReleaseTransport.ps1'))
    if(@($Channels|Select-Object -Unique).Count -ne $Channels.Count){throw 'Duplicate release channels.'}
    $localLocks=[Collections.Generic.List[IDisposable]]::new();$jobs=@();$stateLock=$null
    $state=@{SchemaVersion=1;Tag=$Plan.Tag;VersionCode=$Plan.VersionCode;ApkSha256=$Plan.Files[0].Sha256;LocalFileHashesVerified=$false;Complete=$false;BarrierVerified=$false;Channels=@{};Events=[Collections.Generic.List[object]]::new();Error=$null;StartedUtc=[DateTime]::UtcNow.ToString('o')}
    $customRemote=$null -ne $Remote
    if(!$Remote){. $TransportScript;$Remote={param($c,$o,$p,$r,$f) Invoke-StableRemote -Channel $c -Operation $o -Plan $p -Release $r -File $f}}
    try {
        $stateDir=Split-Path -Parent ([IO.Path]::GetFullPath($StatePath));[IO.Directory]::CreateDirectory($stateDir)|Out-Null
        $stateLock=[IO.File]::Open($StatePath+'.lock',[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
        foreach($file in $Plan.Files){
            $localLocks.Add([IO.File]::Open($file.Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read))
            if((Get-Item -LiteralPath $file.Path).Length -ne $file.Size -or (Get-FileHash -LiteralPath $file.Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $file.Sha256){throw 'Local release file changed after validation.'}
        }
        $state.LocalFileHashesVerified=$true
        # Reconcile real remote state on every run. Ignore a stale or forged checkpoint.
        $targets=@{}
        foreach($channel in $Channels){
            $state.Channels[$channel]=@{DraftCreated=$false;AssetsVerified=$false;Public=$false;Id=$null;Error=$null}
            $latest=Invoke-StableRequest $Remote $channel Latest $Plan $null $null $state.Events
            $target=Invoke-StableRequest $Remote $channel GetTag $Plan $null $null $state.Events
            if($target){foreach($file in $Plan.Files){$present=@($target.Assets|Where-Object{$_.Name -ceq $file.Name});if($present.Count -gt 1){throw 'Duplicate remote asset.'};if($present.Count){Assert-StableAssetMatch $present[0] $file}}}
            if($latest -and ($latest.Draft -or $latest.Prerelease -or !$latest.VersionCode)){throw 'Latest stable metadata is invalid.'}
            if($latest -and ($latest.VersionCode -gt $Plan.VersionCode -or ($latest.VersionCode -eq $Plan.VersionCode -and $latest.Tag -cne $Plan.Tag))){throw 'A newer/conflicting versionCode is already published.'}
            if($target -and !$target.Draft){Assert-StableRelease $target $Plan;if(!$latest -or $latest.Tag -cne $Plan.Tag){throw 'Existing published target is not latest; no changes allowed.'};$state.Channels[$channel].Public=$true}
            $targets[$channel]=$target
        }
        # Finish both read-only preflights before creating any draft.
        foreach($channel in $Channels){
            $target=$targets[$channel]
            if(!$target){
                for($i=1;$i -le $MaxAttempts;$i++){
                    try{$target=Invoke-StableRequest $Remote $channel GetTag $Plan $null $null $state.Events;if(!$target){$target=Invoke-StableRequest $Remote $channel CreateDraft $Plan $null $null $state.Events};break}
                    catch{if($i -ge $MaxAttempts -or !(Test-StableRetry $_)){throw};if($RetryDelayMilliseconds){Start-Sleep -Milliseconds $RetryDelayMilliseconds}}
                }
            }
            if(!$target -or $target.Tag -cne $Plan.Tag -or $target.Prerelease){throw 'Unexpected draft identity.'}
            $state.Channels[$channel].DraftCreated=$true;$state.Channels[$channel].Id=$target.Id
            Save-StableState $state $StatePath
        }
        $results=@()
        if($ParallelUploads -and !$customRemote -and $Channels.Count -gt 1){
            # Independent runspaces: credentials are read inside their channel, never serialized.
            foreach($channel in $Channels){
                $ps=[PowerShell]::Create()
                $null=$ps.AddScript({param($core,$transport,$p,$c,$attempts,$delay)
                    . $core;. $transport
                    Sync-StableAssets -Plan $p -Channel $c -Remote {param($c,$o,$p,$r,$f) Invoke-StableRemote -Channel $c -Operation $o -Plan $p -Release $r -File $f} -MaxAttempts $attempts -RetryDelayMilliseconds $delay
                }).AddArgument((Join-Path $PSScriptRoot 'StableReleaseCore.ps1')).AddArgument($TransportScript).AddArgument($Plan).AddArgument($channel).AddArgument($MaxAttempts).AddArgument($RetryDelayMilliseconds)
                $jobs+=@{Shell=$ps;Handle=$ps.BeginInvoke();Channel=$channel}
            }
            foreach($job in $jobs){try{$r=@($job.Shell.EndInvoke($job.Handle));if(!$r.Count -or $job.Shell.HadErrors){throw 'Worker failed'};$results+=$r[-1]}catch{$results+=@{Channel=$job.Channel;Verified=$false;Events=@();Error='Upload worker failed.'}}}
        } else {foreach($channel in $Channels){$results+=Sync-StableAssets $Plan $channel $Remote $MaxAttempts $RetryDelayMilliseconds}}
        foreach($result in $results){$state.Channels[$result.Channel].AssetsVerified=[bool]$result.Verified;$state.Channels[$result.Channel].Error=$result.Error;foreach($event in $result.Events){$state.Events.Add($event)}}
        Save-StableState $state $StatePath
        if(@($results|Where-Object{!$_.Verified}).Count){throw 'At least one channel failed asset verification; publication barrier remains closed.'}
        # Re-read BOTH channels immediately before releasing the barrier.
        foreach($channel in $Channels){$r=Invoke-StableRequest $Remote $channel GetTag $Plan $null $null $state.Events;Assert-StableRelease $r $Plan}
        $state.BarrierVerified=$true;Save-StableState $state $StatePath
        foreach($channel in $Channels){
            for($attempt=1;$attempt -le $MaxAttempts;$attempt++){
                try {
                    $latest=Invoke-StableRequest $Remote $channel Latest $Plan $null $null $state.Events
                    if($latest -and (!$latest.VersionCode -or $latest.VersionCode -gt $Plan.VersionCode -or ($latest.VersionCode -eq $Plan.VersionCode -and $latest.Tag -cne $Plan.Tag))){throw 'Latest version changed during upload.'}
                    $r=Invoke-StableRequest $Remote $channel GetTag $Plan $null $null $state.Events;Assert-StableRelease $r $Plan
                    if($r.Draft){$null=Invoke-StableRequest $Remote $channel Publish $Plan $r $null $state.Events}
                    $r=Invoke-StableRequest $Remote $channel GetTag $Plan $null $null $state.Events;Assert-StableRelease $r $Plan
                    if($r.Draft){throw 'Stable publication was not confirmed.'}
                    $state.Channels[$channel].Public=$true;Save-StableState $state $StatePath;break
                }catch{if($attempt -ge $MaxAttempts -or !(Test-StableRetry $_)){throw};if($RetryDelayMilliseconds){Start-Sleep -Milliseconds $RetryDelayMilliseconds}}
            }
        }
        foreach($channel in $Channels){$latest=Invoke-StableRequest $Remote $channel Latest $Plan $null $null $state.Events;Assert-StableRelease $latest $Plan;if($latest.Draft -or $latest.Tag -cne $Plan.Tag){throw 'Latest stable publication not confirmed.'}}
        $state.Complete=$true
    } catch {
        # Do not persist server bodies, signed URLs, request headers or exception dumps.
        if($_.Exception.Message -eq 'Local release file changed after validation.'){throw}
        $state.Error=Get-StableFailureKind $_
    } finally {
        foreach($job in $jobs){$job.Shell.Dispose()}
        foreach($item in $localLocks){$item.Dispose()}
        if($stateLock){$state.FinishedUtc=[DateTime]::UtcNow.ToString('o');Save-StableState $state $StatePath;$stateLock.Dispose()}
    }
    return $state
}
