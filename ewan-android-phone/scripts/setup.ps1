$ErrorActionPreference = 'Stop'

$dataHome = Join-Path $env:LOCALAPPDATA '猫叼小窝'
$runtime = Join-Path $dataHome 'runtime'
$python = Join-Path $runtime 'Scripts/python.exe'
New-Item -ItemType Directory -Path $dataHome -Force | Out-Null

if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    $launcher = Get-Command 'python.exe' -ErrorAction SilentlyContinue
    if ($launcher -and $launcher.Source -notmatch 'WindowsApps') {
        & $launcher.Source -m venv $runtime
    }
    if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
        $launcher = Get-Command 'py.exe' -ErrorAction SilentlyContinue
        if ($launcher) { & $launcher.Source -3 -m venv $runtime }
    }
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $python -PathType Leaf)) {
        throw 'Python 3 is required. Install a user-scoped Python runtime and run setup.ps1 again.'
    }
}

if (-not (Test-Path -LiteralPath (Join-Path $runtime 'Lib/site-packages/openpyxl') -PathType Container)) {
    & $python -m pip install --disable-pip-version-check --no-input -r (Join-Path $PSScriptRoot 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Could not install the Excel dependency.' }
}

$oldConfig = Join-Path $env:LOCALAPPDATA '手机直达'
foreach ($name in @('connection.json', 'collector.env')) {
    $source = Join-Path $oldConfig $name
    $destination = Join-Path $dataHome $name
    if ((Test-Path -LiteralPath $source -PathType Leaf) -and -not (Test-Path -LiteralPath $destination)) {
        Copy-Item -LiteralPath $source -Destination $destination
    }
}

$startupFolder = [Environment]::GetFolderPath('Startup')
$oldStartup = Join-Path $startupFolder '手机直达收藏同步.lnk'
$shell = New-Object -ComObject WScript.Shell
if (Test-Path -LiteralPath $oldStartup -PathType Leaf) {
    $oldShortcut = $shell.CreateShortcut($oldStartup)
    if ($oldShortcut.Arguments -match '收藏同步\.py' -and $oldShortcut.Arguments -match '\slisten(?:\s|$)') {
        $oldScript = ''
        if ($oldShortcut.Arguments -match '^"([^"]+收藏同步\.py)"\s+listen(?:\s|$)') {
            $oldScript = $Matches[1]
        }
        if ($oldScript) {
            Get-CimInstance Win32_Process -Filter "Name = 'pythonw.exe'" | ForEach-Object {
                if ($_.CommandLine -and $_.CommandLine.Contains($oldScript)) {
                    Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop
                }
            }
            $oldLedger = Join-Path (Split-Path -Parent $oldScript) '收藏台账'
            $newLedger = Join-Path $dataHome '收藏台账'
            if ((Test-Path -LiteralPath $oldLedger -PathType Container) -and
                -not (Test-Path -LiteralPath $newLedger)) {
                Copy-Item -LiteralPath $oldLedger -Destination $newLedger -Recurse
            }
        }
        Remove-Item -LiteralPath $oldStartup -Force
    }
}

$shortcutPath = Join-Path $startupFolder '猫叼接收.lnk'
$companionExe = Join-Path $env:LOCALAPPDATA 'Programs/CatDiaoNest/CatDiaoNest.exe'
if (-not (Test-Path -LiteralPath $companionExe -PathType Leaf)) {
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $shortcut.Arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + (Join-Path $PSScriptRoot 'start_receiver.ps1') + '"'
    $shortcut.WorkingDirectory = $PSScriptRoot
    $shortcut.Description = 'Receive Cat Diao content after Windows login'
    $shortcut.Save()
}

& (Join-Path $PSScriptRoot 'start_receiver.ps1')
Write-Output ('Cat Diao data: ' + $dataHome)
Write-Output ('Android APK: ' + (Join-Path (Split-Path -Parent $PSScriptRoot) 'assets/cat-diao-android-1.8.apk'))
