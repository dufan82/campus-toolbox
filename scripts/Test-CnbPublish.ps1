[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$AssetDirectory, [Parameter(Mandatory=$true)][string]$ReleaseNotesFile)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Publish-CnbRelease.ps1') -AssetDirectory $AssetDirectory -ReleaseNotesFile $ReleaseNotesFile
$passed = 0
$failed = @()
function Assert-True([bool]$Value, [string]$Message) { if (!$Value) { throw $Message } }
function Case([string]$Name, [scriptblock]$Check) {
    try { & $Check; $script:passed++; Write-Output "PASS $Name" }
    catch { $script:failed += $Name; Write-Output "FAIL $Name" }
}
Case 'safe failure report preserves only a known API status and stage' {
    $details = Get-CnbFailureDetails 'CNB API returned HTTP 401. Check token scope, expiry and connection.' 'checking the target release'
    Assert-True ($details.httpStatus -eq 401 -and $details.stage -ceq 'checking the target release' -and !$details.published) 'Missing safe failure details.'
}
Case 'unexpected failure text and stage cannot leak credentials' {
    $details = Get-CnbFailureDetails 'Synthetic private-placeholder in a signed URL https://evil.test/?secret=private-placeholder' 'private-placeholder'
    $serialized = $details | ConvertTo-Json -Compress
    Assert-True ($serialized -notmatch 'private-placeholder|evil.test|secret=' -and $details.stage -ceq 'local validation' -and $null -eq $details.httpStatus) 'Unsafe diagnostic information.'
}
Case 'safe failure report recognizes verified attachment stages' {
    $details = Get-CnbFailureDetails 'Synthetic transport failure.' 'uploading campus-toolbox-1.7.4.apk'
    Assert-True ($details.stage -ceq 'uploading campus-toolbox-1.7.4.apk' -and $null -eq $details.httpStatus) 'Upload stage was lost.'
}
Case 'failure status cannot be inferred from arbitrary server text' {
    $details = Get-CnbFailureDetails 'Private response: HTTP 401, token=private-placeholder' 'checking the target release'
    Assert-True ($null -eq $details.httpStatus -and ($details | ConvertTo-Json -Compress) -notmatch 'private-placeholder') 'Raw response was trusted.'
}
Case 'exact prepared files produce only three planned assets' {
    $plan = Get-CnbPublishPlan $AssetDirectory $ReleaseNotesFile
    Assert-True ($plan.Tag -eq 'v1.7.4' -and $plan.Code -eq 21) 'Wrong release.'
    Assert-True (($plan.Files.Name -join ',') -eq 'campus-toolbox-1.7.4.apk,update.json,SHA256SUMS.txt') 'Unexpected upload.'
}
foreach ($address in @('https://asset.cnb.cool/upload?signature=test','https://bucket.cos.ap-shanghai.myqcloud.com/object?signature=test')) {
    Case "allow official upload $(([uri]$address).Host)" { Assert-True (Test-CnbUploadUri $address) 'Official storage rejected.' }
}
foreach ($address in @('http://asset.cnb.cool/upload','https://asset.cnb.cool.evil.test/upload','https://evil.test/upload','https://127.0.0.1/upload','https://user:pass@asset.cnb.cool/upload','https://asset.cnb.cool:444/upload','https://asset.cnb.cool/upload#fragment')) {
    Case "reject unsafe upload $address" { Assert-True (!(Test-CnbUploadUri $address)) 'Unsafe storage accepted.' }
}
Case 'confirmation is restricted to the same repository and release' {
    $expected = '/campus-toolbox/campus-toolbox/-/releases/123/asset-upload-confirmation/token/path'
    Assert-True ((Get-CnbConfirmationPath ('https://api.cnb.cool'+$expected) '123') -eq $expected) 'Unexpected confirmation path.'
    Assert-True ((Get-CnbConfirmationPath ('https://cnb.cool'+$expected) '123') -eq $expected) 'Public host should normalize to API.'
}
foreach($address in @('https://evil.test/campus-toolbox/campus-toolbox/-/releases/123/asset-upload-confirmation/a/b','https://api.cnb.cool/other/repo/-/releases/123/asset-upload-confirmation/a/b','https://api.cnb.cool/campus-toolbox/campus-toolbox/-/releases/456/asset-upload-confirmation/a/b')) {
    Case "reject foreign confirmation $address" {
        $rejected = $false
        try { Get-CnbConfirmationPath $address '123' | Out-Null } catch { $rejected = $true }
        Assert-True $rejected 'Foreign confirmation accepted.'
    }
}
foreach($entry in @(@{Method='DELETE';Path='/campus-toolbox/campus-toolbox/-/releases/123'},@{Method='POST';Path='/other/repo/-/releases'},@{Method='POST';Path='/campus-toolbox/campus-toolbox/-/releases/../../settings'})) {
    Case "API rejects unapproved route $($entry.Method) $($entry.Path)" {
        $rejected=$false
        try { Invoke-CnbApi $entry.Method $entry.Path 'synthetic-placeholder' | Out-Null } catch { $rejected=$true }
        Assert-True $rejected 'Unexpected API access.'
    }
}
$script:fixturePlan = Get-CnbPublishPlan $AssetDirectory $ReleaseNotesFile
$script:fixtureHashes = @{
    'campus-toolbox-1.7.4.apk'='7a9888fe941921b63a0f8fadcccd8cb5e9ee514a443c3ca9982448bc1ee7b2a9'
    'update.json'='a232d778d4b3bad62860a3bfd59f2c4e96a7608079ced3982264fb08ea8b40c2'
    'SHA256SUMS.txt'='db3f4fae0c9b7ac1dade418a6cbd69c7be457ced76d9975d7afda982ee0ee9fb'
}
# Only replace the external transport; publish ordering, validation and state handling remain real.
function Reset-Fixture([bool]$FailUpload=$false,[bool]$BadHash=$false) {
    $script:fixtureRelease=$null; $script:events=@(); $script:pendingName=$null; $script:putDone=$false
    $script:fixtureFailUpload=$FailUpload; $script:fixtureBadHash=$BadHash
}
function Invoke-CnbApi([string]$Method,[string]$Path,[string]$Token,$Body=$null) {
    $script:events += "$Method $Path"
    if($Method -eq 'GET'){return $script:fixtureRelease}
    if($Method -eq 'POST' -and $Path -eq '/campus-toolbox/campus-toolbox/-/releases') {
        Assert-True ($Body.draft -eq $true -and $Body.make_latest -ceq 'false') 'Release must start as non-latest draft.'
        $script:fixtureRelease=[pscustomobject]@{id='123';tag_name='v1.7.4';draft=$true;prerelease=$false;is_latest=$false;assets=@()}
        return $script:fixtureRelease
    }
    if($Path.EndsWith('/asset-upload-url')) {
        Assert-True ($Body.overwrite -eq $false -and $Body.ttl -eq 0) 'Must not overwrite or expire release assets.'
        $script:pendingName=$Body.asset_name; $script:putDone=$false
        return [pscustomobject]@{upload_url='https://asset.cnb.cool/fixture/'+$Body.asset_name;verify_url='https://api.cnb.cool/campus-toolbox/campus-toolbox/-/releases/123/asset-upload-confirmation/test/'+$Body.asset_name;expires_in_sec=300}
    }
    if($Path.Contains('/asset-upload-confirmation/')) {
        Assert-True $script:putDone 'Confirmation must follow PUT.'
        $file=$script:fixturePlan.Files | Where-Object Name -CEQ $script:pendingName
        $hash=$script:fixtureHashes[$script:pendingName]
        if($script:fixtureBadHash){$hash='0'*64}
        $script:fixtureRelease.assets += [pscustomobject]@{name=$file.Name;size=$file.Size;hash_algo='sha256';hash_value=$hash}
        return $null
    }
    if($Method -eq 'PATCH') {
        Assert-True ($script:fixtureRelease.assets.Count -eq 3) 'Cannot publish before all confirmations.'
        Assert-True ($Body.draft -eq $false -and $Body.prerelease -eq $false -and $Body.make_latest -ceq 'true') 'Incorrect stable flags.'
        $script:fixtureRelease.draft=$false; $script:fixtureRelease.is_latest=$true
        return $null
    }
    throw 'Unexpected offline fixture request.'
}
function Send-CnbAsset([string]$Address,[string]$FilePath) {
    $script:events+='PUT '+[IO.Path]::GetFileName($FilePath)
    if($script:fixtureFailUpload){throw 'Synthetic upload failure.'}
    $script:putDone=$true
}
Case 'only publish after all three confirmed server hashes match' {
    Reset-Fixture
    $result=Publish-CnbPlan $script:fixturePlan 'synthetic-placeholder'
    Assert-True (!$result.draft -and $result.is_latest) 'Latest release was not published.'
    Assert-True (@($script:events | Where-Object {$_ -like 'PUT *'}).Count -eq 3) 'Incorrect upload count.'
    Assert-True (@($script:events | Where-Object {$_ -like 'PATCH *'}).Count -eq 1) 'Incorrect publish count.'
}
Case 'failed upload never switches latest' {
    Reset-Fixture $true
    $rejected=$false
    try {Publish-CnbPlan $script:fixturePlan 'synthetic-placeholder'|Out-Null}catch{$rejected=$true}
    Assert-True ($rejected -and $script:fixtureRelease.draft -and !@($script:events|Where-Object {$_ -like 'PATCH *'}).Count) 'Failed upload must stay a draft.'
}
Case 'wrong server digest never switches latest' {
    Reset-Fixture $false $true
    $rejected=$false
    try {Publish-CnbPlan $script:fixturePlan 'synthetic-placeholder'|Out-Null}catch{$rejected=$true}
    Assert-True ($rejected -and $script:fixtureRelease.draft -and !@($script:events|Where-Object {$_ -like 'PATCH *'}).Count) 'Bad hashes must prevent publish.'
}
Case 'matching latest release is not uploaded or patched again' {
    Reset-Fixture
    Publish-CnbPlan $script:fixturePlan 'synthetic-placeholder' | Out-Null
    $script:events=@()
    Publish-CnbPlan $script:fixturePlan 'synthetic-placeholder' | Out-Null
    Assert-True (@($script:events|Where-Object {$_ -notlike 'GET *'}).Count -eq 0) 'Identical stable release must stay untouched.'
}
Write-Output "$passed passed; $($failed.Count) failed. Offline tests only."
if ($failed.Count) { exit 1 }
