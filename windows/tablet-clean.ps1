# Makes the tablet just Fedora Pad (same as host/tablet-clean.sh on Fedora):
# removes the maker's extra apps (Samsung's Bixby, Game Launcher, AR Emoji,
# preinstalled Facebook/Netflix...) and makes Fedora Pad the home screen.
#   TabletClean.bat          show the list, ask, then remove
#   TabletClean.bat list     only show what would be removed
#   TabletClean.bat undo     put back every app this removed
# The list is host\tablet-clean.txt. Removed apps stay in the tablet's system
# partition: undo (or a factory reset) brings them back. No root.
param([string]$Mode = 'clean')
$ErrorActionPreference = 'Continue'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$List = Join-Path (Split-Path -Parent $Here) 'host\tablet-clean.txt'
$Data = Join-Path $env:LOCALAPPDATA 'FedoraPad'
$Log = Join-Path $Data 'tablet-clean.log'
$Pkg = 'io.github.aliihjt.tabletdesk'
New-Item -ItemType Directory -Force $Data | Out-Null

$tools = @{}
try { (Get-Content (Join-Path $Data 'tools.json') -Raw | ConvertFrom-Json).psobject.Properties | ForEach-Object { $tools[$_.Name] = $_.Value } } catch {}
$adb = if ($tools.adb -and (Test-Path $tools.adb)) { $tools.adb } else { (Get-Command adb.exe -ErrorAction SilentlyContinue).Source }
if (-not $adb) { Write-Host 'adb is missing. Run Setup.bat first.'; exit 1 }

function Adb { (& $adb @args 2>&1 | Out-String).Trim() }

Write-Host 'Waiting for the tablet (plug in the USB-C cable, allow USB debugging)...'
$job = Start-Job { param($a) & $a wait-for-usb-device } -ArgumentList $adb
if (-not (Wait-Job $job -Timeout 60)) { Stop-Job $job; Write-Host 'No tablet found. Check the cable and USB debugging.'; exit 1 }
$maker = Adb shell getprop ro.product.manufacturer
$model = Adb shell getprop ro.product.model
Write-Host "Tablet: $maker $model"

if ($Mode -eq 'undo') {
    if (-not (Test-Path $Log) -or -not (Get-Content $Log)) { Write-Host "Nothing to put back: this hasn't removed anything yet."; exit 0 }
    $left = @()
    foreach ($p in (Get-Content $Log | Where-Object { $_ } | Sort-Object -Unique)) {
        if ((Adb shell cmd package install-existing $p) -match 'installed') { Write-Host "  back: $p" }
        else { Write-Host "  couldn't put back: $p"; $left += $p }
    }
    Set-Content -Encoding utf8 $Log $left
    Write-Host 'Done. The removed apps are back.'
    exit 0
}

$installed = (Adb shell pm list packages --user 0) -split "`r?`n" | ForEach-Object { $_ -replace '^package:', '' }
$junk = @(); $optional = @()
foreach ($line in Get-Content -Encoding utf8 $List) {
    $f = $line -split '\|'
    if ($f.Count -lt 3) { continue }
    $item = [pscustomobject]@{ Group = $f[0].Trim(); Pkg = $f[1].Trim(); What = $f[2].Trim() }
    if ($installed -notcontains $item.Pkg) { continue }
    if ($item.Group -eq 'junk') { $junk += $item } elseif ($item.Group -eq 'optional') { $optional += $item }
}

Write-Host ''
Write-Host "Extra apps found on the tablet: $($junk.Count)"
$junk | ForEach-Object { Write-Host "  - $($_.What)  ($($_.Pkg))" }
if ($optional.Count) { Write-Host "Apps you may still want (asked one by one): $($optional.Count)" }
if ($maker -notmatch 'samsung') { Write-Host "(This list is made for Samsung tablets; on a $maker only the common apps match.)" }
if ($Mode -eq 'list') { $optional | ForEach-Object { Write-Host "  ? $($_.What)  ($($_.Pkg))" }; exit 0 }

$remove = @()
if ($junk.Count -and (Read-Host "Remove these $($junk.Count) apps? [Y/n]") -notmatch '^[Nn]') { $remove += $junk.Pkg }
foreach ($o in $optional) { if ((Read-Host "Remove $($o.What)? [y/N]") -match '^[Yy]') { $remove += $o.Pkg } }

$gone = 0
foreach ($p in $remove) {
    if ($p -eq $Pkg) { continue }  # never Fedora Pad itself
    if ((Adb shell pm uninstall -k --user 0 $p) -match 'Success') {
        Add-Content -Encoding utf8 $Log $p; $gone++; Write-Host "  removed: $p"
    } else { Write-Host "  the tablet kept: $p (it protects this one)" }
}

if ((Adb shell pm path $Pkg) -match 'package:') {
    if ((Adb shell cmd package set-home-activity "$Pkg/.MainActivity") -match 'Success') { Write-Host '  Fedora Pad is now the home screen' }
    else { Write-Host '  To make Fedora Pad the home screen: tablet Settings > Apps > Choose default apps > Home app > Fedora Pad' }
} else {
    Write-Host "  Fedora Pad isn't installed on the tablet yet: plug in the tablet, wait for it, then run this again."
}
Write-Host ''
Write-Host "Done: $gone apps removed. To put them all back: TabletClean.bat undo"
