#requires -Version 7.0
<#
Official transports only; dot-sourcing AND direct execution define functions, do nothing else.
Public entry: Invoke-StableRemote -Channel github|cnb -Operation GetTag|Latest|CreateDraft|Upload|Publish
  -Plan {Tag,VersionName,VersionCode,Name,Body,TargetCommit,Files}
  -Release {Id,Tag,Draft,Prerelease,Latest,VersionCode,Assets:[{Name,Size,Sha256,State}]}
  -File {Name,Path,Size,Sha256}
Returns a normalized release (GetTag/Latest may return null on HTTP 404). VersionCode is nullable on an
incomplete draft; it is NEVER inferred from a tag or the requested Plan. Latest requires a
verified update.json AND matching APK evidence from that very release. GetTag reads metadata
when present except on CNB drafts (VersionCode stays null). It never guesses Plan.VersionCode.
Upload returns a freshly checked release and only accepts a missing asset on the specified draft.
An already matching attachment is a no-op; conflicts and incomplete server evidence are errors.
Publish requires all Plan.Files and metadata, then PATCHes only the specified draft's flags.
No deletion, replacement, git push, token export, receipt/state-machine logic or automatic run.
Every request has one bounded attempt (no automatic replay of ambiguous writes). After any
failure re-read GetTag before deciding whether to retry. GH API/upload: 120/600 seconds;
CNB API/metadata/storage: 45/45/600 seconds; metadata redirects: at most three, unauthenticated.
CNB has no asset state in its official ReleaseAsset schema: a listed asset with positive size
and a valid sha256 hash is confirmed/uploaded evidence; an explicit non-uploaded state wins.
Helpers are prefixed Stable; keep them private to a module if symbol isolation is needed.
Latest also returns null on HTTP 404 only (first publication); a present release without
verified metadata fails closed. GH GetTag.Latest is deliberately false; use Latest separately.
CNB CreateDraft targets main (download mirror); GH targets Plan.TargetCommit's exact SHA.
Verified metadata is cached in memory by channel/ID/tag/SHA/size, never instead of reading
remote release state. Cache hits still revalidate the current APK's size/hash/state.
CNB draft Publish reads Plan.Files' local update.json, verifies its bytes against the freshly
read server asset SHA/size, then uses the same JSON/identity/APK checks as a remote download.
This local verification is never cached; post-PATCH Latest still verifies public metadata.
Do not enable PowerShell tracing/transcripts around credential-bearing code.
Official contracts: https://docs.github.com/en/rest/releases/releases
https://cli.github.com/manual/gh_api ; https://pkg.go.dev/cnb.cool/cnb/sdk/go-cnb/cnb/types/api#ReleaseAsset
#>

function Get-StableValue($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    if ($Object -is [Collections.IDictionary]) { return $Object[$Name] }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -ne $property) { return $property.Value }
    return $null
}

function Stop-Stable([string]$Code) {
    $exception = [InvalidOperationException]::new("StableTransport/$Code")
    if ($Code -cmatch '\AHTTP([1-5][0-9]{2})\z') {
        $status = [int]$Matches[1]
        $exception.Data['HttpStatus'] = $status
        $exception.Data['Retryable'] = $status -eq 429 -or $status -ge 500
    }
    if ($Code -cin @('GhTimeout','HttpTimeout')) { $exception.Data['Retryable'] = $true }
    throw $exception
}

function Test-StableInteger($Value, [long]$Minimum = 1, [long]$Maximum = [long]::MaxValue) {
    return ($Value -is [byte] -or $Value -is [int16] -or $Value -is [int32] -or $Value -is [int64] -or $Value -is [uint32]) -and
        $Value -ge $Minimum -and $Value -le $Maximum
}

function Assert-StableId($Id) {
    if ([string]$Id -cnotmatch '\A[1-9][0-9]{0,18}\z') { Stop-Stable InvalidReleaseId }
}

function Assert-StableTag($Tag) {
    if ($Tag -isnot [string] -or $Tag -cnotmatch '\Av[A-Za-z0-9][A-Za-z0-9._-]{0,63}\z' -or $Tag.Contains('..')) { Stop-Stable InvalidTag }
}

function Assert-StableName($Name) {
    if ($Name -isnot [string] -or $Name -cnotmatch '\A[A-Za-z0-9][A-Za-z0-9._-]{0,104}\z' -or $Name.Contains('..')) { Stop-Stable InvalidAssetName }
}

function Assert-StablePlan($Plan, [switch]$Write) {
    Assert-StableTag (Get-StableValue $Plan Tag)
    if (!$Write) { return }
    if (!(Test-StableInteger (Get-StableValue $Plan VersionCode) 1 2100000000) -or
        (Get-StableValue $Plan Tag) -cne ('v'+(Get-StableValue $Plan VersionName)) -or
        [string]::IsNullOrWhiteSpace((Get-StableValue $Plan Name)) -or ([string](Get-StableValue $Plan Name)).Length -gt 255 -or
        [string]::IsNullOrWhiteSpace((Get-StableValue $Plan Body)) -or ([string](Get-StableValue $Plan Body)).Length -gt 6000 -or
        [string](Get-StableValue $Plan TargetCommit) -cnotmatch '\A[0-9a-fA-F]{40}\z') { Stop-Stable InvalidPlan }
    $files = @(Get-StableValue $Plan Files)
    if (!$files.Count -or $files.Count -gt 100) { Stop-Stable InvalidPlanFiles }
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($file in $files) {
        Assert-StableName (Get-StableValue $file Name)
        if (!$names.Add($file.Name) -or !(Test-StableInteger $file.Size) -or [string]$file.Sha256 -cnotmatch '\A[a-fA-F0-9]{64}\z') { Stop-Stable InvalidPlanFiles }
    }
    if (@($files | Where-Object Name -CEQ 'update.json').Count -ne 1) { Stop-Stable MetadataMissing }
}

function ConvertTo-StableRelease([string]$Channel, $Raw, [bool]$IsLatest = $false) {
    Assert-StableId (Get-StableValue $Raw id)
    Assert-StableTag (Get-StableValue $Raw tag_name)
    if ((Get-StableValue $Raw draft) -isnot [bool] -or (Get-StableValue $Raw prerelease) -isnot [bool]) { Stop-Stable InvalidReleaseFlags }
    if ($Channel -ceq 'cnb') {
        if ((Get-StableValue $Raw is_latest) -isnot [bool]) { Stop-Stable InvalidReleaseFlags }
        $IsLatest = $Raw.is_latest
    }
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $assets = @(foreach ($asset in @(Get-StableValue $Raw assets)) {
        if ($null -eq $asset) { continue }
        $name = Get-StableValue $asset name
        Assert-StableName $name
        $size = Get-StableValue $asset size
        if (!$names.Add($name) -or !(Test-StableInteger $size 0)) { Stop-Stable InvalidAssetEvidence }
        $sha = $null
        if ($Channel -ceq 'github') {
            $digest = [string](Get-StableValue $asset digest)
            if ($digest -cmatch '\Asha256:([a-fA-F0-9]{64})\z') { $sha = $Matches[1].ToLowerInvariant() }
        } elseif ((Get-StableValue $asset hash_algo) -ceq 'sha256' -and [string](Get-StableValue $asset hash_value) -cmatch '\A[a-fA-F0-9]{64}\z') {
            $sha = ([string]$asset.hash_value).ToLowerInvariant()
        }
        $state = [string](Get-StableValue $asset state)
        if ($Channel -ceq 'cnb' -and !$state -and $sha -and $size -gt 0) { $state = 'uploaded' }
        if ($state -cnotin @('uploaded','available','ready','starter','pending','failed') -or !$sha -or $size -le 0) { $state = 'unknown' }
        [pscustomobject][ordered]@{Name=$name; Size=[long]$size; Sha256=$sha; State=$state}
    })
    [pscustomobject][ordered]@{Id=[string]$Raw.id; Tag=[string]$Raw.tag_name; Draft=$Raw.draft; Prerelease=$Raw.prerelease;
        Latest=$IsLatest; VersionCode=$null; Assets=$assets}
}

function Test-StableHttps([string]$Address) {
    try {
        if ($Address -match '[\x00-\x20\x7f\\]' -or $Address -match '(?i)%0[ad]') { return $false }
        $uri = [uri]$Address
        return $uri.IsAbsoluteUri -and $uri.Scheme -ceq 'https' -and $uri.Port -eq 443 -and !$uri.UserInfo -and !$uri.Fragment -and
            $uri.AbsolutePath -ne '/' -and !$uri.Host.EndsWith('.')
    } catch { return $false }
}

function Test-StableCnbStorageUri([string]$Address) {
    if (!(Test-StableHttps $Address)) { return $false }
    $uri = [uri]$Address
    return $uri.Host -ceq 'asset.cnb.cool' -or $uri.Host.EndsWith('.myqcloud.com', [StringComparison]::Ordinal)
}

function Get-StableCnbConfirmationPath([string]$Address, [string]$ReleaseId, [string]$ExpectedAssetName='') {
    Assert-StableId $ReleaseId
    if ($Address.StartsWith('/') -and !$Address.StartsWith('//')) { $Address = 'https://api.cnb.cool'+$Address }
    if (!(Test-StableHttps $Address)) { Stop-Stable ConfirmationUriRejected }
    $uri = [uri]$Address
    $prefix = '/campus-toolbox/campus-toolbox/-/releases/'+$ReleaseId+'/asset-upload-confirmation/'
    # CNB returns a URL-encoded storage object key. Validate one decoded copy, but
    # keep the original request/signature bytes. The repository/id prefix is never encoded.
    $marker='/asset-upload-confirmation/';$at=$Address.IndexOf($marker,[StringComparison]::Ordinal)
    if($at -lt 0 -or $Address.Substring(0,$at).Contains('%')){Stop-Stable ConfirmationUriRejected}
    $tail=$Address.Substring($at+$marker.Length).Split('?')[0]
    $decoded=[uri]::UnescapeDataString($tail)
    $name=$decoded.Split('/')[-1]
    if ($uri.Host -cnotin @('api.cnb.cool','cnb.cool') -or !$uri.AbsolutePath.StartsWith($prefix, [StringComparison]::Ordinal) -or
        $decoded.Length -gt 2048 -or $decoded -cnotmatch '\A[A-Za-z0-9._~-]+/[A-Za-z0-9._~/-]+\z' -or
        $decoded -match '(^|/)\.{1,2}(/|$)' -or $Address -match '/\.\.?(/|\?|$)' -or
        $name -cnotmatch '\A[A-Za-z0-9][A-Za-z0-9._-]*\.(?:apk|json|txt)\z' -or
        ($ExpectedAssetName -and $name -cne $ExpectedAssetName)) { Stop-Stable ConfirmationUriRejected }
    return $uri.PathAndQuery
}

function Assert-StableCnbApiUri([string]$Address, [string]$Method) {
    if (!(Test-StableHttps $Address)) { Stop-Stable ApiUriRejected }
    $uri = [uri]$Address
    $base = '/campus-toolbox/campus-toolbox/-/releases'
    $path = $uri.AbsolutePath
    if ($uri.Host -cne 'api.cnb.cool' -or $Address -match '/\.\.?(/|\?|$)' -or
        (!$path.Contains('/asset-upload-confirmation/') -and $Address.Contains('%'))) { Stop-Stable ApiUriRejected }
    $valid = switch -CaseSensitive ($Method) {
        'GET' { !$uri.Query -and ($path -ceq "$base/latest" -or $path -cmatch ('\A'+$base+'/(?:[1-9][0-9]{0,18}|tags/v[A-Za-z0-9][A-Za-z0-9._-]{0,63})\z')) }
        'POST' { ($path -ceq $base -and !$uri.Query) -or ($path -cmatch ('\A'+$base+'/[1-9][0-9]{0,18}/asset-upload-url\z') -and !$uri.Query) -or
            ($path -cmatch ('\A'+$base+'/([1-9][0-9]{0,18})/asset-upload-confirmation/')) }
        'PATCH' { !$uri.Query -and $path -cmatch ('\A'+$base+'/[1-9][0-9]{0,18}\z') }
        default { $false }
    }
    if (!$valid) { Stop-Stable ApiUriRejected }
    if ($path.Contains('/asset-upload-confirmation/')) {
        $id = $path.Substring(($base+'/').Length).Split('/')[0]
        $null = Get-StableCnbConfirmationPath $Address $id
    }
}

function New-StableHttpClient([int]$TimeoutSeconds) {
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $handler.UseCookies = $false
    $handler.UseDefaultCredentials = $false
    $client = [Net.Http.HttpClient]::new($handler, $true)
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
    return $client
}

function Get-StableCnbToken {
    . (Join-Path $PSScriptRoot 'CnbCredentialStore.ps1') 3>$null 4>$null 5>$null 6>$null
    Get-CampusCnbToken 3>$null 4>$null 5>$null 6>$null
}

function Test-StableTimeoutException([Exception]$Exception) {
    for ($depth=0; $null -ne $Exception -and $depth -lt 8; $depth++) {
        if ($Exception -is [OperationCanceledException] -or $Exception -is [TimeoutException]) { return $true }
        $Exception = $Exception.InnerException
    }
    return $false
}

function Read-StableResponseBytes($Response, [int]$Limit, [Threading.CancellationToken]$CancellationToken) {
    if ($Response.Content.Headers.ContentLength -gt $Limit) { Stop-Stable ResponseTooLarge }
    $stream = $Response.Content.ReadAsStreamAsync($CancellationToken).GetAwaiter().GetResult()
    $memory = [IO.MemoryStream]::new()
    try {
        $buffer = [byte[]]::new(8192)
        while (($count = $stream.ReadAsync($buffer, 0, $buffer.Length, $CancellationToken).GetAwaiter().GetResult()) -gt 0) {
            if ($memory.Length+$count -gt $Limit) { Stop-Stable ResponseTooLarge }
            $memory.Write($buffer, 0, $count)
        }
        return ,$memory.ToArray()
    } finally { $stream.Dispose(); $memory.Dispose() }
}

function Invoke-StableHttp([string]$Mode, [string]$Method, [string]$Address, $Body = $null, [string]$FilePath = '') {
    # URI validation precedes credential access. Storage and metadata never load any token.
    if ($Mode -ceq 'Api') { Assert-StableCnbApiUri $Address $Method }
    elseif ($Mode -ceq 'Storage') {
        if ($Method -cne 'PUT' -or !(Test-StableCnbStorageUri $Address)) { Stop-Stable StorageUriRejected }
    } elseif ($Mode -ceq 'Metadata') {
        if ($Method -cne 'GET' -or !(Test-StableHttps $Address) -or
            (([uri]$Address).Host -cne 'cnb.cool' -and !(Test-StableCnbStorageUri $Address))) { Stop-Stable MetadataUriRejected }
        if (([uri]$Address).Host -ceq 'cnb.cool' -and ($Address -cnotmatch '\Ahttps://cnb\.cool/campus-toolbox/campus-toolbox/-/releases/download/v[A-Za-z0-9][A-Za-z0-9._-]{0,63}/update\.json\z')) { Stop-Stable MetadataUriRejected }
    } else { Stop-Stable InvalidHttpMode }
    $seconds = if ($Mode -ceq 'Storage') { 600 } else { 45 }
    $client = $null; $request = $null; $response = $null; $token = $null; $stream = $null
    $cts = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($seconds))
    try {
        $client = New-StableHttpClient $seconds
        $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $Address)
        if ($Mode -ceq 'Api') {
            $token = Get-StableCnbToken
            if ($token -isnot [string] -or $token -cnotmatch '\A[A-Za-z0-9._~+/=-]{20,1024}\z') { Stop-Stable CredentialUnavailable }
            $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
            $request.Headers.Accept.ParseAdd('application/vnd.cnb.api+json')
        }
        if ($null -ne $Body) { $request.Content = [Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 10 -Compress), [Text.Encoding]::UTF8, 'application/json') }
        if ($Mode -ceq 'Storage') {
            $stream = [IO.File]::OpenRead($FilePath)
            $request.Content = [Net.Http.StreamContent]::new($stream)
            $request.Content.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('application/octet-stream')
            $request.Content.Headers.ContentLength = $stream.Length
        }
        $response = $client.SendAsync($request, [Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cts.Token).GetAwaiter().GetResult()
        $status = [int]$response.StatusCode
        if ($Mode -ceq 'Metadata' -and $status -in @(301,302,303,307,308)) {
            return [pscustomobject]@{Status=$status; Location=[string]$response.Headers.Location; Bytes=$null}
        }
        if ($Method -ceq 'GET' -and $status -eq 404 -and $Mode -ceq 'Api') { return $null }
        if (!$response.IsSuccessStatusCode) { Stop-Stable ("HTTP$status") }
        if ($Mode -ceq 'Storage') { return }
        $limit = if ($Mode -ceq 'Metadata') { 65536 } else { 8388608 }
        $bytes = Read-StableResponseBytes $response $limit $cts.Token
        if ($Mode -ceq 'Metadata') { return [pscustomobject]@{Status=$status; Location=$null; Bytes=$bytes} }
        if ($bytes.Length) {
            $parsed = [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json -Depth 30 -NoEnumerate -ErrorAction Stop
            if ($Method -ceq 'GET' -and ($null -eq $parsed -or $parsed -is [array])) { Stop-Stable EmptyApiResponse }
            return $parsed
        }
        if ($Method -ceq 'GET') { Stop-Stable EmptyApiResponse }
    } catch {
        $safe = if ($_.Exception.Message -cmatch '\AStableTransport/([A-Za-z0-9]+)\z') { $Matches[1] }
            elseif ($cts.IsCancellationRequested -or (Test-StableTimeoutException $_.Exception)) { 'HttpTimeout' } else { 'HttpFailure' }
        $global:Error.Remove($_)
        Stop-Stable $safe
    } finally {
        $token = $null
        if ($request) { $request.Headers.Authorization = $null; $request.Dispose() }
        if ($response) { $response.Dispose() }
        if ($stream) { $stream.Dispose() }
        if ($client) { $client.Dispose() }
        $cts.Dispose()
    }
}

function Invoke-StableGhProcess([string[]]$Arguments, [string]$InputJson = '', [int]$TimeoutSeconds = 120) {
    $process = $null
    try {
        $command = Get-Command gh -CommandType Application -ErrorAction Stop | Select-Object -First 1
        $info = [Diagnostics.ProcessStartInfo]::new($command.Source)
        $info.UseShellExecute = $false; $info.CreateNoWindow = $true
        $info.RedirectStandardInput = $true; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
        $info.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
        foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
        $info.Environment['GH_HOST'] = 'github.com'; $info.Environment['GH_PROMPT_DISABLED'] = '1'
        $info.Environment['GIT_TERMINAL_PROMPT'] = '0'
        foreach ($key in @('GH_DEBUG','DEBUG','GH_FORCE_TTY')) { $null = $info.Environment.Remove($key) }
        $process = [Diagnostics.Process]::new(); $process.StartInfo = $info
        if (!$process.Start()) { Stop-Stable GhStartFailed }
        $stdout = [IO.MemoryStream]::new()
        try {
            $outTask = $process.StandardOutput.BaseStream.CopyToAsync($stdout)
            $errTask = $process.StandardError.ReadToEndAsync()
            $process.StandardInput.Write($InputJson); $process.StandardInput.Close()
            if (!$process.WaitForExit($TimeoutSeconds*1000)) {
                $process.Kill($true); $null = $process.WaitForExit(5000)
                Stop-Stable GhTimeout
            }
            $null = $outTask.GetAwaiter().GetResult()
            $stderr = $errTask.GetAwaiter().GetResult()
            if ($stdout.Length -gt 8388608) { Stop-Stable ResponseTooLarge }
            $bytes = $stdout.ToArray()
            $header = [Text.Encoding]::UTF8.GetString($bytes, 0, [Math]::Min($bytes.Length, 32768))
            $status = 0
            if ($header -cmatch '\AHTTP/[0-9.]+ ([1-5][0-9]{2})[^\r\n]*\r?\n') { $status = [int]$Matches[1] }
            if ($process.ExitCode -ne 0) {
                if (!$status -and $stderr -match '\(HTTP ([1-5][0-9]{2})\)') { $status = [int]$Matches[1] }
                if ($status -eq 404) { return [pscustomobject]@{Status=404; Bytes=$null} }
                if ($status) { Stop-Stable ("HTTP$status") }
                Stop-Stable GhProcessFailed
            }
            if (!$status -or $status -ge 300) { Stop-Stable GhInvalidResponse }
            # --include puts HTTP headers before the body; locate delimiters in bytes, not decoded binary.
            $offset = $header.IndexOf("`r`n`r`n", [StringComparison]::Ordinal)
            $skip = 4
            if ($offset -lt 0) { $offset = $header.IndexOf("`n`n", [StringComparison]::Ordinal); $skip = 2 }
            if ($offset -lt 0) { Stop-Stable GhInvalidResponse }
            # Headers are ASCII, so character/byte offsets coincide.
            $bodyBytes = if ($offset+$skip -lt $bytes.Length) { [byte[]]$bytes[($offset+$skip)..($bytes.Length-1)] } else { [byte[]]::new(0) }
            return [pscustomobject]@{Status=$status; Bytes=$bodyBytes}
        } finally { $stdout.Dispose() }
    } catch {
        $safe = if ($_.Exception.Message -cmatch '\AStableTransport/([A-Za-z0-9]+)\z') { $Matches[1] } else { 'GhFailure' }
        $global:Error.Remove($_); Stop-Stable $safe
    } finally { if ($process) { $process.Dispose() } }
}

function Invoke-StableGhApi([string]$Method, [string]$Path, $Body = $null, [string]$FilePath = '', [switch]$Binary) {
    $base = '/repos/dufan82/campus-toolbox/releases'
    $valid = switch -CaseSensitive ($Method) {
        'GET' { $Path -cmatch ('\A'+$base+'/(?:latest|[1-9][0-9]{0,18}|tags/v[A-Za-z0-9][A-Za-z0-9._-]{0,63}|assets/[1-9][0-9]{0,18})\z') -or
            $Path -cmatch ('\A'+$base+'\?per_page=100&page=[1-9][0-9]?\z') }
        'POST' { $Path -ceq $base -or $Path -cmatch '\Ahttps://uploads\.github\.com/repos/dufan82/campus-toolbox/releases/[1-9][0-9]{0,18}/assets\?name=[A-Za-z0-9][A-Za-z0-9._-]{0,104}\z' }
        'PATCH' { $Path -cmatch ('\A'+$base+'/[1-9][0-9]{0,18}\z') }
        default { $false }
    }
    if (!$valid -or $Path.Contains('..')) { Stop-Stable ApiUriRejected }
    $arguments = @('api','--hostname','github.com','--include','--method',$Method,$Path,'-H','X-GitHub-Api-Version: 2022-11-28')
    $json = ''
    if ($Binary) { $arguments += @('-H','Accept: application/octet-stream') }
    else { $arguments += @('-H','Accept: application/vnd.github+json') }
    $timeout = 120
    if ($FilePath) { $arguments += @('--input',$FilePath,'-H','Content-Type: application/octet-stream'); $timeout = 600 }
    elseif ($null -ne $Body) { $json = $Body | ConvertTo-Json -Depth 10 -Compress; $arguments += @('--input','-') }
    $result = Invoke-StableGhProcess -Arguments $arguments -InputJson $json -TimeoutSeconds $timeout
    if ($result.Status -eq 404 -and $Method -ceq 'GET') { return $null }
    if ($result.Status -lt 200 -or $result.Status -ge 300) { Stop-Stable ("HTTP"+$result.Status) }
    if ($Binary) { return ,$result.Bytes }
    if ($result.Bytes.Length) {
        $jsonText = [Text.Encoding]::UTF8.GetString($result.Bytes)
        # -NoEnumerate distinguishes a legitimate empty listing [] from JSON null.
        $parsed = $jsonText | ConvertFrom-Json -Depth 30 -NoEnumerate -ErrorAction Stop
        if ($Method -ceq 'GET' -and $null -eq $parsed) { Stop-Stable EmptyApiResponse }
        if ($Method -ceq 'GET' -and $parsed -is [array] -and !$Path.Contains('?per_page=')) { Stop-Stable EmptyApiResponse }
        return $parsed
    }
    if ($Method -ceq 'GET') { Stop-Stable EmptyApiResponse }
}

function Invoke-StableApi([string]$Channel, [string]$Method, [string]$Suffix = '', $Body = $null) {
    if ($Channel -ceq 'github') { return Invoke-StableGhApi $Method ('/repos/dufan82/campus-toolbox/releases'+$Suffix) $Body }
    return Invoke-StableHttp Api $Method ('https://api.cnb.cool/campus-toolbox/campus-toolbox/-/releases'+$Suffix) $Body
}

function Get-StableRawRelease([string]$Channel, [string]$Kind, [string]$Value = '') {
    switch -CaseSensitive ($Kind) {
        'Latest' { return Invoke-StableApi $Channel GET '/latest' }
        'Id' { Assert-StableId $Value; return Invoke-StableApi $Channel GET ('/'+$Value) }
        'Tag' {
            Assert-StableTag $Value
            $raw = Invoke-StableApi $Channel GET ('/tags/'+$Value)
            if ($raw -or $Channel -ceq 'cnb') { return $raw }
            # GH's tag endpoint is for published releases. Include drafts visible to logged-in gh.
            for ($page=1; $page -le 10; $page++) {
                $list = @(Invoke-StableApi github GET ('?per_page=100&page='+$page))
                $matches = @($list | Where-Object { (Get-StableValue $_ tag_name) -ceq $Value })
                if ($matches.Count -gt 1) { Stop-Stable DuplicateRelease }
                if ($matches.Count -eq 1) { return $matches[0] }
                if ($list.Count -lt 100) { return $null }
            }
            Stop-Stable ReleaseLookupLimit
        }
        default { Stop-Stable InvalidLookup }
    }
}

function Read-StableMetadata([string]$Channel, $Raw, $Normalized, [bool]$Required, $LocalMetadataFile = $null) {
    # CNB draft downloads need not be public. GetTag must still return all normalized evidence.
    if ($Channel -ceq 'cnb' -and $Normalized.Draft -and !$Required) { return $null }
    $assets = @($Normalized.Assets | Where-Object Name -CEQ 'update.json')
    if (!$assets.Count) { if ($Required) { Stop-Stable MetadataMissing }; return $null }
    if ($assets.Count -ne 1 -or $assets[0].State -cnotin @('uploaded','available','ready') -or $assets[0].Size -gt 65536) { Stop-Stable MetadataEvidenceInvalid }
    if (!(Get-Variable -Name StableTransportMetadataCache -Scope Script -ErrorAction Ignore)) { $script:StableTransportMetadataCache = @{} }
    $cacheKey = $Channel+'|'+$Normalized.Id+'|'+$Normalized.Tag+'|'+$assets[0].Sha256+'|'+$assets[0].Size
    if ($Channel -ceq 'cnb' -and $Normalized.Draft) {
        if ($null -eq $LocalMetadataFile -or $LocalMetadataFile.Name -cne 'update.json') { Stop-Stable DraftMetadataRequiresLocal }
        Assert-StableAsset $Normalized $LocalMetadataFile
        $bytes = Read-StableLocalMetadataBytes $LocalMetadataFile.Path
        $verified = ConvertFrom-StableMetadataBytes $bytes $Normalized
        # A previous public cache entry must not substitute for post-publication verification.
        $script:StableTransportMetadataCache.Remove($cacheKey)
        return $verified.Code
    }
    if ($script:StableTransportMetadataCache.ContainsKey($cacheKey)) {
        $cached = $script:StableTransportMetadataCache[$cacheKey]
        Assert-StableMetadataApk $Normalized $cached.AssetName $cached.Size $cached.Sha256
        return $cached.Code
    }
    $asset = @($Raw.assets | Where-Object name -CEQ 'update.json')[0]
    if ($Channel -ceq 'github') {
        Assert-StableId (Get-StableValue $asset id)
        $bytes = Invoke-StableGhApi GET ('/repos/dufan82/campus-toolbox/releases/assets/'+$asset.id) -Binary
    } else {
        $address = 'https://cnb.cool/campus-toolbox/campus-toolbox/-/releases/download/'+$Normalized.Tag+'/update.json'
        foreach ($key in @('browser_download_url','brower_download_url')) {
            $given = Get-StableValue $asset $key
            if ($given -and $given -cne $address) { Stop-Stable MetadataUriRejected }
        }
        for ($redirect=0; $redirect -le 3; $redirect++) {
            $result = Invoke-StableHttp Metadata GET $address
            if ($result.Status -eq 200) { $bytes = $result.Bytes; break }
            if ($result.Status -notin @(301,302,303,307,308) -or $redirect -eq 3 -or !(Test-StableCnbStorageUri $result.Location)) { Stop-Stable MetadataRedirectRejected }
            $address = $result.Location
        }
    }
    $verified = ConvertFrom-StableMetadataBytes $bytes $Normalized
    if ($script:StableTransportMetadataCache.Count -ge 256) { $script:StableTransportMetadataCache.Clear() }
    $script:StableTransportMetadataCache[$cacheKey] = $verified
    return $verified.Code
}

function Read-StableLocalMetadataBytes([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    try {
        if ($stream.Length -le 0 -or $stream.Length -gt 65536) { Stop-Stable MetadataEvidenceInvalid }
        $bytes = [byte[]]::new([int]$stream.Length)
        $offset = 0
        while ($offset -lt $bytes.Length) {
            $count = $stream.Read($bytes, $offset, $bytes.Length-$offset)
            if ($count -le 0) { Stop-Stable MetadataDigestMismatch }
            $offset += $count
        }
        if ($stream.ReadByte() -ne -1) { Stop-Stable MetadataDigestMismatch }
        return ,$bytes
    } finally { $stream.Dispose() }
}

function ConvertFrom-StableMetadataBytes($Bytes, $Normalized) {
    # Local draft bytes and official download bytes must prove exactly the same server evidence.
    $assets = @($Normalized.Assets | Where-Object Name -CEQ 'update.json')
    if ($assets.Count -ne 1 -or $assets[0].State -cnotin @('uploaded','available','ready')) { Stop-Stable MetadataEvidenceInvalid }
    if ($null -eq $Bytes -or $Bytes.Length -ne $assets[0].Size -or $Bytes.Length -gt 65536 -or
        [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([byte[]]$Bytes)).ToLowerInvariant() -cne $assets[0].Sha256) { Stop-Stable MetadataDigestMismatch }
    $meta = [Text.UTF8Encoding]::new($false, $true).GetString([byte[]]$Bytes).TrimStart([char]0xfeff) | ConvertFrom-Json -Depth 15 -ErrorAction Stop
    if ($meta.schemaVersion -ne 1 -or $meta.packageName -cne 'cn.campuslife.assistant' -or $meta.channel -cne 'stable' -or
        !(Test-StableInteger $meta.versionCode 1 2100000000) -or $meta.tag -cne $Normalized.Tag -or
        $meta.tag -cne ('v'+$meta.versionName)) { Stop-Stable MetadataIdentityMismatch }
    Assert-StableMetadataApk $Normalized $meta.assetName $meta.size $meta.sha256
    return @{Code=[long]$meta.versionCode; AssetName=$meta.assetName; Size=$meta.size; Sha256=$meta.sha256}
}

function Assert-StableMetadataApk($Normalized, $Name, $Size, $Sha256) {
    Assert-StableName $Name
    $apk = @($Normalized.Assets | Where-Object Name -CEQ $Name)
    if ($apk.Count -ne 1 -or !$Name.EndsWith('.apk', [StringComparison]::Ordinal) -or
        !(Test-StableInteger $Size) -or $Sha256 -cnotmatch '\A[a-f0-9]{64}\z' -or
        $apk[0].Size -ne $Size -or $apk[0].Sha256 -cne $Sha256 -or $apk[0].State -cnotin @('uploaded','available','ready')) { Stop-Stable MetadataApkMismatch }
}

function Get-StableNormalized([string]$Channel, $Raw, [bool]$Latest = $false, [bool]$RequireMetadata = $false) {
    if ($null -eq $Raw) { return $null }
    $release = ConvertTo-StableRelease $Channel $Raw $Latest
    if ($RequireMetadata -and ($release.Draft -or $release.Prerelease -or !$release.Latest)) { Stop-Stable LatestNotStable }
    $release.VersionCode = Read-StableMetadata $Channel $Raw $release $RequireMetadata
    return $release
}

function Assert-StableDraft($Current, $Release, $Plan) {
    Assert-StableId (Get-StableValue $Release Id)
    if ($null -eq $Current -or $Current.Id -cne [string]$Release.Id -or $Current.Tag -cne $Release.Tag -or
        $Current.Tag -cne $Plan.Tag -or $Release.Draft -isnot [bool] -or !$Release.Draft -or
        !$Current.Draft -or $Current.Prerelease -or $Release.Prerelease -ne $false) { Stop-Stable DraftIdentityMismatch }
}

function Assert-StableAsset($Release, $File) {
    $found = @($Release.Assets | Where-Object Name -CEQ $File.Name)
    if ($found.Count -ne 1 -or $found[0].State -cnotin @('uploaded','available','ready') -or $found[0].Size -ne $File.Size -or
        $found[0].Sha256 -ine $File.Sha256) { Stop-Stable AssetConflict }
}

function Invoke-StableRemote {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][ValidateSet('github','cnb')][string]$Channel,
        [Parameter(Mandatory)][ValidateSet('GetTag','Latest','CreateDraft','Upload','Publish')][string]$Operation,
        [Parameter(Mandatory)]$Plan,
        $Release,
        $File
    )
    try {
        $Channel = $Channel.ToLowerInvariant()
        Assert-StablePlan $Plan -Write:($Operation -in @('CreateDraft','Upload','Publish'))
        switch ($Operation) {
            'GetTag' {
                $raw = Get-StableRawRelease $Channel Tag $Plan.Tag
                if ($null -eq $raw) { return $null }
                if ($raw.tag_name -cne $Plan.Tag) { Stop-Stable ReleaseIdentityMismatch }
                return Get-StableNormalized $Channel $raw $false
            }
            'Latest' { return Get-StableNormalized $Channel (Get-StableRawRelease $Channel Latest) $true $true }
            'CreateDraft' {
                if ($null -ne (Get-StableRawRelease $Channel Tag $Plan.Tag)) { Stop-Stable ReleaseAlreadyExists }
                $target = if ($Channel -ceq 'cnb') { 'main' } else { $Plan.TargetCommit }
                $raw = Invoke-StableApi $Channel POST '' @{tag_name=$Plan.Tag; target_commitish=$target; name=$Plan.Name; body=$Plan.Body; draft=$true; prerelease=$false; make_latest='false'}
                $created = Get-StableNormalized $Channel $raw
                if ($null -eq $created -or $created.Tag -cne $Plan.Tag -or !$created.Draft -or $created.Prerelease -or $created.Latest) { Stop-Stable DraftCreationUnconfirmed }
                return $created
            }
            { $_ -in @('Upload','Publish') } {
                Assert-StableId (Get-StableValue $Release Id)
                $raw = Get-StableRawRelease $Channel Id ([string]$Release.Id)
                # Do not require metadata while uploading; update.json may precede its APK.
                $current = if ($raw) { ConvertTo-StableRelease $Channel $raw } else { $null }
                Assert-StableDraft $current $Release $Plan
                if ($Operation -eq 'Upload') {
                    Assert-StableName (Get-StableValue $File Name)
                    $allowed = @($Plan.Files | Where-Object Name -CEQ $File.Name)
                    if ($allowed.Count -ne 1 -or $allowed[0].Size -ne $File.Size -or $allowed[0].Sha256 -ine $File.Sha256 -or
                        !(Test-StableInteger $File.Size) -or [string]$File.Sha256 -cnotmatch '\A[a-fA-F0-9]{64}\z') { Stop-Stable FileOutsidePlan }
                    if (@($current.Assets | Where-Object Name -IEQ $File.Name).Count) {
                        Assert-StableAsset $current $File
                        return $current
                    }
                    $local = Get-Item -LiteralPath $File.Path -ErrorAction Stop
                    if ($local.PSIsContainer -or $local.Length -ne $File.Size -or
                        (Get-FileHash -LiteralPath $local.FullName -Algorithm SHA256 -ErrorAction Stop).Hash -ine $File.Sha256) { Stop-Stable LocalFileMismatch }
                    if ($Channel -ceq 'github') {
                        $null = Invoke-StableGhApi POST ('https://uploads.github.com/repos/dufan82/campus-toolbox/releases/'+$current.Id+'/assets?name='+$File.Name) -FilePath $local.FullName
                    } else {
                        $upload = Invoke-StableApi cnb POST ('/'+$current.Id+'/asset-upload-url') @{asset_name=$File.Name; size=$File.Size; overwrite=$false; ttl=0}
                        $confirmation = Get-StableCnbConfirmationPath ([string]$upload.verify_url) $current.Id -ExpectedAssetName $File.Name
                        if (!(Test-StableCnbStorageUri ([string]$upload.upload_url))) { Stop-Stable StorageUriRejected }
                        $null = Invoke-StableHttp Storage PUT ([string]$upload.upload_url) -FilePath $local.FullName
                        $null = Invoke-StableHttp Api POST ('https://api.cnb.cool'+$confirmation)
                    }
                    $raw = Get-StableRawRelease $Channel Id $current.Id
                    $after = ConvertTo-StableRelease $Channel $raw
                    Assert-StableDraft $after $Release $Plan
                    Assert-StableAsset $after $File
                    # Full metadata is read by GetTag/Latest/Publish; partial uploads stay usable.
                    return $after
                }
                foreach ($planned in $Plan.Files) { Assert-StableAsset $current $planned }
                $localMetadata = if ($Channel -ceq 'cnb') { @($Plan.Files | Where-Object Name -CEQ 'update.json')[0] } else { $null }
                $code = Read-StableMetadata $Channel $raw $current $true $localMetadata
                if ($code -ne $Plan.VersionCode) { Stop-Stable PlanVersionMismatch }
                $null = Invoke-StableApi $Channel PATCH ('/'+$current.Id) @{draft=$false; prerelease=$false; make_latest='true'}
                $published = Get-StableNormalized $Channel (Get-StableRawRelease $Channel Latest) $true $true
                if ($published.Id -cne $current.Id -or $published.Tag -cne $Plan.Tag -or $published.VersionCode -ne $Plan.VersionCode) { Stop-Stable PublishUnconfirmed }
                foreach ($planned in $Plan.Files) { Assert-StableAsset $published $planned }
                return $published
            }
        }
    } catch {
        # Never rethrow raw exceptions/response bodies/CLI stderr or attach an inner exception.
        $safe = if ($_.Exception.Message -cmatch '\AStableTransport/([A-Za-z0-9]+)\z') { $Matches[1] } else { 'OperationFailed' }
        $global:Error.Remove($_)
        $exception = [InvalidOperationException]::new("StableTransport/$safe")
        if ($safe -cmatch '\AHTTP([1-5][0-9]{2})\z') {
            $status = [int]$Matches[1]
            $exception.Data['HttpStatus'] = $status
            $exception.Data['Retryable'] = $status -eq 429 -or $status -ge 500
        }
        if ($safe -cin @('GhTimeout','HttpTimeout')) { $exception.Data['Retryable'] = $true }
        $record = [Management.Automation.ErrorRecord]::new($exception, "StableTransport.$safe", [Management.Automation.ErrorCategory]::InvalidOperation, $null)
        $PSCmdlet.ThrowTerminatingError($record)
    }
}
