#requires -Version 7.0
# Offline only. All external I/O is replaced inside an isolated in-memory module.
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'StableReleaseTransport.ps1'
if (!(Test-Path -LiteralPath $source)) { throw 'FAIL: StableReleaseTransport.ps1 is missing.' }
$script:Passed = 0
function Assert($Condition, [string]$Message) { if (!$Condition) { throw "FAIL: $Message" } }
function Case([string]$Name, [scriptblock]$Test) { & $Test; $script:Passed++; Write-Output "PASS: $Name" }
function Reject([scriptblock]$Test, [string]$Code = '') {
    $caught = $false
    try { & $Test | Out-Null } catch {
        $caught = $true
        if ($Code) { Assert ($_.Exception.Message.Contains($Code)) "Expected safe code $Code" }
    }
    Assert $caught 'Expected rejection'
}
$module = New-Module -ArgumentList $source -ScriptBlock { param($Path) . $Path }
try {
    Case 'dot-source has no external I/O or caller preference changes' {
        $text = [IO.File]::ReadAllText($source)
        $tokens = $null; $errors = $null
        $ast = [Management.Automation.Language.Parser]::ParseInput($text, [ref]$tokens, [ref]$errors)
        Assert (!$errors.Count) 'Parser errors'
        Assert (@($ast.EndBlock.Statements | Where-Object { $_ -isnot [Management.Automation.Language.FunctionDefinitionAst] }).Count -eq 0) 'Top-level execution'
    }
    & $module {
        $script:Hash = 'a' * 64
        $script:Plan = [pscustomobject]@{ Tag='v9.0.0'; VersionName='9.0.0'; VersionCode=900; Name='fixture'; Body='offline'; TargetCommit=('b'*40); Files=@() }
        function script:New-Raw([string]$Channel = 'github', [bool]$Draft = $true) {
            $asset = @{id='7';name='fixture.apk';size=12;state='uploaded';digest=('sha256:'+$script:Hash);hash_algo='sha256';hash_value=$script:Hash}
            [pscustomobject]@{id='123';tag_name=$script:Plan.Tag;draft=$Draft;prerelease=$false;is_latest=(!$Draft);assets=@([pscustomobject]$asset)}
        }
        function script:Invoke-StableGhProcess { throw 'OFFLINE: unexpected process' }
        function script:Invoke-StableHttp { throw 'OFFLINE: unexpected HTTP' }
        function script:Add-Metadata($Raw, [string]$Channel, [long]$Code=900) {
            $script:MetadataBytes=[Text.Encoding]::UTF8.GetBytes((@{schemaVersion=1;packageName='cn.campuslife.assistant';channel='stable';versionName='9.0.0';versionCode=$Code;tag='v9.0.0';assetName='fixture.apk';size=12;sha256=$script:Hash} | ConvertTo-Json -Compress))
            $sha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($script:MetadataBytes)).ToLowerInvariant()
            $Raw.assets+= [pscustomobject]@{id='8';name='update.json';size=$script:MetadataBytes.Length;state='uploaded';digest=('sha256:'+$sha);hash_algo='sha256';hash_value=$sha}
            return $Raw
        }
        function script:Set-PlanFiles($Raw) {
            $script:Plan.Files=@($Raw.assets | ForEach-Object { [pscustomobject]@{Name=$_.name;Size=$_.size;Sha256=$_.hash_value;Path=('X:\offline\'+$_.name)} })
        }
    }
    Case 'GH digest and CNB hash fields normalize without leaking raw fields' {
        & $module {
            foreach ($channel in 'github','cnb') {
                $raw = New-Raw $channel
                if ($channel -eq 'cnb') { $raw.assets[0].PSObject.Properties.Remove('state') }
                $r = ConvertTo-StableRelease $channel $raw $false
                if ($r.Assets[0].Sha256 -cne $script:Hash -or $r.Assets[0].State -cne 'uploaded' -or $r.Assets[0].Size -ne 12) { throw 'Bad asset normalization' }
                if (($r.PSObject.Properties.Name -join ',') -cne 'Id,Tag,Draft,Prerelease,Latest,VersionCode,Assets') { throw 'Unexpected normalized fields' }
            }
        }
    }
    Case 'missing and malformed digests never become uploaded CNB evidence' {
        & $module {
            $raw=New-Raw cnb; $raw.assets[0].hash_value='bad'; $raw.assets[0].PSObject.Properties.Remove('state')
            $r=ConvertTo-StableRelease cnb $raw $false
            if ($null -ne $r.Assets[0].Sha256 -or $r.Assets[0].State -eq 'uploaded') { throw 'Untrusted CNB evidence' }
        }
    }
    Case 'missing Latest metadata fails closed; GetTag version is not guessed' {
        & $module {
            function Get-StableRawRelease { New-Raw github $false }
            function Read-StableMetadata { param($Channel,$Raw,$Normalized,$Required) if ($Required) { throw 'StableTransport/MetadataMissing' }; return $null }
            $r=Invoke-StableRemote github GetTag $script:Plan
            if ($null -ne $r.VersionCode) { throw 'Guessed VersionCode' }
            $failed=$false
            try { Invoke-StableRemote github Latest $script:Plan | Out-Null } catch { $failed=$_.Exception.Message.Contains('MetadataMissing') }
            if (!$failed) { throw 'Latest accepted absent metadata' }
        }
    }
    Case 'public exceptions remove arbitrary token and signed-URL text' {
        & $module {
            function Get-StableRawRelease { throw 'secret-fixture-token https://asset.cnb.cool/a?signature=private' }
            try { Invoke-StableRemote cnb GetTag $script:Plan; throw 'not rejected' } catch {
                if ($_.Exception.Message -notmatch '^StableTransport/' -or $_.Exception.ToString() -match 'secret-fixture|signature=') { throw 'Unsafe public error' }
            }
        }
    }
    Case 'CNB creates main draft; GH uses exact SHA; neither sets latest' {
        & $module {
            $script:Raw=Add-Metadata (New-Raw) github; Set-PlanFiles $script:Raw
            function Get-StableRawRelease { return $null }
            function Read-StableMetadata { return $null }
            function Invoke-StableApi($Channel,$Method,$Suffix,$Body) {
                $target=if($Channel -eq 'cnb'){'main'}else{$script:Plan.TargetCommit}
                if ($Method -ne 'POST' -or $Suffix -ne '' -or !$Body.draft -or $Body.prerelease -or $Body.make_latest -cne 'false' -or $Body.target_commitish -cne $target) { throw 'Wrong draft body' }
                $r=New-Raw $Channel; $r.is_latest=$false; return $r
            }
            foreach($channel in 'github','cnb') { $r=Invoke-StableRemote $channel CreateDraft $script:Plan; if(!$r.Draft -or $r.Latest){throw 'Wrong draft result'} }
        }
    }
    Case 'valid metadata yields actual remote code for both channels' {
        & $module {
            function Invoke-StableGhApi { return ,$script:MetadataBytes }
            function Invoke-StableHttp { [pscustomobject]@{Status=200;Bytes=$script:MetadataBytes} }
            foreach($channel in 'github','cnb') {
                $raw=Add-Metadata (New-Raw $channel $false) $channel 899
                $r=Get-StableNormalized $channel $raw $true $true
                if($r.VersionCode -ne 899){throw 'Plan version used instead of remote metadata'}
            }
        }
    }
    Case 'metadata size and digest corruption rejected' {
        & $module {
            $raw=Add-Metadata (New-Raw) github
            function Invoke-StableGhApi { return ,[Text.Encoding]::UTF8.GetBytes('corrupt') }
            $caught=$false
            try { Get-StableNormalized github $raw $true $true | Out-Null } catch {$caught=$true}
            if(!$caught){throw 'Corrupt metadata accepted'}
        }
    }
    Case 'metadata tag, package, channel, integer code and APK evidence rejected when inconsistent' {
        & $module {
            function Invoke-StableGhApi { return ,$script:MetadataBytes }
            foreach($field in 'tag','packageName','channel','versionCode','sha256','size') {
                $raw=Add-Metadata (New-Raw github $false) github
                $meta=[Text.Encoding]::UTF8.GetString($script:MetadataBytes)|ConvertFrom-Json
                $meta.$field=if($field -eq 'size'){13}elseif($field -eq 'versionCode'){'900'}else{'bad'}
                $script:MetadataBytes=[Text.Encoding]::UTF8.GetBytes(($meta|ConvertTo-Json -Compress))
                $raw.assets[1].size=$script:MetadataBytes.Length
                $raw.assets[1].digest='sha256:'+([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($script:MetadataBytes))).ToLowerInvariant()
                $caught=$false
                try {Get-StableNormalized github $raw $true $true|Out-Null}catch{$caught=$true}
                if(!$caught){throw "Inconsistent metadata accepted: $field"}
            }
        }
    }
    Case 'CNB foreign metadata URL and foreign redirects rejected' {
        & $module {
            $raw=Add-Metadata (New-Raw cnb $false) cnb
            $raw.assets[1]|Add-Member browser_download_url 'https://evil.test/update.json'
            $caught=$false
            try{Get-StableNormalized cnb $raw $true $true|Out-Null}catch{$caught=$true}
            if(!$caught){throw 'Foreign metadata URL accepted'}
            $raw.assets[1].PSObject.Properties.Remove('browser_download_url')
            function Invoke-StableHttp { [pscustomobject]@{Status=302;Location='https://evil.test/secret';Bytes=$null} }
            $caught=$false
            try{Get-StableNormalized cnb $raw $true $true|Out-Null}catch{$caught=$true}
            if(!$caught){throw 'Foreign redirect accepted'}
        }
    }
    Case 'storage allowlist excludes suffix tricks, auth, fragment, port, control and HTTP' {
        & $module {
            foreach($url in 'https://asset.cnb.cool/a?signature=fixture','https://bucket.cos.ap.myqcloud.com/a') { if(!(Test-StableCnbStorageUri $url)){throw 'Valid storage rejected'} }
            foreach($url in 'https://asset.cnb.cool.evil.test/a','https://evilmyqcloud.com/a','https://myqcloud.com/a','https://user@asset.cnb.cool/a','http://asset.cnb.cool/a','https://asset.cnb.cool:8443/a','https://asset.cnb.cool/a#x',"https://asset.cnb.cool/a`n",'https://asset.cnb.cool\@evil.test/a','https://asset.cnb.cool/a%0a') { if(Test-StableCnbStorageUri $url){throw 'Unsafe storage accepted'} }
        }
    }
    Case 'confirmation requires fixed repository and current release ID' {
        & $module {
            $prefix='/campus-toolbox/campus-toolbox/-/releases/123/asset-upload-confirmation/a/update.json'
            if((Get-StableCnbConfirmationPath $prefix 123) -cne $prefix){throw 'Valid confirmation rejected'}
            foreach($url in ('https://evil.test'+$prefix),('//evil.test'+$prefix),($prefix -replace '/123/','/456/'),($prefix -replace 'campus-toolbox/campus-toolbox','other/repo'),($prefix+'%2fsecret'),($prefix+'#x'),($prefix+'/../secret')) {
                $caught=$false; try{Get-StableCnbConfirmationPath $url 123|Out-Null}catch{$caught=$true}; if(!$caught){throw 'Unsafe confirmation accepted'}
            }
        }
    }
    Case 'CNB confirmation accepts its encoded object key without decoding the actual request URI' {
        & $module {
            $path='/campus-toolbox/campus-toolbox/-/releases/123/asset-upload-confirmation/testsig/assets%2Frelease%2F2026%2Ffixture.apk?ttl=0'
            if((Get-StableCnbConfirmationPath $path 123 -ExpectedAssetName 'fixture.apk') -cne $path){throw 'Valid encoded object key rejected or altered'}
            Assert-StableCnbApiUri ('https://api.cnb.cool'+$path) POST
            foreach($bad in ($path.Replace('assets%2Frelease','assets%2F%2e%2e')),($path.Replace('%2F','%252F')),($path.Replace('fixture.apk','other.apk'))) {
                $caught=$false;try{Get-StableCnbConfirmationPath $bad 123 -ExpectedAssetName 'fixture.apk'|Out-Null}catch{$caught=$true}
                if(!$caught){throw 'Encoded traversal, double encoding or wrong asset accepted'}
            }
        }
    }
    Case 'API allowlists reject foreign repositories, hosts, DELETE and traversal' {
        & $module {
            foreach($url in 'https://api.cnb.cool/other/repo/-/releases/latest','https://evil.test/campus-toolbox/campus-toolbox/-/releases/latest','https://api.cnb.cool/campus-toolbox/campus-toolbox/-/releases/../latest') {
                $caught=$false;try{Assert-StableCnbApiUri $url GET}catch{$caught=$true};if(!$caught){throw 'Unsafe API URI accepted'}
            }
            $caught=$false;try{Assert-StableCnbApiUri 'https://api.cnb.cool/campus-toolbox/campus-toolbox/-/releases/123' DELETE}catch{$caught=$true};if(!$caught){throw 'DELETE allowed'}
            foreach($path in '/repos/other/repo/releases/latest','https://evil.test/repos/dufan82/campus-toolbox/releases/latest','/repos/dufan82/campus-toolbox/releases/../latest') {
                $caught=$false;try{Invoke-StableGhApi GET $path|Out-Null}catch{$caught=$true};if(!$caught){throw 'Unsafe GH API allowed'}
            }
        }
    }
    Case 'duplicate names and noninteger sizes fail normalization' {
        & $module {
            foreach($bad in 'duplicate','size','flags') {
                $raw=New-Raw
                if($bad -eq 'duplicate'){$raw.assets+= $raw.assets[0]}elseif($bad -eq 'size'){$raw.assets[0].size='12'}else{$raw.draft='false'}
                $caught=$false;try{ConvertTo-StableRelease github $raw|Out-Null}catch{$caught=$true};if(!$caught){throw 'Invalid evidence accepted'}
            }
        }
    }
    Case 'Upload skips identical asset and rejects conflicting hashes without writes' {
        & $module {
            $script:Raw=Add-Metadata (New-Raw) github; Set-PlanFiles $script:Raw
            function Get-StableRawRelease { return $script:Raw }
            function Invoke-StableApi { throw 'Unexpected mutation' }
            $release=ConvertTo-StableRelease github $script:Raw
            $null=Invoke-StableRemote github Upload $script:Plan $release $script:Plan.Files[0]
            $script:Raw.assets[0].digest='sha256:'+('c'*64)
            $caught=$false;try{Invoke-StableRemote github Upload $script:Plan $release $script:Plan.Files[0]|Out-Null}catch{$caught=$_.Exception.Message -match 'AssetConflict'}
            if(!$caught){throw 'Conflict not rejected'}
        }
    }
    Case 'published or wrong-ID release cannot upload or publish' {
        & $module {
            $script:Raw=Add-Metadata (New-Raw) github; Set-PlanFiles $script:Raw
            function Get-StableRawRelease { return $script:Raw }
            foreach($mode in 'published','wrongid') {
                $release=ConvertTo-StableRelease github $script:Raw
                if($mode -eq 'published'){$script:Raw.draft=$false}else{$release.Id='456';$script:Raw.draft=$true}
                foreach($op in 'Upload','Publish') {
                    $caught=$false;try{Invoke-StableRemote github $op $script:Plan $release $script:Plan.Files[0]|Out-Null}catch{$caught=$_.Exception.Message -match 'DraftIdentityMismatch'}
                    if(!$caught){throw 'Non-target draft accepted'}
                }
            }
        }
    }
    Case 'publish PATCH contains only stable flags and verified files precede mutation' {
        & $module {
            $script:Raw=Add-Metadata (New-Raw) github; Set-PlanFiles $script:Raw; $script:Patched=$false
            function Get-StableRawRelease($Channel,$Kind,$Value) { if($Kind -eq 'Latest'){$script:Raw.draft=$false};return $script:Raw }
            function Invoke-StableGhApi { return ,$script:MetadataBytes }
            function Invoke-StableApi($Channel,$Method,$Suffix,$Body) {
                if($Method -ne 'PATCH' -or $Suffix -cne '/123' -or $Body.Count -ne 3 -or $Body.draft -or $Body.prerelease -or $Body.make_latest -cne 'true'){throw 'Bad publish PATCH'}
                $script:Patched=$true
            }
            $release=ConvertTo-StableRelease github $script:Raw
            $r=Invoke-StableRemote github Publish $script:Plan $release
            if(!$script:Patched -or !$r.Latest -or $r.Draft -or $r.VersionCode -ne 900){throw 'Publish result invalid'}
        }
    }
    Case '401 and 403 are nonretryable and HTTP status survives public error sanitization' {
        & $module {
            foreach($status in 401,403,429,503) {
                function Get-StableRawRelease { Stop-Stable ("HTTP"+$status) }
                $caught=$false
                try{Invoke-StableRemote github Latest $script:Plan|Out-Null}catch {
                    $caught=$true
                    if($_.Exception.Data['HttpStatus'] -ne $status -or [bool]$_.Exception.Data['Retryable'] -ne ($status -in @(429,503))){throw 'Retry/status metadata lost'}
                }
                if(!$caught){throw 'HTTP error accepted'}
            }
        }
    }
    Case 'real CNB HTTP layer isolates bearer credentials from storage and metadata' {
        # In-memory handler: no sockets, credential store, fixture files or dependencies.
        if (-not ('StableTransportOfflineHandler' -as [type])) {
            Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
public sealed class StableTransportOfflineHandler : HttpMessageHandler {
    public static List<string> Auth = new List<string>();
    public static int Status = 200;
    public static bool Fail;
    public static bool Timeout;
    public static string Body = "{}";
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage r, CancellationToken c) {
        Auth.Add(r.Headers.Authorization == null ? "none" : r.Headers.Authorization.Scheme);
        if(Fail) throw new Exception("private-fixture-token https://asset.cnb.cool/file?signature=private");
        if(Timeout) throw new TaskCanceledException("private-fixture-token https://asset.cnb.cool/file?signature=private");
        return Task.FromResult(new HttpResponseMessage((HttpStatusCode)Status) { Content = new StringContent(Body) });
    }
}
'@
        }
        $httpModule=New-Module -ArgumentList $source -ScriptBlock {param($Path) . $Path}
        try {
            & $httpModule {
                $script:TokenReads=0
                function Get-StableCnbToken { $script:TokenReads++; return 'offline-sentinel-token-not-a-real-secret' }
                function New-StableHttpClient { [Net.Http.HttpClient]::new([StableTransportOfflineHandler]::new()) }
                [StableTransportOfflineHandler]::Auth.Clear()
                [StableTransportOfflineHandler]::Status=200
                $null=Invoke-StableHttp Api GET 'https://api.cnb.cool/campus-toolbox/campus-toolbox/-/releases/latest'
                $null=Invoke-StableHttp Metadata GET 'https://cnb.cool/campus-toolbox/campus-toolbox/-/releases/download/v9.0.0/update.json'
                $null=Invoke-StableHttp Storage PUT 'https://asset.cnb.cool/fixture?signature=offline' -FilePath $PSCommandPath
                if($script:TokenReads -ne 1 -or ([StableTransportOfflineHandler]::Auth -join ',') -cne 'Bearer,none,none'){throw 'Credential isolation failure'}
                $before=$script:TokenReads
                try{Invoke-StableHttp Api GET 'https://evil.test/releases/latest'|Out-Null}catch{}
                if($script:TokenReads -ne $before){throw 'Credential accessed before URI validation'}
                [StableTransportOfflineHandler]::Fail=$true
                $caught=$false
                try{Invoke-StableHttp Api GET 'https://api.cnb.cool/campus-toolbox/campus-toolbox/-/releases/latest'|Out-Null}catch{
                    $caught=$true
                    if($_.Exception.ToString() -match 'private-fixture|signature='){throw 'HTTP error leaked secret'}
                }
                if(!$caught){throw 'Network failure accepted'}
                [StableTransportOfflineHandler]::Fail=$false
                [StableTransportOfflineHandler]::Timeout=$true
                $caught=$false
                try{Invoke-StableHttp Api GET 'https://api.cnb.cool/campus-toolbox/campus-toolbox/-/releases/latest'|Out-Null}catch{
                    $caught=$true
                    if($_.Exception.Message -cne 'StableTransport/HttpTimeout' -or $_.Exception.Data['Retryable'] -ne $true){throw 'Actual HTTP timeout not safely classified'}
                }
                if(!$caught){throw 'HTTP cancellation accepted'}
                [StableTransportOfflineHandler]::Timeout=$false
                foreach($body in '', 'null', '[]') {
                    [StableTransportOfflineHandler]::Body=$body
                    $caught=$false
                    try{Invoke-StableHttp Api GET 'https://api.cnb.cool/campus-toolbox/campus-toolbox/-/releases/latest'|Out-Null}catch{$caught=$_.Exception.Message -match 'EmptyApiResponse'}
                    if(!$caught){throw 'HTTP 200 empty/null/array mistaken for 404'}
                }
                [StableTransportOfflineHandler]::Body='{}'
            }
        } finally { [StableTransportOfflineHandler]::Fail=$false; [StableTransportOfflineHandler]::Timeout=$false; [StableTransportOfflineHandler]::Body='{}'; Remove-Module $httpModule -Force -ErrorAction SilentlyContinue }
    }
    Case 'GH uses bounded official API process, stdin JSON, and 600-second upload without token export' {
        & $module {
            $script:Requests=@()
            function Invoke-StableGhProcess($Arguments,$InputJson,$TimeoutSeconds) {
                $script:Requests+= [pscustomobject]@{Args=$Arguments;Json=$InputJson;Timeout=$TimeoutSeconds}
                [pscustomobject]@{Status=200;Bytes=[Text.Encoding]::UTF8.GetBytes('{}')}
            }
            $null=Invoke-StableGhApi GET '/repos/dufan82/campus-toolbox/releases/latest'
            $null=Invoke-StableGhApi POST '/repos/dufan82/campus-toolbox/releases' @{draft=$true}
            $null=Invoke-StableGhApi POST 'https://uploads.github.com/repos/dufan82/campus-toolbox/releases/123/assets?name=fixture.apk' -FilePath 'X:\offline\fixture.apk'
            if($script:Requests[0].Timeout -ne 120 -or $script:Requests[2].Timeout -ne 600 -or !$script:Requests[1].Json.Contains('"draft":true') -or $script:Requests[1].Args -notcontains '--input'){throw 'GH bounded invocation invalid'}
            foreach($r in $script:Requests){if($r.Args -contains '--clobber' -or $r.Args -contains 'token' -or $r.Args -contains 'push' -or $r.Args -notcontains 'github.com'){throw 'Unsafe GH command'}}
        }
        $text=[IO.File]::ReadAllText($source)
        Assert ($text.Contains('WaitForExit($TimeoutSeconds*1000)') -and $text.Contains('$process.Kill($true)')) 'Missing hard process timeout'
        Assert ($text.Contains('$handler.AllowAutoRedirect = $false') -and $text.Contains('$handler.UseDefaultCredentials = $false')) 'HTTP redirect/credential policy'
        Assert ($text.Contains("Join-Path `$PSScriptRoot 'CnbCredentialStore.ps1'") -and $text.Contains('Get-CampusCnbToken')) 'CNB fixed credential-store reuse'
        Assert ($text -notmatch 'gh auth token|Set-CampusCnbToken|Remove-CampusCnbToken|WriteAllText|Write-Host|Write-Output') 'Credential export or unexpected output/persistence'
    }
    Case 'missing GetTag returns null; a draft found via GH listing is not recreated' {
        & $module {
            function Invoke-StableApi($Channel,$Method,$Suffix) { if($Suffix.StartsWith('?')){return @()};return $null }
            if($null -ne (Get-StableRawRelease github Tag 'v9.0.0') -or $null -ne (Get-StableRawRelease cnb Tag 'v9.0.0')){throw 'Missing tag not null'}
            function Invoke-StableApi($Channel,$Method,$Suffix) { if($Suffix.StartsWith('?')){return New-Raw};return $null }
            $raw=Get-StableRawRelease github Tag 'v9.0.0'
            if(!$raw.draft -or $raw.id -cne '123'){throw 'GH draft lookup failed'}
        }
    }
    Case 'missing-only Upload rechecks server evidence; CNB PUT precedes confirmation; GH addresses exact ID' {
        & $module {
            foreach($channel in 'github','cnb') {
                $script:Raw=Add-Metadata (New-Raw $channel) $channel; Set-PlanFiles $script:Raw
                $script:UploadedAsset=$script:Raw.assets[0]; $script:Raw.assets=@(); $script:Events=@()
                function Get-StableRawRelease { $script:Events+='GET';return $script:Raw }
                function Get-Item { [pscustomobject]@{PSIsContainer=$false;Length=12;FullName='X:\offline\fixture.apk'} }
                function Get-FileHash { [pscustomobject]@{Hash=$script:Hash} }
                function Invoke-StableApi($Channel,$Method,$Suffix,$Body) {
                    if($Channel -cne 'cnb' -or $Method -cne 'POST' -or $Suffix -cne '/123/asset-upload-url' -or $Body.overwrite -or $Body.size -ne 12){throw 'Unexpected upload URL request'}
                    $script:Events+='URL'
                    [pscustomobject]@{upload_url='https://asset.cnb.cool/fixture?signature=offline';verify_url='/campus-toolbox/campus-toolbox/-/releases/123/asset-upload-confirmation/a/fixture.apk'}
                }
                function Invoke-StableHttp($Mode,$Method,$Address,$Body,$FilePath) {
                    if($Mode -eq 'Storage' -and $Method -eq 'PUT'){$script:Events+='PUT';return}
                    if($Mode -eq 'Api' -and $Method -eq 'POST' -and $Address.Contains('/123/asset-upload-confirmation/')){
                        $script:Events+='CONFIRM';$script:Raw.assets=@($script:UploadedAsset);return
                    }
                    throw 'Unexpected HTTP call'
                }
                function Invoke-StableGhApi($Method,$Path,$Body,$FilePath) {
                    if($Method -cne 'POST' -or $Path -cne 'https://uploads.github.com/repos/dufan82/campus-toolbox/releases/123/assets?name=fixture.apk' -or $FilePath -cne 'X:\offline\fixture.apk'){throw 'Wrong GH upload target'}
                    $script:Events+='UPLOAD';$script:Raw.assets=@($script:UploadedAsset)
                }
                $release=ConvertTo-StableRelease $channel $script:Raw
                $r=Invoke-StableRemote $channel Upload $script:Plan $release $script:Plan.Files[0]
                $expected=if($channel -eq 'cnb'){'GET,URL,PUT,CONFIRM,GET'}else{'GET,UPLOAD,GET'}
                if(($script:Events -join ',') -cne $expected -or $r.Assets[0].Sha256 -cne $script:Hash -or !$r.Draft){throw 'Upload ordering/evidence failure'}
            }
        }
    }
    Case 'bad post-upload evidence fails closed; no publish occurs' {
        & $module {
            $script:Raw=Add-Metadata (New-Raw) github;Set-PlanFiles $script:Raw
            $script:Raw.assets=@();$release=ConvertTo-StableRelease github $script:Raw
            function Get-StableRawRelease { return $script:Raw }
            function Get-Item { [pscustomobject]@{PSIsContainer=$false;Length=12;FullName='X:\offline\fixture.apk'} }
            function Get-FileHash { [pscustomobject]@{Hash=$script:Hash} }
            function Invoke-StableGhApi { return $null }
            $caught=$false;try{Invoke-StableRemote github Upload $script:Plan $release $script:Plan.Files[0]|Out-Null}catch{$caught=$_.Exception.Message -match 'AssetConflict'}
            if(!$caught -or !$script:Raw.draft){throw 'Unconfirmed upload accepted'}
        }
    }
    Case 'Latest 404 alone returns null; existing release without metadata remains rejected' {
        & $module {
            function Get-StableRawRelease { return $null }
            if($null -ne (Invoke-StableRemote github Latest $script:Plan)){throw 'Empty repository not null'}
            function Get-StableRawRelease { New-Raw github $false }
            $caught=$false;try{Invoke-StableRemote github Latest $script:Plan|Out-Null}catch{$caught=$_.Exception.Message.Contains('MetadataMissing')}
            if(!$caught){throw 'Existing release without metadata accepted'}
        }
    }
    Case 'GH and HTTP timeout errors remain sanitized and retryable through public boundary' {
        & $module {
            foreach($code in 'GhTimeout','HttpTimeout') {
                function Get-StableRawRelease { Stop-Stable $code }
                $caught=$false
                try{Invoke-StableRemote github Latest $script:Plan|Out-Null}catch{$caught=$true;if($_.Exception.Data['Retryable'] -ne $true){throw 'Timeout not retryable'}}
                if(!$caught){throw 'Timeout accepted'}
            }
        }
    }
    Case 'draft GetTag downloads GH metadata by authenticated asset ID without redundant Latest request' {
        & $module {
            $script:Raw=Add-Metadata (New-Raw) github 901
            function Get-StableRawRelease($Channel,$Kind) { if($Kind -cne 'Tag'){throw 'Redundant Latest request'};return $script:Raw }
            function Invoke-StableGhApi($Method,$Path,[switch]$Binary) {
                if($Method -cne 'GET' -or $Path -cne '/repos/dufan82/campus-toolbox/releases/assets/8' -or !$Binary){throw 'Metadata not using asset ID'}
                return ,$script:MetadataBytes
            }
            $r=Invoke-StableRemote github GetTag $script:Plan
            if($r.VersionCode -ne 901 -or !$r.Draft -or $r.Latest){throw 'Draft metadata result invalid'}
            $script:Raw.draft=$false
            $r=Invoke-StableRemote github GetTag $script:Plan
            if($r.Latest){throw 'GetTag guessed GH Latest flag'}
        }
    }
    Case 'metadata cache reuses identical digest only after fresh asset evidence and refetches changed SHA' {
        & $module {
            $script:StableTransportMetadataCache=@{}; $script:Downloads=0
            function Invoke-StableGhApi { $script:Downloads++;return ,$script:MetadataBytes }
            $raw=Add-Metadata (New-Raw github $false) github 902
            $r=Get-StableNormalized github $raw $true $true
            $r=Get-StableNormalized github $raw $true $true
            if($script:Downloads -ne 1 -or $r.VersionCode -ne 902){throw 'Identical metadata redownloaded'}
            $raw.assets[0].size=13
            $caught=$false;try{Get-StableNormalized github $raw $true $true|Out-Null}catch{$caught=$true}
            if(!$caught){throw 'Cached metadata hid changed APK evidence'}
            $raw=Add-Metadata (New-Raw github $false) github 903
            $r=Get-StableNormalized github $raw $true $true
            if($script:Downloads -ne 2 -or $r.VersionCode -ne 903){throw 'Changed SHA reused stale metadata'}
        }
    }
    Case 'GH HTTP 200 empty, null and array responses cannot masquerade as missing Latest' {
        & $module {
            foreach($body in '', 'null', '[]') {
                $script:GhBody=$body
                function Invoke-StableGhProcess { [pscustomobject]@{Status=200;Bytes=[Text.Encoding]::UTF8.GetBytes($script:GhBody)} }
                $caught=$false;try{Invoke-StableGhApi GET '/repos/dufan82/campus-toolbox/releases/latest'|Out-Null}catch{$caught=$_.Exception.Message -match 'EmptyApiResponse'}
                if(!$caught){throw 'Empty GH response accepted'}
            }
        }
    }
    Case 'CNB draft GetTag retains asset evidence with null version and never downloads private metadata' {
        & $module {
            $script:Raw=Add-Metadata (New-Raw cnb) cnb
            function Get-StableRawRelease { return $script:Raw }
            function Invoke-StableHttp { throw 'Draft anonymous metadata must never be requested' }
            $r=Invoke-StableRemote cnb GetTag $script:Plan
            if(!$r.Draft -or $null -ne $r.VersionCode -or $r.Assets.Count -ne 2 -or
                $r.Assets[1].Sha256 -cne $script:Raw.assets[1].hash_value -or $r.Assets[1].State -cne 'uploaded') { throw 'Draft evidence lost or version guessed' }
        }
    }
    Case 'CNB draft Publish validates local bytes against server digest then downloads public Latest metadata' {
        & $module {
            $script:Raw=Add-Metadata (New-Raw cnb) cnb;Set-PlanFiles $script:Raw
            $script:LocalReads=0;$script:PublicReads=0;$script:Patched=$false
            $cacheKey='cnb|123|v9.0.0|'+$script:Raw.assets[1].hash_value+'|'+$script:Raw.assets[1].size
            $script:StableTransportMetadataCache=@{}
            $script:StableTransportMetadataCache[$cacheKey]=@{Code=900;AssetName='fixture.apk';Size=12;Sha256=$script:Hash}
            function Get-StableRawRelease { return $script:Raw }
            function Read-StableLocalMetadataBytes($Path) {
                if($Path -cne 'X:\offline\update.json'){throw 'Wrong local metadata path'}
                $script:LocalReads++;return ,$script:MetadataBytes
            }
            function Invoke-StableHttp($Mode,$Method,$Address) {
                if($script:Raw.draft){throw 'Draft anonymous metadata download is unavailable'}
                if($Mode -cne 'Metadata' -or $Method -cne 'GET' -or $Address -cne 'https://cnb.cool/campus-toolbox/campus-toolbox/-/releases/download/v9.0.0/update.json'){throw 'Wrong public metadata request'}
                $script:PublicReads++;return [pscustomobject]@{Status=200;Bytes=$script:MetadataBytes}
            }
            function Invoke-StableApi($Channel,$Method,$Suffix,$Body) {
                if($Channel -cne 'cnb' -or $Method -cne 'PATCH' -or $Suffix -cne '/123' -or $Body.draft -or $Body.prerelease -or $Body.make_latest -cne 'true' -or $script:LocalReads -ne 1){throw 'Unsafe publish mutation'}
                $script:Patched=$true;$script:Raw.draft=$false;$script:Raw.is_latest=$true
            }
            $release=Invoke-StableRemote cnb GetTag $script:Plan
            $r=Invoke-StableRemote cnb Publish $script:Plan $release
            if(!$script:Patched -or $script:LocalReads -ne 1 -or $script:PublicReads -ne 1 -or $r.VersionCode -ne 900 -or !$r.Latest -or $r.Draft){throw 'CNB local/private to public verification failed'}
        }
    }
    Case 'CNB draft rejects wrong local bytes, code, protocol, tag and APK evidence before PATCH despite cache' {
        & $module {
            function Get-StableRawRelease { return $script:Raw }
            function Read-StableLocalMetadataBytes { return ,$script:LocalBytes }
            function Invoke-StableHttp { throw 'Draft anonymous metadata must never be requested' }
            function Invoke-StableApi { $script:Patched=$true;throw 'PATCH must not occur' }
            foreach($fault in 'bytes','code','protocol','tag','apk') {
                $script:Raw=Add-Metadata (New-Raw cnb) cnb
                $meta=[Text.Encoding]::UTF8.GetString($script:MetadataBytes)|ConvertFrom-Json
                switch($fault){'code'{$meta.versionCode=901};'protocol'{$meta.schemaVersion=2};'tag'{$meta.tag='v9.0.1';$meta.versionName='9.0.1'};'apk'{$meta.sha256='b'*64}}
                $script:LocalBytes=[Text.Encoding]::UTF8.GetBytes(($meta|ConvertTo-Json -Compress))
                if($fault -eq 'bytes'){$script:LocalBytes=[Text.Encoding]::UTF8.GetBytes(([Text.Encoding]::UTF8.GetString($script:MetadataBytes).Replace('900','901')))}else{
                    $script:Raw.assets[1].size=$script:LocalBytes.Length
                    $script:Raw.assets[1].hash_value=([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($script:LocalBytes))).ToLowerInvariant()
                }
                Set-PlanFiles $script:Raw;$script:Patched=$false
                $key='cnb|123|v9.0.0|'+$script:Raw.assets[1].hash_value+'|'+$script:Raw.assets[1].size
                $script:StableTransportMetadataCache=@{};$script:StableTransportMetadataCache[$key]=@{Code=900;AssetName='fixture.apk';Size=12;Sha256=$script:Hash}
                $release=ConvertTo-StableRelease cnb $script:Raw
                $expected=switch($fault){'bytes'{'MetadataDigestMismatch'};'code'{'PlanVersionMismatch'};'protocol'{'MetadataIdentityMismatch'};'tag'{'MetadataIdentityMismatch'};'apk'{'MetadataApkMismatch'}}
                $caught=$false
                try{Invoke-StableRemote cnb Publish $script:Plan $release|Out-Null}catch{$caught=$_.Exception.Message -ceq ('StableTransport/'+$expected)}
                if(!$caught -or $script:Patched -or !$script:Raw.draft){throw "Unsafe local metadata accepted: $fault"}
            }
        }
    }
    Case 'bounded local metadata reader preserves exact file bytes without modifying any fixture' {
        $bytes = & $module { param($Path) Read-StableLocalMetadataBytes $Path } $source
        $expected=[IO.File]::ReadAllBytes($source)
        Assert ($bytes.Length -eq $expected.Length -and [Convert]::ToBase64String($bytes) -ceq [Convert]::ToBase64String($expected)) 'Local byte reader changed content'
    }
    Write-Output "StableReleaseTransport: $script:Passed passed; offline only; no remote writes."
} finally { Remove-Module $module -Force -ErrorAction SilentlyContinue }
