#!/usr/bin/env bash
# Build the server with nothing but a JDK (21 or newer), curl and sha256sum (or shasum).
# Output: dist/php-java-bridge-server-<version>.jar and dist/internal/ (the internal jar and its libraries).
# The libraries listed in dependencies.txt are downloaded once into build/deps/ and checked against their
# SHA-256 before use. Uses $JAVA_HOME/bin when JAVA_HOME is set, otherwise javac/jar from the PATH.
set -euo pipefail
cd "$(dirname "$0")"

VERSION="$(tr -d ' \r\n' < VERSION)"
BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
JAR_FILE="dist/php-java-bridge-server-$VERSION.jar"
INTERNAL_JAR="dist/internal/php-java-bridge-internal-$VERSION.jar"
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac

sha256() {
    if command -v sha256sum > /dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# ---- dependencies
mkdir -p build/deps
DEPS=()
while read -r file sum url; do
    case "$file" in ''|'#'*) continue ;; esac
    target="build/deps/$file"
    if [ ! -f "$target" ] || [ "$(sha256 "$target")" != "$sum" ]; then
        echo "Downloading $file"
        curl -fsSL -o "$target.part" "$url"
        actual="$(sha256 "$target.part")"
        if [ "$actual" != "$sum" ]; then
            rm -f "$target.part"
            echo "SHA-256 mismatch for $file: expected $sum, got $actual" >&2
            exit 1
        fi
        mv "$target.part" "$target"
    fi
    DEPS+=("$target")
done < dependencies.txt
DEPS_CP="$(IFS="$SEP"; echo "${DEPS[*]}")"

# ---- server jar
rm -rf build/classes build/internal-classes dist/internal
mkdir -p build/classes build/internal-classes dist/internal

find server/src -name '*.java' | sort > build/sources.txt
echo "Compiling $(wc -l < build/sources.txt | tr -d ' ') server source files"
"${BIN}javac" --release 21 -encoding UTF-8 -Xlint:all,-serial,-rawtypes,-unchecked -Werror \
    -d build/classes @build/sources.txt

cat > build/MANIFEST.MF <<EOF
Main-Class: php.java.bridge.Standalone
Implementation-Title: PHP/Java Bridge
Implementation-Version: $VERSION
Implementation-Vendor: riffel-labs/php-java-bridge
EOF

mkdir -p build/classes/META-INF
cp LICENSE NOTICE.md build/classes/META-INF/ 2>/dev/null || true
"${BIN}jar" --create --file "$JAR_FILE" --manifest build/MANIFEST.MF -C build/classes .

# ---- internal jar: compiled against the server classes and the libraries, loaded in its own class loader
find server/internal -name '*.java' | sort > build/internal-sources.txt
echo "Compiling $(wc -l < build/internal-sources.txt | tr -d ' ') internal source files"
"${BIN}javac" --release 21 -encoding UTF-8 -Xlint:all,-serial,-rawtypes,-unchecked -Werror \
    -cp "build/classes$SEP$DEPS_CP" -d build/internal-classes @build/internal-sources.txt
mkdir -p build/internal-classes/META-INF
cp LICENSE build/internal-classes/META-INF/ 2>/dev/null || true
"${BIN}jar" --create --file "$INTERNAL_JAR" -C build/internal-classes .
cp "${DEPS[@]}" dist/internal/

echo "Built $JAR_FILE and dist/internal/ ($(ls dist/internal | wc -l | tr -d ' ') jars)"
