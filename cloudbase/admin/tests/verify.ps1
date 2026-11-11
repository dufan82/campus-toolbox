param([switch]$WithMock, [switch]$WithMockFixtureWrites)
$ErrorActionPreference = 'Stop'
$admin = Split-Path -Parent $PSScriptRoot
$out = Join-Path $PSScriptRoot 'artifacts'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$env:PYTHONUTF8 = '1'
$steps = [System.Collections.Generic.List[object]]::new()
function Invoke-Check([string]$name, [string]$program, [string[]]$arguments) {
    $started = [DateTimeOffset]::Now.ToString('o')
    # Capture native stdout/stderr directly: unittest normally writes to stderr.
    # Avoid PowerShell 5's ErrorRecord conversion and preserve actual raw text.
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = (Get-Command $program -CommandType Application | Select-Object -First 1).Source
    $info.Arguments = ($arguments | ForEach-Object { '"' + $_.Replace('"','\"') + '"' }) -join ' '
    $info.WorkingDirectory = $admin
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = [Text.Encoding]::UTF8
    $info.StandardErrorEncoding = [Text.Encoding]::UTF8
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    $null = $process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $code = $process.ExitCode
    $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
    $process.Dispose()
    $output | Set-Content -LiteralPath (Join-Path $out "$name.txt") -Encoding utf8
    Write-Output $output
    $steps.Add([ordered]@{name=$name; command=(@($program)+$arguments) -join ' '; started=$started; exitCode=$code; rawOutput="$name.txt"})
}
Push-Location -LiteralPath $admin
try {
    Invoke-Check 'node-tests' 'node' @('--test','tests/api.test.mjs','tests/static.test.mjs')
    Invoke-Check 'edge-injected-tests' 'python' @('tests/test_browser.py')
    if ($WithMockFixtureWrites) {
        Invoke-Check 'real-mock-ui-workflow' 'python' @('tests/mock_ui_workflow.py','--allow-local-fixture-writes')
    }
    if ($WithMock -or $WithMockFixtureWrites) { Invoke-Check 'real-mock-smoke' 'python' @('tests/smoke_mock.py') }
    $files = @('index.html','config.js','api.js','app.js','main.js','styles.css','package.json','.gitignore','README.md',
        'tests/api.test.mjs','tests/static.test.mjs','tests/test_browser.py','tests/smoke_mock.py','tests/mock_ui_workflow.py','tests/verify.ps1')
    $hashes = foreach($file in $files) {
        $full = Join-Path $admin $file
        if(Test-Path -LiteralPath $full) {
            [ordered]@{file=$file;sha256=(Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash;bytes=(Get-Item -LiteralPath $full).Length}
        }
    }
    $evidence = Get-ChildItem -LiteralPath $out -File | Where-Object Name -ne 'verification.json' | ForEach-Object {
        [ordered]@{file=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash;bytes=$_.Length}
    }
    $mockProcess = $null
    if ($WithMock -or $WithMockFixtureWrites) {
        $listener = Get-NetTCPConnection -LocalPort 8787 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($listener) {
            $owner = Get-Process -Id $listener.OwningProcess
            $mockProcess = [ordered]@{pid=$owner.Id;startTime=$owner.StartTime.ToString('o');
                scope='Identity of the running local server only; no assertion that backend edits were reloaded'}
        }
    }
    $report = [ordered]@{
        generated=[DateTimeOffset]::Now.ToString('o'); scope='cloudbase/admin only; no cloud deployment';
        originalState='New admin directory; no pre-existing admin files modified; original SHA256 not applicable';
        contractSHA256=(Get-FileHash -LiteralPath (Join-Path $admin '../CONTRACT.md') -Algorithm SHA256).Hash;
        checks=$steps; files=$hashes; evidenceFiles=@($evidence); mockServerProcess=$mockProcess
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $out 'verification.json') -Encoding utf8
} finally { Pop-Location }
if (@($steps | Where-Object exitCode -ne 0).Count -gt 0) { exit 1 }
