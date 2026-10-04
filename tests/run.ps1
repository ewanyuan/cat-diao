param([string]$Python = '', [string]$JavaBin = '')
$ErrorActionPreference = 'Stop'
if (-not $Python) {
    $runtimePython = Join-Path $env:LOCALAPPDATA '猫叼小窝/runtime/Scripts/python.exe'
    $Python = if (Test-Path -LiteralPath $runtimePython) { $runtimePython } else { (Get-Command python.exe -ErrorAction Stop).Source }
}
if (-not $JavaBin) {
    $JavaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { Split-Path -Parent (Get-Command javac.exe -ErrorAction Stop).Source }
}
$java = Join-Path $JavaBin 'java.exe'
$javac = Join-Path $JavaBin 'javac.exe'
$repository = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $PSScriptRoot 'build/java'
$previousJava = $env:CATDIAO_TEST_JAVA
$previousEncoding = $env:PYTHONIOENCODING
Push-Location $repository
try {
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $sources = @('ComputerConnection', 'LocalBridgeServer', 'PairingGate', 'CaptureBatch') |
        ForEach-Object { 'android/app/src/main/java/com/ewan/wallpaperbridge/' + $_ + '.java' }
    $sources += @('tests/ComputerConnectionTest.java', 'tests/CatDiaoEdgeTest.java')
    & $javac -encoding UTF-8 -d $classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'Java checks could not compile.' }
    foreach ($check in @('CatDiaoEdgeTest', 'ComputerConnectionTest')) {
        & $java -cp $classes ('com.ewan.wallpaperbridge.' + $check)
        if ($LASTEXITCODE -ne 0) { throw "Java check failed: $check" }
    }
    $env:CATDIAO_TEST_JAVA = $java
    $env:PYTHONIOENCODING = 'utf-8'
    & $Python -m unittest discover -s tests -p 'test_*.py' -v
    if ($LASTEXITCODE -ne 0) { throw 'Python checks failed.' }
} finally {
    $env:CATDIAO_TEST_JAVA = $previousJava
    $env:PYTHONIOENCODING = $previousEncoding
    Pop-Location
}
