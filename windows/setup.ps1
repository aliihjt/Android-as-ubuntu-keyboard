﻿# Fedora Pad for Windows: one-time setup (double-click Setup.bat).
# Gets Python and the Android tools (adb) - with winget when it works, otherwise
# straight from python.org and Google - puts Fedora Pad in your user folder,
# starts it at every log-in, and helps you set up the screen (spacedesk).
# Everything it does is written to %LOCALAPPDATA%\FedoraPad\setup-log.txt
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'  # makes downloads much faster in Windows PowerShell
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Data = Join-Path $env:LOCALAPPDATA 'FedoraPad'
New-Item -ItemType Directory -Force $Data | Out-Null
Start-Transcript -Path (Join-Path $Data 'setup-log.txt') -Force | Out-Null

$Repo = Join-Path $HOME 'Android-as-ubuntu-keyboard'
$RepoUrl = 'https://github.com/aliihjt/Android-as-ubuntu-keyboard.git'
$PyVersion = '3.12.8'
$problems = @()

function Step($m) { Write-Host ""; Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m) { Write-Host "    OK: $m" -ForegroundColor Green }
function Warn($m) { Write-Host "    $m" -ForegroundColor Yellow }
function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
                [Environment]::GetEnvironmentVariable('Path', 'User') + ";$env:LOCALAPPDATA\Microsoft\WinGet\Links"
}
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
$HasWinget = [bool](Get-Command winget -ErrorAction SilentlyContinue)
function Winget-Install($id) {
    if (-not $HasWinget) { return $false }
    Write-Host "    winget install $id ..."
    # Out-Host: show winget's messages without making them this function's result
    winget install --id $id -e --scope user --silent --accept-source-agreements --accept-package-agreements | Out-Host
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189) {  # no per-user installer: the normal one
        winget install --id $id -e --silent --accept-source-agreements --accept-package-agreements | Out-Host
    }
    # 0 = installed, -1978335189 = already installed
    return ($LASTEXITCODE -eq 0 -or $LASTEXITCODE -eq -1978335189)
}

# A real Python (not the Microsoft Store shortcut that only opens the Store)
function Find-Python {
    $found = @()
    foreach ($n in 'pythonw.exe', 'pyw.exe') {
        $c = Get-Command $n -ErrorAction SilentlyContinue
        if ($c -and $c.Source -notlike '*WindowsApps*') { $found += $c.Source }
    }
    foreach ($pattern in "$env:LOCALAPPDATA\Programs\Python\Python3*\pythonw.exe",
                         "$env:ProgramFiles\Python3*\pythonw.exe", "C:\Python3*\pythonw.exe") {
        $found += (Get-ChildItem $pattern -ErrorAction SilentlyContinue | Sort-Object FullName -Descending |
                   ForEach-Object FullName)
    }
    foreach ($p in ($found | Where-Object { $_ })) {
        if ($p -like '*pyw.exe') { return $p }  # the launcher picks the newest Python itself
        $py = Join-Path (Split-Path $p) 'python.exe'
        $v = & $py -c "import sys; print(sys.version_info >= (3, 8))" 2>$null
        if ($v -eq 'True') { return $p }
    }
    return $null
}

Write-Host "Fedora Pad for Windows setup" -ForegroundColor Cyan
if (-not $HasWinget) { Warn 'winget is not on this PC: downloading everything directly instead.' }

# ---------------------------------------------------------------- Python
Step 'Python'
$pyw = Find-Python
if (-not $pyw) {
    if (Winget-Install 'Python.Python.3.12') { Refresh-Path; $pyw = Find-Python }
}
if (-not $pyw) {
    $arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'amd64' }
    $exe = Join-Path $Data "python-$PyVersion-$arch.exe"
    try {
        Download "https://www.python.org/ftp/python/$PyVersion/python-$PyVersion-$arch.exe" $exe "Python $PyVersion"
        Write-Host '    installing (about a minute) ...'
        Start-Process $exe -ArgumentList '/quiet InstallAllUsers=0 PrependPath=1 Include_launcher=1 InstallLauncherAllUsers=0 Include_test=0' -Wait | Out-Null
        Remove-Item $exe -ErrorAction SilentlyContinue
    } catch { Warn "Python download failed: $($_.Exception.Message)" }
    Refresh-Path
    $pyw = Find-Python
}
if ($pyw) { Ok $pyw } else { $problems += 'Python' ; Warn 'Python is missing. Install it from https://www.python.org/downloads/ and run Setup again.' }

# ---------------------------------------------------------------- adb
# Where Google's servers are blocked, adb comes from GitHub instead: first Fedora
# Pad's own copy (its build mirrors Google's official Windows tools, with a
# checksum), then the official scrcpy release (it ships Google's adb), then Google.
Step 'Android tools (adb)'
$adb = $null
$own = Join-Path $Data 'platform-tools\adb.exe'
if (Test-Path $own) { $adb = $own }
if (-not $adb) {
    $c = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($c) { $adb = $c.Source }
}

# The newest release tag of a GitHub project, from the "latest" link (no API needed)
function Latest-Tag($repo, $fallback) {
    try {
        $r = [System.Net.HttpWebRequest]::Create("https://github.com/$repo/releases/latest")
        $r.AllowAutoRedirect = $false
        $r.UserAgent = 'FedoraPad-setup'
        $resp = $r.GetResponse()
        $loc = $resp.Headers['Location']
        $resp.Close()
        if ($loc -match '/tag/(v[0-9.]+)$') { return $Matches[1] }
    } catch {}
    return $fallback
}

# Downloads a zip with adb in it and keeps adb.exe + its two DLLs; $true when it worked
function Get-AdbZip($url, $label, $shaUrl) {
    $zip = Join-Path $Data 'adb-download.zip'
    $tmp = Join-Path $Data 'adb-unpack'
    try {
        Download $url $zip $label
        if ($shaUrl) {
            $want = (New-Object System.Net.WebClient).DownloadString($shaUrl).Trim().ToLower()
            $have = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
            if ($want -ne $have) { Warn "    $label`: the checksum doesn't match, not used"; return $false }
            Write-Host '    checksum OK'
        }
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        Expand-Archive $zip -DestinationPath $tmp -Force
        $exe = Get-ChildItem $tmp -Recurse -Filter adb.exe | Select-Object -First 1
        if (-not $exe) { Warn "    $label`: no adb inside"; return $false }
        $dest = Join-Path $Data 'platform-tools'
        New-Item -ItemType Directory -Force $dest | Out-Null
        Get-ChildItem $exe.DirectoryName -Filter 'Adb*' | Copy-Item -Destination $dest -Force  # adb.exe, AdbWinApi.dll, AdbWinUsbApi.dll
        return (Test-Path $own)
    } catch {
        Warn "    $label failed: $($_.Exception.Message)"
        return $false
    } finally {
        Remove-Item $zip, $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if (-not $adb) {
    $mirror = 'https://github.com/aliihjt/Android-as-ubuntu-keyboard/releases/download/latest-apk'
    $scrcpy = Latest-Tag 'Genymobile/scrcpy' 'v5.0.1'
    $sources = @(
        @("$mirror/platform-tools-windows.zip", 'Android tools (GitHub copy)', "$mirror/platform-tools-windows.sha256"),
        @("https://github.com/Genymobile/scrcpy/releases/download/$scrcpy/scrcpy-win64-$scrcpy.zip", "Android tools (from scrcpy $scrcpy)", $null),
        @('https://dl.google.com/android/repository/platform-tools-latest-windows.zip', 'Android tools (Google)', $null)
    )
    foreach ($src in $sources) {
        if (Get-AdbZip $src[0] $src[1] $src[2]) { $adb = $own; break }
    }
}
if ($adb) { Ok $adb } else { $problems += 'adb' ; Warn 'adb could not be downloaded from GitHub or Google. Check the internet connection (or a VPN) and run Setup again.' }

# ---------------------------------------------------------------- Git (optional)
Step 'Git (optional: easy updates, and the phone web app)'
Refresh-Path
if (-not (Get-Command git.exe -ErrorAction SilentlyContinue)) {
    if (Winget-Install 'Git.Git') { Refresh-Path }
}
$git = (Get-Command git.exe -ErrorAction SilentlyContinue).Source
if ($git) { Ok $git } else { Warn 'skipped: Update.bat will download the newest version instead.' }

# ---------------------------------------------------------------- Fedora Pad itself
Step 'Fedora Pad files'
$here = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (Test-Path (Join-Path $here '.git')) {
    $Repo = $here
} elseif ($git -and (Test-Path (Join-Path $Repo '.git'))) {
    & $git -C $Repo pull --ff-only
} elseif ($git) {
    & $git clone $RepoUrl $Repo
} elseif ($here -ne $Repo) {
    # no Git: keep a copy of the unzipped folder in a fixed place
    New-Item -ItemType Directory -Force $Repo | Out-Null
    Copy-Item (Join-Path $here '*') $Repo -Recurse -Force
}
if (-not (Test-Path (Join-Path $Repo 'windows\fedorapad_host.py'))) {
    $Repo = $here  # clone failed: run from where Setup.bat is
}
Ok $Repo

# ---------------------------------------------------------------- scrcpy (the tablet's screen on this PC)
Step 'Tablet Mirroring (scrcpy, from GitHub)'
$scrcpyExe = Get-ChildItem (Join-Path $Data 'scrcpy') -Recurse -Filter scrcpy.exe -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $scrcpyExe) {
    $tag = Latest-Tag 'Genymobile/scrcpy' 'v5.0.1'
    $zip = Join-Path $Data 'scrcpy.zip'
    try {
        Download "https://github.com/Genymobile/scrcpy/releases/download/$tag/scrcpy-win64-$tag.zip" $zip "scrcpy $tag"
        Expand-Archive $zip -DestinationPath (Join-Path $Data 'scrcpy') -Force
        $scrcpyExe = Get-ChildItem (Join-Path $Data 'scrcpy') -Recurse -Filter scrcpy.exe | Select-Object -First 1
    } catch { Warn "scrcpy download failed: $($_.Exception.Message)" }
    Remove-Item $zip -ErrorAction SilentlyContinue
}
$scrcpy = if ($scrcpyExe) { $scrcpyExe.FullName } else { $null }
if ($scrcpy) { Ok $scrcpy } else { Warn 'skipped: Tablet Mirroring needs it (run Setup again later)' }

# remember the tools for FedoraPad.ps1
$openssl = (Get-Command openssl.exe -ErrorAction SilentlyContinue).Source
foreach ($p in "$env:ProgramFiles\Git\usr\bin\openssl.exe", "$env:LOCALAPPDATA\Programs\Git\usr\bin\openssl.exe") {
    if (-not $openssl -and (Test-Path $p)) { $openssl = $p }
}
@{ python = $pyw; adb = $adb; git = $git; openssl = $openssl; repo = $Repo; scrcpy = $scrcpy } |
    ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $Data 'tools.json')

# ---------------------------------------------------------------- extras
if ($pyw) {
    Step 'Exact volume control (optional)'
    $py = if ($pyw -like '*pyw.exe') { $pyw -replace 'pyw\.exe$', 'py.exe' } else { Join-Path (Split-Path $pyw) 'python.exe' }
    & $py -m pip install --user --disable-pip-version-check --progress-bar on pycaw comtypes
    if ($LASTEXITCODE -eq 0) { Ok 'pycaw' } else { Warn 'skipped (volume then moves in 2 % steps)' }
}

Step 'Persian keyboard'
try {
    $langs = Get-WinUserLanguageList
    if (-not ($langs | Where-Object LanguageTag -like 'fa*')) {
        $a = Read-Host '    Add the Persian keyboard to Windows for the tablet''s فا key? (Y/n)'
        if ($a -ne 'n') {
            $langs.Add('fa-IR')
            Set-WinUserLanguageList $langs -Force
            Ok 'added (switch with Win+Space)'
        }
    } else { Ok 'already there' }
} catch { Warn 'skipped' }

# ---------------------------------------------------------------- start at log-in
if ($pyw -and $adb) {
    Step 'Start Fedora Pad at every log-in'
    $runner = Join-Path $Repo 'windows\FedoraPad.ps1'
    $shell = New-Object -ComObject WScript.Shell
    $lnk = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Startup')) 'Fedora Pad.lnk'))
    $lnk.TargetPath = 'powershell.exe'
    $lnk.Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$runner`""
    $lnk.WindowStyle = 7
    $lnk.Description = 'Fedora Pad: tablet keyboard, trackpad and tools'
    $lnk.Save()
    # Explorer: right-click a file › Send to › Fedora Pad (AirDrop to the tablet / phone)
    $send = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath('SendTo')) 'Fedora Pad.lnk'))
    $send.TargetPath = $pyw
    $send.Arguments = "`"$(Join-Path $Repo 'host\send_to_pad.py')`""
    $send.Description = 'Send to the tablet or phone with Fedora Pad'
    $send.Save()
    if ($scrcpy) {
        # Start menu › Tablet Mirroring (with Fedora Pad's adb, so the two never fight)
        $programs = [Environment]::GetFolderPath('Programs')
        $mir = $shell.CreateShortcut((Join-Path $programs 'Tablet Mirroring.lnk'))
        $mir.TargetPath = 'powershell.exe'
        $mir.Arguments = "-NoProfile -WindowStyle Hidden -Command `"`$env:ADB='$adb'; & '$scrcpy' --window-title='Tablet - Fedora Pad' --stay-awake --max-size=1920`""
        $mir.IconLocation = $scrcpy
        $mir.Description = 'Show and use the tablet in a window (USB cable)'
        $mir.Save()
    }
    Start-Process powershell.exe -ArgumentList "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$runner`"" -WindowStyle Hidden
    Ok 'running now'

    Step 'Second screen (spacedesk)'
    Write-Host @'
    Windows shows its screen on the tablet with spacedesk (free for personal use):
      1. On this PC: install the "spacedesk Windows DRIVER" from the page that opens now.
      2. On the tablet: install "spacedesk" from the Play Store.
      3. Connect them over the same Wi-Fi (not spacedesk's USB mode).
'@
    Start-Process 'https://www.spacedesk.net/#download'
}

Write-Host ""
if ($problems.Count -eq 0) {
    Write-Host 'Done! Plug in the tablet (USB debugging on, tap Allow on the tablet).' -ForegroundColor Green
    Write-Host 'Fedora Pad installs itself on the tablet and opens.'
} else {
    Write-Host "Not finished: $($problems -join ', ') could not be installed (see above)." -ForegroundColor Red
}
Write-Host "Setup log: $Data\setup-log.txt   Fedora Pad log: $Data\fedorapad.log"
Stop-Transcript | Out-Null
