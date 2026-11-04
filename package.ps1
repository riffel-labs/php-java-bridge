# Assemble the release: dist\php-java-bridge-<version>.zip and dist\SHA256SUMS.
# Builds first (see build.ps1). The zip is written with the JDK's jar tool.
# Works in Windows PowerShell 5.1 and PowerShell 7.

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location -LiteralPath $root

function Get-Tool([string] $name) {
    if ($env:JAVA_HOME) { return (Join-Path (Join-Path $env:JAVA_HOME 'bin') $name) }
    return $name
}

& (Join-Path $root 'build.ps1')

$version = (Get-Content -Raw -LiteralPath (Join-Path $root 'VERSION')).Trim()
$name = "php-java-bridge-$version"
$jarName = "php-java-bridge-server-$version.jar"
$stage = "build/package/$name"

if (Test-Path build/package) { Remove-Item -Recurse -Force build/package }
New-Item -ItemType Directory -Force "$stage/server/lib", "$stage/server/internal", "$stage/client",
    "$stage/examples/security", "$stage/docs" | Out-Null

Copy-Item README.md, LICENSE, NOTICE.md, SECURITY.md, CHANGELOG.md "$stage/"
Copy-Item "dist/$jarName", dist-template/server/start-server.cmd, dist-template/server/start-server.sh "$stage/server/"
Copy-Item dist-template/server/lib/README.txt "$stage/server/lib/"
Copy-Item dist/internal/*.jar, dist-template/server/internal/README.txt "$stage/server/internal/"
Copy-Item client/JavaBridge.php "$stage/client/"
Copy-Item dist-template/examples/*.php "$stage/examples/"
Copy-Item dist-template/examples/security/*.php "$stage/examples/security/"
Copy-Item docs/*.md "$stage/docs/"

$zip = "dist/$name.zip"
foreach ($f in $zip, 'dist/SHA256SUMS') { if (Test-Path $f) { Remove-Item -Force $f } }

$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& (Get-Tool 'jar') --create --no-manifest --file $zip -C build/package $name 2>&1 | ForEach-Object { "$_" }
$code = $LASTEXITCODE
$ErrorActionPreference = $previous
if ($code -ne 0) { throw "jar failed with exit code $code" }

$lines = foreach ($f in "$name.zip", $jarName, "internal/php-java-bridge-internal-$version.jar") {
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $root "dist/$f")).Hash.ToLowerInvariant()
    "$hash  $f"
}
[IO.File]::WriteAllText((Join-Path $root 'dist/SHA256SUMS'), (($lines -join "`n") + "`n"))

Write-Output "Packaged $zip"
Get-Content dist/SHA256SUMS
