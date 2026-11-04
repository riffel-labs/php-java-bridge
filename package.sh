#!/usr/bin/env bash
# Assemble the release: dist/php-java-bridge-<version>.zip and dist/SHA256SUMS.
# Builds first (see build.sh). The zip is written with the JDK's jar tool.
set -euo pipefail
cd "$(dirname "$0")"

./build.sh

VERSION="$(tr -d ' \r\n' < VERSION)"
BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
NAME="php-java-bridge-$VERSION"
JAR_NAME="php-java-bridge-server-$VERSION.jar"
STAGE="build/package/$NAME"

rm -rf build/package
mkdir -p "$STAGE/server/lib" "$STAGE/server/internal" "$STAGE/client" "$STAGE/examples/security" "$STAGE/docs"

cp README.md LICENSE NOTICE.md SECURITY.md CHANGELOG.md "$STAGE/"
cp "dist/$JAR_NAME" dist-template/server/start-server.cmd dist-template/server/start-server.sh "$STAGE/server/"
cp dist-template/server/lib/README.txt "$STAGE/server/lib/"
cp dist/internal/*.jar dist-template/server/internal/README.txt "$STAGE/server/internal/"
cp client/JavaBridge.php "$STAGE/client/"
cp dist-template/examples/*.php "$STAGE/examples/"
cp dist-template/examples/security/*.php "$STAGE/examples/security/"
cp docs/*.md "$STAGE/docs/"

rm -f "dist/$NAME.zip" dist/SHA256SUMS
"${BIN}jar" --create --no-manifest --file "dist/$NAME.zip" -C build/package "$NAME"

cd dist
FILES=("$NAME.zip" "$JAR_NAME" "internal/php-java-bridge-internal-$VERSION.jar")
if command -v sha256sum > /dev/null 2>&1; then
    sha256sum "${FILES[@]}" | sed 's/ \*/  /' > SHA256SUMS
else
    shasum -a 256 "${FILES[@]}" > SHA256SUMS
fi

echo "Packaged dist/$NAME.zip"
cat SHA256SUMS
