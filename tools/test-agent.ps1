# End-to-end test of the Windows app's network side, in "dry" mode: it injects no input and installs nothing.
# Covers pairing and its refusals, a scripted session, the app and window lists, the clipboard, and the gyro maths.
# Needs a JDK 17 (JAVA_HOME, or javac/java on PATH) and windows-agent\build.ps1 run first.
$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot

function Find-Jdk($name) {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\$name.exe")) { return "$env:JAVA_HOME\bin\$name.exe" }
    $c = Get-Command $name -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    throw "$name not found: install a JDK 17 and set JAVA_HOME"
}
$javac = Find-Jdk 'javac'
$java = Find-Jdk 'java'
$agentExe = Join-Path $repo 'windows-agent\PocketRemotePc-console.exe'
if (-not (Test-Path $agentExe)) { throw 'Build the Windows app first: windows-agent\build.ps1' }

$work = Join-Path ([IO.Path]::GetTempPath()) ('pocketremote-test-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$classes = Join-Path $work 'classes'
$data = Join-Path $work 'agent-data'
$client = Join-Path $work 'client'
$stdout = Join-Path $work 'agent.txt'
New-Item -ItemType Directory -Force $classes | Out-Null

$src = @(Get-ChildItem (Join-Path $repo 'core\src') -Recurse -Filter *.java | ForEach-Object FullName) +
       (Join-Path $repo 'tools\PcCli.java') + (Join-Path $repo 'tools\TvCli.java') + (Join-Path $repo 'tools\AirMathTest.java')
& $javac -encoding UTF-8 -d $classes $src
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$script:fail = 0
function Check($name, $ok) {
    if ($ok) { Write-Host "PASS $name" } else { Write-Host "FAIL $name"; $script:fail++ }
}
function Soft($name, $ok) {            # depends on the machine (Start menu apps, clipboard): reported, never fails the run
    if ($ok) { Write-Host "PASS $name" } else { Write-Host "SKIP $name (not available here)" }
}
function Run($cliArgs) {
    $o = & $java -cp $classes PcCli @cliArgs 2>&1 | ForEach-Object { "$_" }
    return ($o -join "`n")
}

Write-Host '--- gyro maths'
Check 'gyro projection for flat, upright, tilted and leaning holds' ((& $java -cp $classes AirMathTest | Out-String) -match 'ALL PASS')

$agent = Start-Process -FilePath $agentExe -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout `
    -ArgumentList @('--dry', '--nogui', '--pair', '--data', "`"$data`"", '--port', '47891')
try {
    $code = $null
    for ($i = 0; $i -lt 60 -and -not $code; $i++) {
        Start-Sleep -Milliseconds 200
        $m = Select-String -Path $stdout -Pattern 'PAIRING_CODE (\d+)' -ErrorAction SilentlyContinue
        if ($m) { $code = $m.Matches[0].Groups[1].Value }
    }
    if (-not $code) { throw 'the Windows app did not start' }

    Write-Host '--- pairing'
    Check 'a wrong code is refused' ((Run @('pair', $client, '127.0.0.1', '000000')) -match 'wrong code')
    Check 'the right code pairs' ((Run @('pair', $client, '127.0.0.1', $code)) -match 'PAIRED')
    Check 'the code cannot be used twice' ((Run @('pair', "$client-2", '127.0.0.1', $code)) -match 'pairing is not open')
    Check 'a wrong token is refused' ((Run @('bad', $client, '127.0.0.1')) -match 'not paired')
    Check 'a changed certificate is refused' ((Run @('badfp', $client, '127.0.0.1')) -match 'certificate changed')

    Write-Host '--- a scripted session'
    $drive = Run @('drive', $client, '127.0.0.1')
    Check 'signs in and reports the protocol version' ($drive -match 'AGENT VERSION 4')
    Check 'stays connected while idle (pings are answered)' ($drive -match 'OPEN after idle: true')

    Write-Host '--- lists and clipboard'
    Soft 'the Start menu app list, then a rescan' ((Run @('apps', $client, '127.0.0.1')) -match 'APPS refreshed')
    Check 'the open window list' ((Run @('windows', $client, '127.0.0.1')) -match 'WINDOWS received: true')
    Soft 'the clipboard' ((Run @('clip', $client, '127.0.0.1')) -match 'CLIP received: true')

    Start-Sleep -Milliseconds 600
    $log = Get-Content $stdout -Raw
    Check 'a dropped connection releases a held mouse button' ($log -match 'lost Test phone[\s\S]*button 1 up')
}
finally {
    Stop-Process -Id $agent.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 300
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
if ($script:fail -eq 0) { Write-Host 'ALL PASS' } else { Write-Host "$($script:fail) FAILED"; exit 1 }