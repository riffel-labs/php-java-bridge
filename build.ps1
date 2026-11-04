# Build the server with nothing but a JDK (21 or newer).
# Output: dist\php-java-bridge-server-<version>.jar and dist\internal\ (the internal jar and its libraries).
# The libraries listed in dependencies.txt are downloaded once into build\deps\ and checked against their
# SHA-256 before use. Uses $env:JAVA_HOME\bin when JAVA_HOME is set, otherwise javac/jar from the PATH.
# Works in Windows PowerShell 5.1 and PowerShell 7.

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location -LiteralPath $root

function Get-Tool([string] $name) {
    if ($env:JAVA_HOME) { return (Join-Path (Join-Path $env:JAVA_HOME 'bin') $name) }
    return $name
}

# Run a native tool; fail on a non-zero exit code. Its output is passed through as plain text.
function Invoke-Tool([string] $tool, [string[]] $arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & (Get-Tool $tool) @arguments 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { throw "$tool failed with exit code $code" }
}

function Get-Sha256([string] $path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
}

$version = (Get-Content -Raw -LiteralPath (Join-Path $root 'VERSION')).Trim()
$jarFile = "dist/php-java-bridge-server-$version.jar"
$internalJar = "dist/internal/php-java-bridge-internal-$version.jar"

# ---- dependencies
New-Item -ItemType Directory -Force build/deps | Out-Null
$deps = @()
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'dependencies.txt')) {
    $fields = @($line.Trim() -split '\s+' | Where-Object { $_ })
    if ($fields.Count -lt 3 -or $fields[0].StartsWith('#')) { continue }
    $file, $sum, $url = $fields[0], $fields[1], $fields[2]
    $target = Join-Path $root "build/deps/$file"
    if (-not (Test-Path $target) -or (Get-Sha256 $target) -ne $sum) {
        Write-Output "Downloading $file"
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile "$target.part"
        $actual = Get-Sha256 "$target.part"
        if ($actual -ne $sum) {
            Remove-Item -Force "$target.part"
            throw "SHA-256 mismatch for ${file}: expected $sum, got $actual"
        }
        Move-Item -Force "$target.part" $target
    }
    $deps += $target
}

# ---- server jar
foreach ($d in 'build/classes', 'build/internal-classes', 'dist/internal') {
    if (Test-Path $d) { Remove-Item -Recurse -Force $d }
}
New-Item -ItemType Directory -Force build/classes, build/internal-classes, dist/internal | Out-Null

$sources = Get-ChildItem -Recurse -Filter *.java -LiteralPath (Join-Path $root 'server/src') |
    Sort-Object FullName |
    ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
[IO.File]::WriteAllLines((Join-Path $root 'build/sources.txt'), [string[]] $sources)

Write-Output "Compiling $($sources.Count) server source files"
Invoke-Tool 'javac' @('--release', '21', '-encoding', 'UTF-8', '-Xlint:all,-serial,-rawtypes,-unchecked', '-Werror',
                      '-d', 'build/classes', '@build/sources.txt')

$manifest = "Main-Class: php.java.bridge.Standalone`n" +
            "Implementation-Title: PHP/Java Bridge`n" +
            "Implementation-Version: $version`n" +
            "Implementation-Vendor: riffel-labs/php-java-bridge`n"
[IO.File]::WriteAllText((Join-Path $root 'build/MANIFEST.MF'), $manifest)

New-Item -ItemType Directory -Force build/classes/META-INF | Out-Null
foreach ($f in 'LICENSE', 'NOTICE.md') {
    if (Test-Path $f) { Copy-Item $f build/classes/META-INF/ }
}
Invoke-Tool 'jar' @('--create', '--file', $jarFile, '--manifest', 'build/MANIFEST.MF', '-C', 'build/classes', '.')

# ---- internal jar: compiled against the server classes and the libraries, loaded in its own class loader
$internalSources = Get-ChildItem -Recurse -Filter *.java -LiteralPath (Join-Path $root 'server/internal') |
    Sort-Object FullName |
    ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
[IO.File]::WriteAllLines((Join-Path $root 'build/internal-sources.txt'), [string[]] $internalSources)
Write-Output "Compiling $($internalSources.Count) internal source files"
$classpath = (@('build/classes') + $deps) -join ';'
Invoke-Tool 'javac' @('--release', '21', '-encoding', 'UTF-8', '-Xlint:all,-serial,-rawtypes,-unchecked', '-Werror',
                      '-cp', $classpath, '-d', 'build/internal-classes', '@build/internal-sources.txt')
New-Item -ItemType Directory -Force build/internal-classes/META-INF | Out-Null
if (Test-Path LICENSE) { Copy-Item LICENSE build/internal-classes/META-INF/ }
Invoke-Tool 'jar' @('--create', '--file', $internalJar, '-C', 'build/internal-classes', '.')
foreach ($d in $deps) { Copy-Item $d dist/internal/ }

Write-Output "Built $jarFile and dist/internal/ ($((Get-ChildItem dist/internal).Count) jars)"
