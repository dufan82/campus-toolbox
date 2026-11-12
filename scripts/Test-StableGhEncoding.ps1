# Offline real child-process transport test; never invokes gh, credentials or a network.
# Exercise a non-UTF8 parent console as well as a script-in-script caller (the CI style).
$ErrorActionPreference='Stop'
$transport=Join-Path $PSScriptRoot 'StableReleaseTransport.ps1'
$hostPath=(Get-Process -Id $PID).Path
$module=New-Module -ArgumentList $transport,$hostPath -ScriptBlock {
    param($Source,$HostBinary)
    . $Source
    $script:ChildHost=$HostBinary
    function Get-Command { param($Name,$CommandType,$ErrorAction) [pscustomobject]@{Source=$script:ChildHost} }
}
$original=[Console]::InputEncoding
try {
    [Text.Encoding]::RegisterProvider([Text.CodePagesEncodingProvider]::Instance)
    [Console]::InputEncoding=[Text.Encoding]::GetEncoding(936)
    $child=@'
$ErrorActionPreference='Stop'
$raw=[IO.MemoryStream]::new()
[Console]::OpenStandardInput().CopyTo($raw)
try {
    $text=[Text.UTF8Encoding]::new($false,$true).GetString($raw.ToArray())
    $value=$text|ConvertFrom-Json -ErrorAction Stop
    $body=@{name=$value.name}|ConvertTo-Json -Compress
    [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
    [Console]::Write("HTTP/1.1 201 Created`r`nContent-Type: application/json`r`n`r`n"+$body)
}catch{[Console]::Write("HTTP/1.1 400 Bad Request`r`nContent-Type: application/json`r`n`r`n{}")}
'@
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($child))
    foreach($name in @('ASCII fixture','校伴（兼容过渡版）')) {
        $json=@{name=$name;draft=$true}|ConvertTo-Json -Compress
        $result=& $module {param($code,$payload)Invoke-StableGhProcess -Arguments @('-NoProfile','-EncodedCommand',$code) -InputJson $payload} $encoded $json
        $decoded=[Text.UTF8Encoding]::new($false,$true).GetString($result.Bytes)|ConvertFrom-Json
        if($result.Status -ne 201 -or $decoded.name -cne $name){throw 'Child stdin did not preserve UTF-8 JSON'}
    }
    Write-Output 'PASS real child stdin preserves ASCII and Chinese UTF-8 JSON with a GB2312 console'
}finally{[Console]::InputEncoding=$original;Remove-Module $module -Force -ErrorAction SilentlyContinue}
