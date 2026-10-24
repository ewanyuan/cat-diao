$ErrorActionPreference = 'Stop'
$taskName = 'CatDiao Receiver Recovery'
$entry = Join-Path $PSScriptRoot 'ensure_receiver.py'
if (-not (Test-Path -LiteralPath $entry -PathType Leaf)) { throw 'Receiver starter is missing.' }
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$inRepo = (Test-Path -LiteralPath (Join-Path $repoRoot '.git')) -or (Test-Path -LiteralPath (Join-Path $repoRoot 'data') -PathType Container)
if ($env:CATDIAO_NEST_HOME) { $dataHome = $env:CATDIAO_NEST_HOME }
elseif ($inRepo) { $dataHome = Join-Path $repoRoot 'data' }
else { $dataHome = Join-Path $env:LOCALAPPDATA '猫叼小窝' }
if ($inRepo) { $venvPython = Join-Path $repoRoot '.venv/Scripts/python.exe' } else { $venvPython = Join-Path $dataHome 'runtime/Scripts/python.exe' }
if (-not (Test-Path -LiteralPath $venvPython -PathType Leaf)) { throw 'Cat Diao runtime is missing.' }
# Windows Task Scheduler can reject the venv launcher under a Unicode path.
# Use its installed base interpreter for the tiny stdlib-only recovery helper.
$basePython = & $venvPython -c 'import sys; print(sys._base_executable)'
if ($LASTEXITCODE -ne 0) { throw 'Could not locate the installed Python.' }
$runtime = $basePython
if (-not (Test-Path -LiteralPath $runtime -PathType Leaf)) { throw 'Installed Python is missing.' }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$encodedHome = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($dataHome))
$encodedHome = & $venvPython $entry --home-base64 $encodedHome --resolve-home
if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the existing Cat Diao data directory.' }
$launchArguments = '"' + $entry + '" --home-base64 ' + $encodedHome
$safeExecutable = $runtime.Replace("'", "''")
$safeArguments = $launchArguments.Replace("'", "''")
$command = '$info = New-Object System.Diagnostics.ProcessStartInfo; $info.FileName = ''' + $safeExecutable +
    '''; $info.Arguments = ''' + $safeArguments +
    '''; $info.UseShellExecute = $false; $info.CreateNoWindow = $true; ' +
    '$process = [System.Diagnostics.Process]::Start($info); $process.WaitForExit(); exit $process.ExitCode'
$encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
$action = New-ScheduledTaskAction -Execute (Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe') `
    -Argument ('-NoProfile -WindowStyle Hidden -EncodedCommand ' + $encodedCommand) `
    -WorkingDirectory $PSScriptRoot
$logon = New-ScheduledTaskTrigger -AtLogOn -User $identity
$retry = New-ScheduledTaskTrigger -Once -At ((Get-Date).AddMinutes(1)) `
    -RepetitionInterval (New-TimeSpan -Minutes 1)
$principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 2)
$settings.Hidden = $true
$task = New-ScheduledTask -Action $action -Trigger @($logon, $retry) -Principal $principal -Settings $settings `
    -Description 'Keep the existing Cat Diao receiver available while the current user is signed in.'
Register-ScheduledTask -TaskName $taskName -InputObject $task -Force | Out-Null
Start-ScheduledTask -TaskName $taskName
Write-Output 'Cat Diao receiver recovery is enabled (login and once per minute).'
