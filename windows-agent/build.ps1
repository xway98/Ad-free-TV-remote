# Builds PocketRemotePc.exe (the installer and the agent in one file) with the C# compiler that ships with Windows,
# so no SDK is needed. Also builds PocketRemotePc-console.exe, a console twin used by tools\test-agent.ps1.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$fw = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319'

# the app icon: a pink circle with a power symbol, written as a PNG inside an .ico file
$ico = Join-Path $here 'agent.ico'
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap 256, 256
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.Clear([System.Drawing.Color]::Transparent)
$g.FillEllipse((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 53, 210))), 8, 8, 240, 240)
$pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), 22
$pen.StartCap = 'Round'; $pen.EndCap = 'Round'
$g.DrawArc($pen, 68, 76, 120, 120, -50, 280)
$g.DrawLine($pen, 128, 56, 128, 132)
$g.Dispose()
$ms = New-Object System.IO.MemoryStream
$bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
$png = $ms.ToArray()
$bmp.Dispose()
$out = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter $out
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]1)                      # ICONDIR: one image
$w.Write([byte]0); $w.Write([byte]0); $w.Write([byte]0); $w.Write([byte]0)         # 256 x 256, no palette
$w.Write([uint16]1); $w.Write([uint16]32); $w.Write([uint32]$png.Length); $w.Write([uint32]22)
$w.Write($png)
[System.IO.File]::WriteAllBytes($ico, $out.ToArray())

$refs = @('System.dll', 'System.Core.dll', 'System.Drawing.dll', 'System.Windows.Forms.dll', 'System.Security.dll', 'Microsoft.CSharp.dll') | ForEach-Object { "/r:$_" }
$sources = @((Join-Path $here 'Agent.cs'), (Join-Path $here 'Setup.cs'))

& "$fw\csc.exe" /nologo /target:winexe /optimize+ "/win32icon:$ico" "/out:$(Join-Path $here 'PocketRemotePc.exe')" @refs @sources
if ($LASTEXITCODE -ne 0) { throw 'build failed' }
& "$fw\csc.exe" /nologo /target:exe /optimize+ "/win32icon:$ico" "/out:$(Join-Path $here 'PocketRemotePc-console.exe')" @refs @sources
if ($LASTEXITCODE -ne 0) { throw 'console build failed' }
Write-Host "Built $(Join-Path $here 'PocketRemotePc.exe')"
