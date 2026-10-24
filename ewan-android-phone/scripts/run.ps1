param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $CommandArgs
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$inRepo = (Test-Path -LiteralPath (Join-Path $repoRoot '.git')) -or (Test-Path -LiteralPath (Join-Path $repoRoot 'data') -PathType Container)
if ($env:CATDIAO_NEST_HOME) { $dataHome = $env:CATDIAO_NEST_HOME }
elseif ($inRepo) { $dataHome = Join-Path $repoRoot 'data' }
else { $dataHome = Join-Path $env:LOCALAPPDATA '猫叼小窝' }
if ($inRepo) { $runtime = Join-Path $repoRoot '.venv' } else { $runtime = Join-Path $dataHome 'runtime' }
$python = Join-Path $runtime 'Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw 'Cat Diao is not configured. Run setup.ps1 from this skill first.'
}
if (-not $CommandArgs -or $CommandArgs.Count -lt 2) {
    throw 'Use: run.ps1 phone <command> or run.ps1 collector <command>.'
}
$script = switch ($CommandArgs[0]) {
    'phone' { Join-Path $PSScriptRoot '手机直达.py' }
    'collector' { Join-Path $PSScriptRoot '收藏同步.py' }
    default { throw 'Choose phone or collector.' }
}
$env:CATDIAO_NEST_HOME = $dataHome
& $python $script @($CommandArgs | Select-Object -Skip 1)
exit $LASTEXITCODE
