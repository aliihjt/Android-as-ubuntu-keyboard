# Fedora Pad for Windows: runs in the background from log-in (setup.ps1 adds it
# to Startup). Keeps the host running and, every time the tablet is plugged in:
# connects it over the cable, updates the tablet app and opens Fedora Pad.
$ErrorActionPreference = 'Continue'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Pkg = 'io.github.aliihjt.tabletdesk'
$ApkUrl = 'https://github.com/aliihjt/Android-as-ubuntu-keyboard/releases/download/latest-apk'
$Data = Join-Path $env:LOCALAPPDATA 'FedoraPad'
$Log = Join-Path $Data 'fedorapad.log'
New-Item -ItemType Directory -Force $Data | Out-Null

function Log($m) { "$(Get-Date -Format s)  $m" | Add-Content -Encoding utf8 $Log }

# only one copy at a time
$mutex = New-Object System.Threading.Mutex($false, 'Local\FedoraPadConnect')
if (-not $mutex.WaitOne(0)) { exit }

function Find-Exe($names, $search) {
    foreach ($n in $names) {
        $c = Get-Command $n -ErrorAction SilentlyContinue
        if ($c) { return $c.Source }
    }
    if ($search -and (Test-Path $search)) {
        $f = Get-ChildItem $search -Recurse -Filter $names[0] -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($f) { return $f.FullName }
    }
    return $null
}

$adb = Find-Exe @('adb.exe', 'adb') "$env:LOCALAPPDATA\Microsoft\WinGet\Packages"
$pyw = Find-Exe @('pyw.exe', 'pythonw.exe') $null
if (-not $adb -or -not $pyw) {
    Log "missing tools (adb: $adb, python: $pyw). Run windows\Setup.bat again."
    exit 1
}
Log "started (adb: $adb)"

$hostProc = $null
function Ensure-Host {
    if ($script:hostProc -and -not $script:hostProc.HasExited) { return }
    $script:hostProc = Start-Process $pyw -ArgumentList "`"$Here\fedorapad_host.py`"" -PassThru `
        -RedirectStandardOutput (Join-Path $Data 'host.out') -RedirectStandardError (Join-Path $Data 'host.log')
    Set-Content (Join-Path $Data 'host.pid') $script:hostProc.Id
    Log "host started (pid $($script:hostProc.Id))"
}

function Update-App {
    try { $latest = [int]((Invoke-RestMethod "$ApkUrl/version.txt" -TimeoutSec 10).ToString().Trim()) }
    catch { Log 'could not check for app updates (offline?)'; return }
    $line = (& $adb shell dumpsys package $Pkg 2>$null) | Select-String 'versionCode=(\d+)' | Select-Object -First 1
    if ($line -and [int]$line.Matches[0].Groups[1].Value -ge $latest) { return }
    $apk = Join-Path $Data 'TabletDesk.apk'
    try { Invoke-WebRequest "$ApkUrl/TabletDesk.apk" -OutFile $apk -UseBasicParsing -TimeoutSec 120 }
    catch { Log 'app download failed'; return }
    $out = (& $adb install -r $apk 2>&1) | Out-String
    if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
        & $adb uninstall $Pkg 2>&1 | Out-Null  # very old builds had another signing key
        $out = (& $adb install $apk 2>&1) | Out-String
    }
    if ($out -match 'Success') {
        & $adb shell appops set $Pkg SYSTEM_ALERT_WINDOW allow 2>&1 | Out-Null
        Log "tablet app updated to build $latest"
    } elseif ($out -match 'USER_RESTRICTED|canceled by user') {
        Log "app install blocked by the tablet. Xiaomi/Redmi/POCO: Developer options > Install via USB."
    } else {
        Log "app install failed: $out"
    }
}

$connected = $false
$warned = $false
while ($true) {
    Ensure-Host
    $state = ((& $adb get-state 2>$null) | Out-String).Trim()
    if ($state -eq 'device') {
        if (-not $connected) {
            & $adb reverse tcp:7777 tcp:7777 2>&1 | Out-Null
            Update-App
            & $adb shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
            & $adb shell am start -n "$Pkg/.MainActivity" --ez autostart true 2>&1 | Out-Null
            $connected = $true
            $warned = $false
            Log 'tablet connected'
        } elseif (-not ((& $adb reverse --list 2>$null) -match 'tcp:7777')) {
            & $adb reverse tcp:7777 tcp:7777 2>&1 | Out-Null  # the tablet's adb restarted
        }
    } else {
        if ($connected) { Log 'tablet unplugged' }
        $connected = $false
        if ($state -eq 'unauthorized' -and -not $warned) {
            Log 'on the tablet, tick "Always allow from this computer" and tap Allow'
            $warned = $true
        }
    }
    Start-Sleep -Seconds 3
}
