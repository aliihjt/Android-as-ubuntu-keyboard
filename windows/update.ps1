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
$pidFile = Join-Path $Data 'host.pid'
if (Test-Path $pidFile) { Stop-Process -Id (Get-Content $pidFile) -ErrorAction SilentlyContinue }
Write-Host 'Updated. Fedora Pad restarts by itself in a few seconds.' -ForegroundColor Green
