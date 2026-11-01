# Builds everything a user needs into .\dist:
#   PocketRemotePc-Setup.exe   the Windows installer (and, once installed, the laptop app)
#   PocketRemote.apk           the phone app (signed with the Android debug key: fine for sideloading)
#   SHA256SUMS.txt             checksums for both
#
# Needs JDK 17 (JAVA_HOME) and the Android SDK (ANDROID_HOME, or sdk.dir in android\local.properties).
# The Windows part needs nothing installed: it uses the C# compiler that comes with Windows.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null

Write-Host '== Windows app'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'windows-agent\build.ps1')
if ($LASTEXITCODE -ne 0) { throw 'the Windows app build failed' }
Copy-Item (Join-Path $root 'windows-agent\PocketRemotePc.exe') (Join-Path $dist 'PocketRemotePc-Setup.exe') -Force

Write-Host '== Phone app'
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17.' }
Push-Location (Join-Path $root 'android')
try {
    & .\gradlew.bat assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'the phone app build failed' }
} finally { Pop-Location }
Copy-Item (Join-Path $root 'android\app\build\outputs\apk\release\app-release.apk') (Join-Path $dist 'PocketRemote.apk') -Force

Get-ChildItem $dist -File | Where-Object { $_.Name -ne 'SHA256SUMS.txt' } |
    ForEach-Object { '{0}  {1}' -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.Name } |
    Set-Content (Join-Path $dist 'SHA256SUMS.txt') -Encoding ascii
Write-Host ''
Get-ChildItem $dist | Select-Object Name, @{n = 'KB'; e = { [math]::Round($_.Length / 1KB) } } | Format-Table -AutoSize