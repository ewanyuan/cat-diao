param([switch]$Restart)

$ErrorActionPreference = 'Stop'
$dataHome = Join-Path $env:LOCALAPPDATA '猫叼小窝'
$python = Join-Path $dataHome 'runtime/Scripts/python.exe'
$receiver = Join-Path $PSScriptRoot '收藏同步.py'
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw 'Cat Diao runtime is missing. Run setup.ps1 first.'
}
try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8793/health' -TimeoutSec 2
    if ($health.service -eq 'catdiao-nest' -and -not $Restart) {
        Write-Output 'Cat Diao receiver is already running.'
        return
    }
} catch {
    # The receiver may simply be stopped; inspect the port below.
}
$listener = Get-NetTCPConnection -State Listen -LocalPort 8793 -ErrorAction SilentlyContinue
if ($listener) {
    foreach ($ownerId in @($listener | Select-Object -ExpandProperty OwningProcess -Unique)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $ownerId"
        if ($process -and $process.CommandLine -and $process.CommandLine.Contains($receiver)) {
            if ($Restart) {
                Stop-Process -Id $ownerId -Force
                Start-Sleep -Milliseconds 500
                continue
            }
            Write-Output 'Cat Diao receiver is already running.'
            return
        }
        if ($process -and $process.Name -eq 'CatDiaoNest.exe') {
            Write-Output 'Cat Diao receiver is already running.'
            return
        }
    }
    $remaining = Get-NetTCPConnection -State Listen -LocalPort 8793 -ErrorAction SilentlyContinue
    if ($remaining) {
        throw 'Port 8793 is used by another program. Resolve that conflict before starting Cat Diao.'
    }
}
$logs = Join-Path $dataHome 'logs'
New-Item -ItemType Directory -Path $logs -Force | Out-Null
$env:CATDIAO_NEST_HOME = $dataHome
$env:PYTHONUNBUFFERED = '1'
$launched = Start-Process -FilePath $python -ArgumentList ('"' + $receiver + '" listen') `
    -WorkingDirectory $PSScriptRoot -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logs 'receiver.log') `
    -RedirectStandardError (Join-Path $logs 'receiver-error.log') -PassThru
Start-Sleep -Milliseconds 750
if ($launched.HasExited) {
    throw 'Cat Diao receiver exited during startup. Check the logs in the data directory.'
}
Write-Output 'Cat Diao receiver was started.'
