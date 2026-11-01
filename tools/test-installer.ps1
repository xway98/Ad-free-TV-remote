# Tests the Windows installer end to end inside a temporary folder: install, run the installed copy, uninstall.
# --sandbox keeps it away from the registry, Start menu, startup list and firewall, so it is safe to run anywhere.
# Run windows-agent\build.ps1 first.
$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $repo 'windows-agent\PocketRemotePc-console.exe'
if (-not (Test-Path $exe)) { throw 'Build the Windows app first: windows-agent\build.ps1' }
$root = Join-Path ([IO.Path]::GetTempPath()) ('pocketremote-installer-test-' + [Guid]::NewGuid().ToString('N').Substring(0, 6))
$inst = Join-Path $root 'Program Files Test\Pocket Remote PC'      # spaces on purpose
$data = Join-Path $root 'data'
New-Item -ItemType Directory -Force $root | Out-Null
$script:fail = 0
function Check($name, $ok) { if ($ok) { Write-Host "PASS $name" } else { Write-Host "FAIL $name"; $script:fail++ } }

Write-Host '--- install (silent, sandbox)'
$o = & $exe --silent --sandbox --install-dir "`"$inst`"" --data "`"$data`""
$o | ForEach-Object { "  $_" }
Check 'the installed exe exists' (Test-Path (Join-Path $inst 'PocketRemotePc.exe'))
Check 'it reported INSTALLED' (($o -join ' ') -match 'INSTALLED')

Write-Host '--- run the installed copy as the app (dry)'
$stdout = Join-Path $root 'agent.txt'
$agent = Start-Process -FilePath (Join-Path $inst 'PocketRemotePc.exe') -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout `
    -ArgumentList @('--dry', '--nogui', '--pair', '--data', "`"$data`"", '--port', '47895')
Start-Sleep -Seconds 3
$out = Get-Content $stdout -ErrorAction SilentlyContinue
$out | ForEach-Object { "  $_" }
Check 'it starts and reports protocol 4' (($out -join ' ') -match 'protocol 4')
Check 'it is ready to pair' (($out -join ' ') -match 'READY')
Stop-Process -Id $agent.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 800

Write-Host '--- uninstall (silent, sandbox)'
& (Join-Path $inst 'PocketRemotePc.exe') --uninstall --silent --sandbox --install-dir "`"$inst`"" --data "`"$data`""
Start-Sleep -Seconds 2
Check 'the install folder is removed' (-not (Test-Path $inst))
Check 'the pairing data is removed' (-not (Test-Path $data))
Remove-Item $root -Recurse -Force -ErrorAction SilentlyContinue
if ($script:fail -eq 0) { Write-Host 'ALL PASS' } else { Write-Host "$($script:fail) FAILED"; exit 1 }