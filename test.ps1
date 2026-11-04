# Build, run the server tests, then run the PHP client tests, the security tests and the smoke test against
# live servers. Needs a JDK 21+ ($env:JAVA_HOME\bin or the PATH) and, for the client tests, PHP 8.2+ with
# ext-openssl (php on the PATH, or set $env:PHP to php.exe; extra PHP flags in $env:PHP_FLAGS, for example
# "-c test-php.ini").
# Works in Windows PowerShell 5.1 and PowerShell 7.

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location -LiteralPath $root

function Get-Tool([string] $name) {
    if ($env:JAVA_HOME) { return (Join-Path (Join-Path $env:JAVA_HOME 'bin') $name) }
    return $name
}

function Invoke-Native([string] $exe, [string[]] $arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $exe @arguments 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { throw "$exe failed with exit code $code" }
}

& (Join-Path $root 'build.ps1')

$version = (Get-Content -Raw -LiteralPath (Join-Path $root 'VERSION')).Trim()
$port = if ($env:TEST_PORT) { [int] $env:TEST_PORT } else { 9399 }
$jar = "dist/php-java-bridge-server-$version.jar"

if (Test-Path build/test-classes) { Remove-Item -Recurse -Force build/test-classes }
New-Item -ItemType Directory -Force build/test-classes | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java -LiteralPath (Join-Path $root 'server/test') |
    Sort-Object FullName |
    ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
[IO.File]::WriteAllLines((Join-Path $root 'build/test-sources.txt'), [string[]] $sources)
Invoke-Native (Get-Tool 'javac') @('--release', '21', '-encoding', 'UTF-8', '-Werror', '-cp', 'build/classes',
                                   '-d', 'build/test-classes', '@build/test-sources.txt')

Write-Output ''
Write-Output '== Server tests'
Invoke-Native (Get-Tool 'java') @('-Dphp.java.bridge.internal=dist/internal', '-cp', 'build/classes;build/test-classes',
                                  'php.java.bridge.ServerTests')

$php = if ($env:PHP) { $env:PHP } else { 'php' }
if (-not (Get-Command $php -ErrorAction SilentlyContinue)) {
    Write-Output ''
    Write-Output 'PHP not found; skipping the client tests (set $env:PHP to php.exe)'
    exit 0
}
$phpFlags = @()
if ($env:PHP_FLAGS) { $phpFlags = @($env:PHP_FLAGS -split ' ' | Where-Object { $_ }) }

Write-Output ''
Write-Output "== Client tests (server on 127.0.0.1:$port, security tls-anonymous)"
$log = Join-Path $root 'build/test-server.log'
if (Test-Path $log) { Remove-Item -Force $log }
$server = Start-Process -FilePath (Get-Tool 'java') -PassThru -NoNewWindow `
    -ArgumentList @('-jar', $jar, '--listen', "127.0.0.1:$port") `
    -RedirectStandardError $log `
    -RedirectStandardOutput (Join-Path $root 'build/test-server.out')
try {
    $ready = $false
    for ($i = 0; $i -lt 50 -and -not $ready; $i++) {
        if ((Test-Path $log) -and (Select-String -Quiet -Pattern 'listening on' -LiteralPath $log)) {
            $ready = $true
        } else {
            Start-Sleep -Milliseconds 200
        }
    }
    if (-not $ready) { throw "the server did not start; see build/test-server.log" }

    Invoke-Native $php ($phpFlags + @('client/tests/run.php', "127.0.0.1:$port"))

    Write-Output ''
    Write-Output '== Security tests'
    $env:JAVA = Get-Tool 'java'
    $env:BRIDGE_JAR = $jar
    Invoke-Native $php ($phpFlags + @('client/tests/security.php'))

    Write-Output ''
    Write-Output '== Smoke test'
    $env:JAVA_BRIDGE_ADDRESS = "127.0.0.1:$port"
    Invoke-Native $php ($phpFlags + @('dist-template/examples/smoke-test.php'))
} finally {
    if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force -Confirm:$false }
}
