# Fedora Pad for Windows: get the newest version and restart it (Update.bat).
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
# Downloads a file and shows how far it is: "Python  42 %  (11.2 / 26.5 MB)"
function Download($url, $out, $label) {
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.UserAgent = 'FedoraPad-setup'
    $resp = $req.GetResponse()
    $total = $resp.ContentLength
    $in = $resp.GetResponseStream()
    $fs = [System.IO.File]::Create($out)
    $buf = New-Object byte[] 262144
    $done = 0; $shown = -1
    try {
        while (($n = $in.Read($buf, 0, $buf.Length)) -gt 0) {
            $fs.Write($buf, 0, $n)
            $done += $n
            if ($total -gt 0) {
                $pct = [int][math]::Floor(100 * $done / $total)
                if ($pct -ne $shown) {
                    Write-Host -NoNewline ("`r    {0}  {1,3} %  ({2:N1} / {3:N1} MB)   " -f $label, $pct, ($done / 1MB), ($total / 1MB))
                    $shown = $pct
                }
            } else {
                Write-Host -NoNewline ("`r    {0}  {1:N1} MB   " -f $label, ($done / 1MB))
            }
        }
    } finally {
        $fs.Close(); $in.Close(); $resp.Close()
    }
    Write-Host ""
}
$Repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Data = Join-Path $env:LOCALAPPDATA 'FedoraPad'
$git = (Get-Command git.exe -ErrorAction SilentlyContinue).Source
if ($git -and (Test-Path (Join-Path $Repo '.git'))) {
    & $git -C $Repo pull --ff-only
} else {
    $zip = Join-Path $env:TEMP 'fedorapad.zip'
    $tmp = Join-Path $env:TEMP 'fedorapad-new'
    try {
        Download 'https://github.com/aliihjt/Android-as-ubuntu-keyboard/archive/refs/heads/main.zip' $zip 'Fedora Pad'
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        Expand-Archive $zip -DestinationPath $tmp -Force
        $src = Get-ChildItem $tmp -Directory | Select-Object -First 1
        Copy-Item (Join-Path $src.FullName '*') $Repo -Recurse -Force
        Remove-Item $zip, $tmp -Recurse -Force -ErrorAction SilentlyContinue
    } catch { Write-Host "Download failed: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
}
# Steam, for the Controller page's "Open Steam Big Picture" option
$steamReg = Get-ItemProperty 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue
if (($steamReg -and $steamReg.SteamExe -and (Test-Path $steamReg.SteamExe)) -or
    (Test-Path "${env:ProgramFiles(x86)}\Steam\steam.exe")) {
    Write-Host 'Steam is installed.'
} else {
    Write-Host 'Installing Steam...'
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        winget install --id Valve.Steam -e --silent --accept-source-agreements --accept-package-agreements | Out-Host
    } else {
        try {
            $steamExe = Join-Path $env:TEMP 'SteamSetup.exe'
            Download 'https://cdn.akamai.steamstatic.com/client/installer/SteamSetup.exe' $steamExe 'Steam'
            Start-Process $steamExe -ArgumentList '/S' -Wait
        } catch { Write-Host "Steam could not be installed: $($_.Exception.Message)" -ForegroundColor Yellow }
    }
}

# iiSU game launcher on the tablet (Controller page > When a controller connects)
$adb = $null
try { $adb = (Get-Content (Join-Path $Data 'tools.json') -Raw | ConvertFrom-Json).adb } catch {}
if ($adb -and (Test-Path $adb) -and (((& $adb get-state 2>$null) | Out-String).Trim() -eq 'device')) {
    if (((& $adb shell pm list packages com.iisulauncher 2>$null) | Out-String) -match 'package:com\.iisulauncher') {
        Write-Host 'iiSU is already on the tablet.'
    } else {
        try {
            $releases = Invoke-RestMethod 'https://api.github.com/repos/iisu-network/iiSU/releases?per_page=5' `
                -Headers @{ 'User-Agent' = 'FedoraPad-setup' } -TimeoutSec 15
            $asset = $releases | ForEach-Object { $_.assets } | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1
            if (-not $asset) { throw 'no APK in the newest releases' }
            $apk = Join-Path $Data 'iiSU.apk'
            Download $asset.browser_download_url $apk "iiSU ($($asset.name))"
            $out = (& $adb install -r $apk 2>&1) | Out-String
            if ($out -match 'Success') { Write-Host 'iiSU installed on the tablet.' -ForegroundColor Green }
            else { Write-Host "iiSU install failed: $out Watch the tablet for 'Install anyway'." -ForegroundColor Yellow }
        } catch {
            Write-Host "iiSU could not be installed ($($_.Exception.Message)). Get it from github.com/iisu-network/iiSU/releases" -ForegroundColor Yellow
        }
    }
} else {
    Write-Host 'Tablet not plugged in: iiSU is checked the next time you run Update with it connected.'
}

$pidFile = Join-Path $Data 'host.pid'
if (Test-Path $pidFile) { Stop-Process -Id (Get-Content $pidFile) -ErrorAction SilentlyContinue }
Write-Host 'Updated. Fedora Pad restarts by itself in a few seconds.' -ForegroundColor Green
