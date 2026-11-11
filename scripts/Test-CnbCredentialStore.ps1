[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
if($env:OS -ne 'Windows_NT'){throw 'Run on Windows: native Credential Manager is required.'}
. (Join-Path $PSScriptRoot 'CnbCredentialStore.ps1')
$target='CampusCompanion/CNB/Test/'+[guid]::NewGuid().ToString('N')
$dummy='test-only-not-a-real-token-123456'
try {
    if($null -ne [CampusCnbCredentialStore]::Read($target)){throw 'Test target unexpectedly exists'}
    [CampusCnbCredentialStore]::Write($target,$dummy)
    if([CampusCnbCredentialStore]::Read($target) -cne $dummy){throw 'Round-trip mismatch'}
    [CampusCnbCredentialStore]::Remove($target)
    if($null -ne [CampusCnbCredentialStore]::Read($target)){throw 'Deletion failed'}
    $rejected=$false
    try{[CampusCnbCredentialStore]::Read('OtherApplication/NotAllowed')|Out-Null}catch{$rejected=$true}
    if(!$rejected){throw 'Target restriction failed'}
    'PASS: absent/read/write/remove and target restriction; no project credential modified.'
} finally {[CampusCnbCredentialStore]::Remove($target)}