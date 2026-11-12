# Offline state-machine tests. No credentials or network, no real APK publication.
$ErrorActionPreference='Stop'
. "$PSScriptRoot/StableReleaseCore.ps1"
$root=Join-Path ([IO.Path]::GetTempPath()) ('stable-tests-'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($root)|Out-Null
$count=0
function Assert($Condition,[string]$Message){if(!$Condition){throw $Message}}
function Case([string]$Name,[scriptblock]$Run){& $Run;$script:count++;Write-Output "PASS $Name"}
function Fixture {
    $dir=Join-Path $root ([guid]::NewGuid().ToString('N'));[IO.Directory]::CreateDirectory($dir)|Out-Null
    $apk=Join-Path $dir 'campus-toolbox-9.2.0.apk';[IO.File]::WriteAllText($apk,'synthetic APK bytes, not installable')
    $hash=(Get-FileHash $apk).Hash.ToLowerInvariant()
    $meta=@{schemaVersion=1;packageName='cn.campuslife.assistant';channel='stable';versionName='9.2.0';versionCode=92;tag='v9.2.0';minSdk=26;assetName='campus-toolbox-9.2.0.apk';size=(Get-Item $apk).Length;sha256=$hash}
    $json=Join-Path $dir 'update.json';[IO.File]::WriteAllText($json,($meta|ConvertTo-Json -Compress)+"`n")
    [IO.File]::WriteAllText((Join-Path $dir 'SHA256SUMS.txt'),"$hash  campus-toolbox-9.2.0.apk`n$((Get-FileHash $json).Hash.ToLowerInvariant())  update.json`n")
    $notes=Join-Path $dir 'notes.md';[IO.File]::WriteAllText($notes,'Synthetic test release')
    $plan=Get-StablePlan -AssetDirectory $dir -ReleaseNotesFile $notes -TargetCommit ('a'*40)
    @{Dir=$dir;Plan=$plan;State=(Join-Path $dir 'state.json');Remote=@{github=$null;cnb=$null};Calls=[Collections.Generic.List[string]]::new();Fail='';Failures=0}
}
function Remote($F){
    {param($Channel,$Operation,$Plan,$Release,$File)
        $F.Calls.Add("${Channel}:$Operation")
        if($F.Fail -eq "${Channel}:$Operation" -and $F.Failures -gt 0){$F.Failures--;throw [TimeoutException]::new('Synthetic timeout; credential must not be logged')}
        switch($Operation){
            'Latest' {if($F.Remote[$Channel] -and !$F.Remote[$Channel].Draft){return $F.Remote[$Channel]};return @{Tag='v9.1.0';VersionCode=91;Draft=$false;Prerelease=$false;Assets=@()}}
            'GetTag' {return $F.Remote[$Channel]}
            'CreateDraft' {$F.Remote[$Channel]=@{Id='123';Tag=$Plan.Tag;Draft=$true;Prerelease=$false;Latest=$false;VersionCode=$Plan.VersionCode;Assets=@()};return $F.Remote[$Channel]}
            'Upload' {$F.Remote[$Channel].Assets+=@{Name=$File.Name;Size=$File.Size;Sha256=$File.Sha256;State='uploaded'};return}
            'Publish' {$F.Remote[$Channel].Draft=$false;$F.Remote[$Channel].Latest=$true;return $F.Remote[$Channel]}
            default {throw 'Unknown mock operation'}
        }
    }.GetNewClosure()
}
function Go($F){Invoke-StablePipeline -Plan $F.Plan -StatePath $F.State -Remote (Remote $F) -MaxAttempts 2 -RetryDelayMilliseconds 0}
try {
    Case 'generic version and three immutable file hashes' {$f=Fixture;Assert ($f.Plan.VersionCode -eq 92 -and $f.Plan.Tag -eq 'v9.2.0' -and $f.Plan.Files.Count -eq 3) 'Version was hardcoded'}
    Case 'metadata/hash tamper rejected' {$f=Fixture;[IO.File]::AppendAllText((Join-Path $f.Dir 'update.json'),' ');try{Get-StablePlan $f.Dir (Join-Path $f.Dir 'notes.md') ('a'*40);throw 'accepted'}catch{Assert ($_.Exception.Message -ne 'accepted') 'Tampered checksums accepted'}}
    Case 'both verified before first publication' {$f=Fixture;$r=Go $f;Assert $r.Complete 'Not complete';Assert (($f.Calls|Where-Object{$_ -match ':Upload$'}).Count -eq 6) 'Upload count';Assert (($f.Calls|Where-Object{$_ -match ':Publish$'}).Count -eq 2) 'Publish count';Assert ([array]::IndexOf($f.Calls.ToArray(),'github:Publish') -gt [array]::LastIndexOf($f.Calls.ToArray(),'cnb:Upload')) 'Barrier violated'}
    Case 'rerun queries remote and skips all matching uploads' {$f=Fixture;$null=Go $f;$f.Calls.Clear();$null=Go $f;Assert (($f.Calls|Where-Object{$_ -match ':(Upload|CreateDraft|Publish)$'}).Count -eq 0) 'Repeated writes'}
    Case 'upload failure publishes neither channel then resumes only missing files' {$f=Fixture;$f.Fail='cnb:Upload';$f.Failures=3;$r=Go $f;Assert (!$r.Complete) 'False complete';Assert (($f.Calls|Where-Object{$_ -match ':Publish$'}).Count -eq 0) 'Published before barrier';$done=($f.Remote.github.Assets).Count;Assert ($done -eq 3) 'Other channel lost';$f.Failures=0;$f.Calls.Clear();$r=Go $f;Assert $r.Complete 'Resume failed';Assert (($f.Calls|Where-Object{$_ -eq 'github:Upload'}).Count -eq 0) 'Repeated successful upload'}
    Case 'server asset hash conflict never overwritten' {$f=Fixture;$f.Remote.github=@{Id='123';Tag=$f.Plan.Tag;Draft=$true;Prerelease=$false;VersionCode=92;Assets=@(@{Name=$f.Plan.Files[0].Name;Size=$f.Plan.Files[0].Size;Sha256=('0'*64);State='uploaded'})};$r=Go $f;Assert (!$r.Complete) 'Conflict accepted';Assert (($f.Calls|Where-Object{$_ -match ':Publish$'}).Count -eq 0) 'Conflict published'}
    Case 'already public matching GitHub can backfill CNB without changing GitHub' {$f=Fixture;$null=Go $f;$f.Remote.cnb=$null;$f.Calls.Clear();$r=Go $f;Assert $r.Complete 'Backfill failed';Assert (($f.Calls|Where-Object{$_ -match '^github:(Upload|Publish|CreateDraft)$'}).Count -eq 0) 'Public channel changed'}
    Case 'partial publication is accurately recorded and recoverable' {$f=Fixture;$f.Fail='cnb:Publish';$f.Failures=4;$r=Go $f;Assert (!$r.Complete -and $r.Channels.github.Public -and !$r.Channels.cnb.Public) 'Partial success hidden';$f.Failures=0;$f.Calls.Clear();$r=Go $f;Assert $r.Complete 'Partial resume failed';Assert (($f.Calls|Where-Object{$_ -eq 'github:Publish'}).Count -eq 0) 'Republished GitHub'}
    Case 'lying checkpoint is not authoritative' {$f=Fixture;[IO.File]::WriteAllText($f.State,'{"Complete":true}');$r=Go $f;Assert ($r.Complete -and ($f.Calls|Where-Object{$_ -match ':Upload$'}).Count -eq 6) 'Trusted checkpoint over server'}
    Case 'uploading asset without digest is not treated as verified' {$f=Fixture;$f.Remote.github=@{Id='123';Tag=$f.Plan.Tag;Draft=$true;Prerelease=$false;VersionCode=92;Assets=@(@{Name=$f.Plan.Files[0].Name;Size=$f.Plan.Files[0].Size;Sha256='';State='uploading'})};$r=Go $f;Assert (!$r.Complete) 'Missing digest accepted'}
    Case 'versionCode rollback blocked before creating drafts' {$f=Fixture;$remote=Remote $f;$fRemote={param($c,$o,$p,$r,$a)if($o -eq 'Latest'){return @{Tag='v10.0.0';VersionCode=100;Draft=$false;Prerelease=$false;Assets=@()}};& $remote $c $o $p $r $a}.GetNewClosure();$r=Invoke-StablePipeline $f.Plan $f.State -Remote $fRemote -RetryDelayMilliseconds 0;Assert (!$r.Complete) 'Downgrade allowed';Assert (($f.Calls|Where-Object{$_ -match ':CreateDraft$'}).Count -eq 0) 'Created downgrade draft'}
    Case 'local file modification rejected even with a previous plan' {$f=Fixture;[IO.File]::AppendAllText($f.Plan.Files[0].Path,'changed');try{Go $f;throw 'accepted'}catch{Assert ($_.Exception.Message -ne 'accepted') 'Local tamper accepted'}}
    Case 'explicit single-channel selection never queries or writes the other channel' {$f=Fixture;$r=Invoke-StablePipeline $f.Plan $f.State -Remote (Remote $f) -Channels github;Assert ($r.Complete -and !($f.Calls|Where-Object{$_ -like 'cnb:*'})) 'Single-channel boundary violated'}
    Case 'existing artifact recovery requires an exact public anchor' {$f=Fixture;try{Get-StablePublishedAnchor $f.Plan (Remote $f);throw 'accepted'}catch{Assert ($_.Exception.Message -ne 'accepted') 'Unpublished artifact bypassed validation'};$null=Go $f;$anchor=Get-StablePublishedAnchor $f.Plan (Remote $f);Assert ($anchor.Channel -eq 'github') 'Public artifact not reusable'}
    Case 'public anchor with different APK bytes rejects recovery' {$f=Fixture;$null=Go $f;$f.Remote.github.Assets[0].Sha256='0'*64;try{Get-StablePublishedAnchor $f.Plan (Remote $f);throw 'accepted'}catch{Assert ($_.Exception.Message -ne 'accepted') 'Wrong APK anchor accepted'}}
    Case 'other channel rollback is found before any draft creation' {$f=Fixture;$remote=Remote $f;$mixed={param($c,$o,$p,$r,$a)if($c -eq 'cnb' -and $o -eq 'Latest'){return @{Tag='v10.0.0';VersionCode=100;Draft=$false;Prerelease=$false;Assets=@()}};& $remote $c $o $p $r $a}.GetNewClosure();$r=Invoke-StablePipeline $f.Plan $f.State -Remote $mixed;Assert (!$r.Complete -and !($f.Calls|Where-Object{$_ -match ':CreateDraft$'})) 'Mutated before both preflights finished'}
    Case 'real runspaces overlap only synthetic per-channel uploads' {
        $adapter=Join-Path $root 'mock-transport.ps1'
        [IO.File]::WriteAllText($adapter,@'
function Invoke-StableRemote {
 param($Channel,$Operation,$Plan,$Release,$File)
 $path=Join-Path $Plan.MockRoot "$Channel.json"
 $r=if(Test-Path -LiteralPath $path){Get-Content -LiteralPath $path -Raw|ConvertFrom-Json -AsHashtable}else{$null}
 switch($Operation){
  'Latest' {if($r -and !$r.Draft){return $r};return @{Tag='v9.1.0';VersionCode=91;Draft=$false;Prerelease=$false;Assets=@()}}
  'GetTag' {return $r}
  'CreateDraft' {$r=@{Id='123';Tag=$Plan.Tag;Draft=$true;Prerelease=$false;VersionCode=$Plan.VersionCode;Assets=@();Trace=@()}}
  'Upload' {$start=[DateTime]::UtcNow.Ticks;Start-Sleep -Milliseconds 350;$r.Assets+=@{Name=$File.Name;Size=$File.Size;Sha256=$File.Sha256;State='uploaded'};$r.Trace+=@{Start=$start;End=[DateTime]::UtcNow.Ticks}}
  'Publish' {$r.Draft=$false;$r.Latest=$true}
 }
 [IO.File]::WriteAllText($path,($r|ConvertTo-Json -Depth 8));return $r
}
'@)
        $results=@{}
        foreach($parallel in @($false,$true)){
            $f=Fixture;$f.Plan|Add-Member MockRoot $f.Dir
            $clock=[Diagnostics.Stopwatch]::StartNew()
            $r=Invoke-StablePipeline $f.Plan $f.State -TransportScript $adapter -ParallelUploads:$parallel
            $clock.Stop();Assert $r.Complete 'Synthetic runspace publication failed'
            $results[[string]$parallel]=$clock.ElapsedMilliseconds
            if($parallel){$g=Get-Content (Join-Path $f.Dir 'github.json') -Raw|ConvertFrom-Json;$c=Get-Content (Join-Path $f.Dir 'cnb.json') -Raw|ConvertFrom-Json;Assert ([Math]::Max($g.Trace[0].Start,$c.Trace[0].Start) -lt [Math]::Min($g.Trace[-1].End,$c.Trace[-1].End)) 'Synthetic upload windows did not overlap'}
        }
        Write-Output ('SIMULATED ONLY, 350ms per tiny mock file; sequential/parallel ms: '+($results|ConvertTo-Json -Compress))
    }
    Write-Output "PASS $count offline stable-publish tests"
} finally {
    # Exact owned GUID directory only. Never touch repository artifacts or user data.
    if([IO.Path]::GetFileName($root) -match '^stable-tests-[0-9a-f]{32}$'){Remove-Item -LiteralPath $root -Recurse -Force}
}
