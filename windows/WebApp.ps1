# Fedora Pad on an iPhone, iPad or any phone, from this Windows PC (home Wi-Fi,
# encrypted, paired devices only). Double-click WebApp.bat.
#   WebApp.bat          turn on and show how to set up a phone
#   WebApp.bat off      turn off
#   WebApp.bat forget   unpair every phone / iPad
param([string]$what = '')
$conf = Join-Path $env:APPDATA 'FedoraPad'
New-Item -ItemType Directory -Force $conf | Out-Null
$flag = Join-Path $conf 'web'
$pidFile = Join-Path $env:LOCALAPPDATA 'FedoraPad\host.pid'
function Restart-Host {
    if (Test-Path $pidFile) { Stop-Process -Id (Get-Content $pidFile) -ErrorAction SilentlyContinue }
}
if ($what -eq 'off') { Remove-Item $flag -ErrorAction SilentlyContinue; Restart-Host; Write-Host 'The web app is off.'; exit }
if ($what -eq 'forget') { Remove-Item (Join-Path $conf 'web-devices.json') -ErrorAction SilentlyContinue
    Write-Host 'All phones and iPads are unpaired.'; exit }
if (-not (Get-Command openssl -ErrorAction SilentlyContinue) -and -not (Test-Path "$env:ProgramFiles\Git\usr\bin\openssl.exe")) {
    Write-Host 'Git (which brings openssl) is missing. Run Setup.bat first.' -ForegroundColor Red; exit 1
}
New-Item -ItemType File -Force $flag | Out-Null
Restart-Host  # FedoraPad.ps1 starts it again within a few seconds, with the web app
Start-Sleep -Seconds 6
$ip = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
       Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } |
       Select-Object -First 1).IPAddress
$url = "http://$($ip):7781"
Write-Host ''
Write-Host 'The Fedora Pad web app is on.' -ForegroundColor Green
Write-Host "On the iPhone / iPad (same Wi-Fi), open this in Safari:  $url"
Write-Host @"
The page walks you through it:
  1. Install the "Fedora Pad local CA" certificate, then trust it in
     Settings > General > About > Certificate Trust Settings.
  2. Open the https link it shows and Share > Add to Home Screen.
  3. Open Fedora Pad from the Home Screen, tap "Show a code on the PC",
     and type the code that pops up on this PC.
If Windows asks whether Python may use the network, allow "Private networks".
"@
