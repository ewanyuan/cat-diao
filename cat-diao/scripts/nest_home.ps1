function Resolve-CatDiaoNest {
    param([string]$RepoRoot)
    if ($env:CATDIAO_NEST_HOME) { return $env:CATDIAO_NEST_HOME }
    $config = Join-Path $env:USERPROFILE '.catdiao\config.json'
    if (Test-Path -LiteralPath $config -PathType Leaf) {
        try {
            $parsed = Get-Content -LiteralPath $config -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($parsed.nest_dir -and -not [string]::IsNullOrWhiteSpace([string]$parsed.nest_dir)) {
                return [string]$parsed.nest_dir
            }
        } catch {
            Write-Output '猫叼小窝配置读不出来，将改用默认位置。'
        }
    }
    $repoData = Join-Path $RepoRoot 'data'
    $inRepo = (Test-Path -LiteralPath (Join-Path $RepoRoot '.git')) -or (Test-Path -LiteralPath $repoData -PathType Container)
    if ($inRepo) { return $repoData }
    return (Join-Path $env:LOCALAPPDATA '猫叼小窝')
}

function Save-CatDiaoNestDir {
    param([string]$NestDir)
    $folder = Join-Path $env:USERPROFILE '.catdiao'
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $payload = @{ nest_dir = $NestDir } | ConvertTo-Json
    $path = Join-Path $folder 'config.json'
    $utf8 = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($path, $payload + "`n", $utf8)
}
