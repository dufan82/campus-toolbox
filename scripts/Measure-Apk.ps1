param([Parameter(Mandatory=$true)][string]$ApkPath)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskApk=Get-Item -LiteralPath $ApkPath
if ($taskApk.Extension -ne '.apk') { throw 'Expected an APK file.' }
$taskZip=[IO.Compression.ZipFile]::OpenRead($taskApk.FullName)
try {
    $taskParts=@($taskZip.Entries | Group-Object {
        if ($_.FullName -match '^lib/([^/]+)/') { 'native/'+$Matches[1] }
        elseif ($_.FullName -eq 'assets/pingtan-reference.png') { 'campus-reference-image' }
        elseif ($_.FullName -match '^classes\d*\.dex$') { 'dex' }
        elseif ($_.FullName -match '^(res/|resources.arsc)') { 'resources' }
        else { 'other' }
    } | ForEach-Object {
        [PSCustomObject]@{group=$_.Name;rawBytes=[long]($_.Group | Measure-Object Length -Sum).Sum;packedBytes=[long]($_.Group | Measure-Object CompressedLength -Sum).Sum}
    })
    [PSCustomObject]@{
        fileName=$taskApk.Name
        bytes=$taskApk.Length
        MiB=[Math]::Round($taskApk.Length/1MB,3)
        sha256=(Get-FileHash -LiteralPath $taskApk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        parts=$taskParts
    } | ConvertTo-Json -Depth 4
} finally { $taskZip.Dispose() }
