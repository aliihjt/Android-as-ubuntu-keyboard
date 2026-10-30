# Fedora Pad for Windows: get the newest version and restart it (Update.bat).
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Data = Join-Path $env:LOCALAPPDATA 'FedoraPad'
$git = (Get-Command git.exe -ErrorAction SilentlyContinue).Source
if ($git -and (Test-Path (Join-Path $Repo '.git'))) {
    & $git -C $Repo pull --ff-only
} else {
    Write-Host 'Downloading the newest Fedora Pad ...'
    $zip = Join-Path $env:TEMP 'fedorapad.zip'
    $tmp = Join-Path $env:TEMP 'fedorapad-new'
    try {
        Invoke-WebRequest 'https://github.com/aliihjt/Android-as-ubuntu-keyboard/archive/refs/heads/main.zip' -OutFile $zip -UseBasicParsing
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        Expand-Archive $zip -DestinationPath $tmp -Force
        $src = Get-ChildItem $tmp -Directory | Select-Object -First 1
        Copy-Item (Join-Path $src.FullName '*') $Repo -Recurse -Force
        Remove-Item $zip, $tmp -Recurse -Force -ErrorAction SilentlyContinue
    } catch { Write-Host "Download failed: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
}
$pidFile = Join-Path $Data 'host.pid'
if (Test-Path $pidFile) { Stop-Process -Id (Get-Content $pidFile) -ErrorAction SilentlyContinue }
Write-Host 'Updated. Fedora Pad restarts by itself in a few seconds.' -ForegroundColor Green
