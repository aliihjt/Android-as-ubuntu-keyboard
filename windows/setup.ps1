# Fedora Pad for Windows: one-time setup (double-click Setup.bat).
# Installs Python, the Android tools (adb) and Git with winget, gets Fedora Pad,
# starts it at every log-in, and helps you set up the screen (spacedesk).
$ErrorActionPreference = 'Continue'  # git and pip print progress on stderr
$Repo = Join-Path $HOME 'Android-as-ubuntu-keyboard'
$RepoUrl = 'https://github.com/aliihjt/Android-as-ubuntu-keyboard.git'

function Step($m) { Write-Host ""; Write-Host "==> $m" -ForegroundColor Cyan }
function Has($c) { [bool](Get-Command $c -ErrorAction SilentlyContinue) }
function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
                [Environment]::GetEnvironmentVariable('Path', 'User') + ";$env:LOCALAPPDATA\Microsoft\WinGet\Links"
}
function Install($id, $name) {
    Write-Host "    $name..."
    winget install --id $id -e --silent --accept-source-agreements --accept-package-agreements | Out-Null
}

if (-not (Has winget)) {
    Write-Host "winget is missing. Install 'App Installer' from the Microsoft Store, then run Setup again." -ForegroundColor Red
    exit 1
}

Step 'Installing Python, Android tools (adb) and Git'
Refresh-Path
if (-not (Has pyw)) { Install 'Python.Python.3.12' 'Python' }
if (-not (Has adb)) { Install 'Google.PlatformTools' 'Android tools (adb)' }
if (-not (Has git)) { Install 'Git.Git' 'Git' }
Refresh-Path
if (-not (Has pyw)) { throw 'Python did not install. Install it from python.org and run Setup again.' }

Step 'Getting Fedora Pad'
$here = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (Test-Path (Join-Path $here '.git')) {
    $Repo = $here  # running from a clone already
} elseif (Test-Path (Join-Path $Repo '.git')) {
    git -C $Repo pull --ff-only
} else {
    git clone $RepoUrl $Repo
}

Step 'Exact volume control (optional)'
try { py -3 -m pip install --user --quiet --upgrade pycaw comtypes } catch { Write-Host '    skipped' }

Step 'Persian keyboard'
$langs = Get-WinUserLanguageList
if (-not ($langs | Where-Object LanguageTag -like 'fa*')) {
    $a = Read-Host '    Add the Persian keyboard to Windows for the tablet''s فا key? (Y/n)'
    if ($a -ne 'n') {
        $langs.Add('fa-IR')
        Set-WinUserLanguageList $langs -Force
        Write-Host '    Added. Switch with Win+Space.'
    }
} else {
    Write-Host '    already there'
}

Step 'Start Fedora Pad at every log-in'
$runner = Join-Path $Repo 'windows\FedoraPad.ps1'
$startup = [Environment]::GetFolderPath('Startup')
$shell = New-Object -ComObject WScript.Shell
$lnk = $shell.CreateShortcut((Join-Path $startup 'Fedora Pad.lnk'))
$lnk.TargetPath = 'powershell.exe'
$lnk.Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$runner`""
$lnk.WindowStyle = 7
$lnk.Description = 'Fedora Pad: tablet keyboard, trackpad and tools'
$lnk.Save()
Start-Process powershell.exe -ArgumentList "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$runner`"" -WindowStyle Hidden
Write-Host '    running now'

Step 'Second screen (spacedesk)'
Write-Host @'
    Windows shows its screen on the tablet with spacedesk (free for personal use):
      1. On this PC: install the "spacedesk Windows DRIVER" from the page that opens now.
      2. On the tablet: install "spacedesk" from the Play Store
         (Fedora Pad's "Show Screen" button opens it).
      3. Connect them over the same Wi-Fi. The screen appears as a second monitor.
'@
Start-Process 'https://www.spacedesk.net/#download'

Write-Host ""
Write-Host 'Done! Plug in the tablet (USB debugging on, tap Allow on the tablet).' -ForegroundColor Green
Write-Host "Fedora Pad installs itself on the tablet and opens. Log: $env:LOCALAPPDATA\FedoraPad\fedorapad.log"
