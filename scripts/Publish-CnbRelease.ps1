[CmdletBinding()]
param([string]$AssetDirectory, [string]$ReleaseNotesFile, [switch]$Publish)

function Get-CnbFailureDetails([string]$Message, [string]$Stage) {
    $allowedStages = @('local validation','checking the target release','creating a draft release',
        'uploading campus-toolbox-1.7.4.apk','uploading update.json','uploading SHA256SUMS.txt',
        'checking all three server hashes','publishing the latest stable release')
    if ($Stage -cnotin $allowedStages) { $Stage = 'local validation' }
    $status = $null
    if ($Message -cmatch '^(?:CNB API|Asset upload) returned HTTP ([1-5][0-9]{2})\.') { $status = [int]$Matches[1] }
    $reason = 'local-or-network-error'
    if ($status -eq 401) { $reason = 'authentication-rejected' }
    elseif ($status -eq 403) { $reason = 'permission-rejected' }
    elseif ($null -ne $status) { $reason = 'http-error' }
    elseif ($Message -ceq 'Token format is invalid. No credentials are logged.') { $reason = 'token-format' }
    return @{tag='v1.7.4';published=$false;stage=$Stage;httpStatus=$status;reason=$reason;utc=[DateTime]::UtcNow.ToString('o')}
}

function Get-CnbPublishPlan([string]$Directory, [string]$NotesFile) {
    $root = (Resolve-Path -LiteralPath $Directory -ErrorAction Stop).ProviderPath
    $meta = [IO.File]::ReadAllText((Join-Path $root 'update.json'), [Text.Encoding]::UTF8) | ConvertFrom-Json
    if ($meta.packageName -cne 'cn.campuslife.assistant' -or $meta.schemaVersion -ne 1 -or $meta.channel -cne 'stable' -or
        $meta.versionCode -ne 21 -or $meta.versionName -cne '1.7.4' -or $meta.tag -cne 'v1.7.4' -or $meta.minSdk -ne 26 -or
        $meta.assetName -cne 'campus-toolbox-1.7.4.apk' -or $meta.size -ne 78841838 -or
        $meta.sha256 -cne '7a9888fe941921b63a0f8fadcccd8cb5e9ee514a443c3ca9982448bc1ee7b2a9') { throw 'This helper accepts only the verified v1.7.4 package.' }
    $files = @('campus-toolbox-1.7.4.apk','update.json','SHA256SUMS.txt') | ForEach-Object {
        $file = Get-Item -LiteralPath (Join-Path $root $_) -ErrorAction Stop
        if ($file.PSIsContainer -or $file.Length -le 0) { throw 'Missing or empty release file.' }
        [pscustomobject]@{Name=$_;Path=$file.FullName;Size=$file.Length;Sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    if ($files[0].Sha256 -cne $meta.sha256 -or $files[0].Size -ne $meta.size) { throw 'APK hash or size changed.' }
    $checksums = @([IO.File]::ReadAllLines($files[2].Path) | Where-Object { $_.Trim().Length })
    if ($checksums.Count -ne 2 -or $checksums[0] -cne ($files[0].Sha256+'  '+$files[0].Name) -or
        $checksums[1] -cne ($files[1].Sha256+'  '+$files[1].Name)) { throw 'Checksum file does not match the two release files.' }
    $notes = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $NotesFile).ProviderPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace($notes.name) -or $notes.name.Length -gt 255 -or [string]::IsNullOrWhiteSpace($notes.body) -or $notes.body.Length -gt 6000) { throw 'Invalid release notes.' }
    [pscustomobject]@{Tag='v1.7.4';Code=21;Name=$notes.name;Body=$notes.body;Files=@($files)}
}
function Test-CnbUploadUri([string]$Address) {
    try {
        $uri = [uri]$Address
        return $uri.IsAbsoluteUri -and $uri.Scheme -ceq 'https' -and $uri.Port -eq 443 -and !$uri.UserInfo -and !$uri.Fragment -and
            ($uri.Host -ceq 'asset.cnb.cool' -or $uri.Host.EndsWith('.myqcloud.com')) -and $uri.AbsolutePath -ne '/' -and $Address -notmatch '[\x00-\x20\x7f]'
    } catch { return $false }
}
function Get-CnbConfirmationPath([string]$Address, [string]$ReleaseId) {
    if ($ReleaseId -cnotmatch '^[1-9][0-9]{0,18}$') { throw 'Invalid release ID.' }
    if ($Address.StartsWith('/')) { $Address = 'https://api.cnb.cool'+$Address }
    $uri = [uri]$Address
    $prefix = '/campus-toolbox/campus-toolbox/-/releases/'+$ReleaseId+'/asset-upload-confirmation/'
    if (!$uri.IsAbsoluteUri -or $uri.Scheme -cne 'https' -or $uri.Port -ne 443 -or $uri.UserInfo -or $uri.Fragment -or
        $uri.Host -cnotin @('api.cnb.cool','cnb.cool') -or !$uri.AbsolutePath.StartsWith($prefix) -or
        $uri.AbsolutePath.Substring($prefix.Length) -notmatch '^[^/]+/.+$') { throw 'Foreign upload confirmation rejected.' }
    return $uri.PathAndQuery
}
function Invoke-CnbApi([string]$Method, [string]$Path, [string]$Token, $Body = $null) {
    $prefix = '/campus-toolbox/campus-toolbox/-/releases'
    $uri = [uri]('https://api.cnb.cool'+$Path)
    if ($Method -cnotin @('GET','POST','PATCH') -or ($uri.AbsolutePath -cne $prefix -and !$uri.AbsolutePath.StartsWith($prefix+'/')) -or
        $uri.Host -cne 'api.cnb.cool' -or $uri.UserInfo -or $uri.Port -ne 443 -or $uri.Fragment) { throw 'API request is outside the approved repository.' }
    $handler = New-Object Net.Http.HttpClientHandler
    $handler.AllowAutoRedirect = $false
    $client = New-Object Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(45)
    $request = New-Object Net.Http.HttpRequestMessage((New-Object Net.Http.HttpMethod($Method)), $uri)
    $request.Headers.Authorization = New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token)
    $request.Headers.Accept.ParseAdd('application/vnd.cnb.api+json')
    if ($null -ne $Body) { $request.Content = New-Object Net.Http.StringContent(($Body | ConvertTo-Json -Depth 8 -Compress), [Text.Encoding]::UTF8, 'application/json') }
    $response = $null
    try {
        try { $response = $client.SendAsync($request).GetAwaiter().GetResult() } catch { throw 'CNB API connection failed; no credentials are logged.' }
        $code = [int]$response.StatusCode
        if ($Method -ceq 'GET' -and $code -eq 404) { return $null }
        if (!$response.IsSuccessStatusCode) { throw ('CNB API returned HTTP '+$code+'. Check token scope, expiry and connection.') }
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (![string]::IsNullOrWhiteSpace($text)) { return ($text | ConvertFrom-Json) }
    } finally { if ($response) { $response.Dispose() }; $request.Dispose(); $client.Dispose(); $handler.Dispose() }
}
function Send-CnbAsset([string]$Address, [string]$FilePath) {
    if (!(Test-CnbUploadUri $Address)) { throw 'Upload storage host is not trusted.' }
    $handler = New-Object Net.Http.HttpClientHandler
    $handler.AllowAutoRedirect = $false
    $client = New-Object Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromMinutes(10)
    $stream = [IO.File]::OpenRead($FilePath)
    $content = New-Object Net.Http.StreamContent($stream)
    $content.Headers.ContentType = New-Object Net.Http.Headers.MediaTypeHeaderValue('application/octet-stream')
    $content.Headers.ContentLength = $stream.Length
    $response = $null
    try {
        # A separate client sends only file bytes, never the CNB account token, to object storage.
        try { $response = $client.PutAsync($Address, $content).GetAwaiter().GetResult() } catch { throw 'Asset upload connection failed; signed URLs are not logged.' }
        if (!$response.IsSuccessStatusCode) { throw ('Asset upload returned HTTP '+[int]$response.StatusCode+'.') }
    } finally { if ($response) { $response.Dispose() }; $content.Dispose(); $stream.Dispose(); $client.Dispose(); $handler.Dispose() }
}
function Assert-CnbAssets($Release, $Files) {
    foreach ($file in $Files) {
        $found = @($Release.assets | Where-Object { $_.name -ceq $file.Name })
        if ($found.Count -ne 1 -or $found[0].size -ne $file.Size -or $found[0].hash_algo -cne 'sha256' -or $found[0].hash_value -cne $file.Sha256) { throw 'Release attachments are missing or do not match. Existing files were not overwritten.' }
    }
}
function Publish-CnbPlan($Plan, [string]$Token) {
    $script:CnbPublishStep = 'checking the target release'
    $base = '/campus-toolbox/campus-toolbox/-/releases'
    $release = Invoke-CnbApi 'GET' ($base+'/tags/'+$Plan.Tag) $Token
    if (!$release) {
        $script:CnbPublishStep = 'creating a draft release'
        Write-Host 'Creating a draft release. Existing v1.7.3 stays latest during upload.'
        $release = Invoke-CnbApi 'POST' $base $Token @{tag_name=$Plan.Tag;target_commitish='main';name=$Plan.Name;body=$Plan.Body;draft=$true;prerelease=$false;make_latest='false'}
    }
    if ($release.tag_name -cne $Plan.Tag -or [string]$release.id -cnotmatch '^[1-9][0-9]{0,18}$') { throw 'Unexpected release identity.' }
    if (!$release.draft) {
        Assert-CnbAssets $release $Plan.Files
        if ($release.prerelease -or !$release.is_latest) { throw 'Existing release is not the latest stable release; no changes made.' }
        return $release
    }
    foreach ($file in $Plan.Files) {
        $existing = @($release.assets | Where-Object { $_.name -ceq $file.Name })
        if ($existing.Count) { Assert-CnbAssets $release @($file); continue }
        Write-Host ('Uploading '+$file.Name+' ('+$file.Size+' bytes)...')
        $script:CnbPublishStep = 'uploading '+$file.Name
        $upload = Invoke-CnbApi 'POST' ($base+'/'+$release.id+'/asset-upload-url') $Token @{asset_name=$file.Name;size=$file.Size;overwrite=$false;ttl=0}
        $confirm = Get-CnbConfirmationPath $upload.verify_url ([string]$release.id)
        if (!(Test-CnbUploadUri $upload.upload_url)) { throw 'Upload storage host is not trusted.' }
        Send-CnbAsset $upload.upload_url $file.Path
        Invoke-CnbApi 'POST' $confirm $Token | Out-Null
    }
    $release = Invoke-CnbApi 'GET' ($base+'/'+$release.id) $Token
    $script:CnbPublishStep = 'checking all three server hashes'
    Assert-CnbAssets $release $Plan.Files
    Write-Host 'All three server hashes match. Publishing the stable release...'
    $script:CnbPublishStep = 'publishing the latest stable release'
    Invoke-CnbApi 'PATCH' ($base+'/'+$release.id) $Token @{name=$Plan.Name;body=$Plan.Body;draft=$false;prerelease=$false;make_latest='true'} | Out-Null
    $latest = Invoke-CnbApi 'GET' ($base+'/latest') $Token
    if ($latest.tag_name -cne $Plan.Tag -or $latest.draft -or $latest.prerelease -or !$latest.is_latest) { throw 'Latest stable release was not confirmed.' }
    Assert-CnbAssets $latest $Plan.Files
    return $latest
}

if ($MyInvocation.InvocationName -ne '.') {
    $ErrorActionPreference = 'Stop'
    Add-Type -AssemblyName System.Net.Http
    $plan = Get-CnbPublishPlan $AssetDirectory $ReleaseNotesFile
    if (!$Publish) { Write-Output ('Validated '+$plan.Tag+'; 3 files. No network requests or credentials.'); return }
    Write-Host 'CNB v1.7.4 only: create release and upload the verified 3 files. No GitHub push.'
    Write-Host 'Use a short-lived token limited to this repository and repo-release:rw.'
    $script:CnbPublishStep = 'local validation'
    $secret = Read-Host 'Paste CNB token here (hidden; NEVER paste it into chat)' -AsSecureString
    $token = $null
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
        try { $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        if ([string]::IsNullOrWhiteSpace($token) -or $token.Length -gt 4096 -or $token -match '[\x00-\x20\x7f]') { throw 'Token format is invalid. No credentials are logged.' }
        $release = Publish-CnbPlan $plan $token
        $reportRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'verification'
        [IO.Directory]::CreateDirectory($reportRoot) | Out-Null
        $report = @{tag=$plan.Tag;releaseId=[string]$release.id;published=$true;utc=[DateTime]::UtcNow.ToString('o')}
        $reportFile = Join-Path $reportRoot ('cnb-publish-174-'+[guid]::NewGuid().ToString('N')+'.json')
        [IO.File]::WriteAllText($reportFile, ($report | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
        Write-Host 'Published and verified: https://cnb.cool/campus-toolbox/campus-toolbox/-/releases/tag/v1.7.4'
    } catch {
        $failure = Get-CnbFailureDetails $_.Exception.Message $script:CnbPublishStep
        Write-Host ('Stopped while '+$failure.stage+'. Reason: '+$failure.reason+'. HTTP: '+$failure.httpStatus)
        try {
            $reportRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'verification'
            [IO.Directory]::CreateDirectory($reportRoot) | Out-Null
            $reportFile = Join-Path $reportRoot ('cnb-publish-174-failed-'+[guid]::NewGuid().ToString('N')+'.json')
            [IO.File]::WriteAllText($reportFile, ($failure | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
            Write-Host 'Safe failure details saved locally. No token or response body is saved.'
        } catch { Write-Host 'Could not save the safe failure report.' }
        Write-Host 'No existing attachments were overwritten. A partial upload stays in a draft.'
        exit 1
    } finally { $token = $null; $secret.Dispose() }
}
