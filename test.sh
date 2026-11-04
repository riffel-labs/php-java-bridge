#!/usr/bin/env bash
# Build, run the server tests, then run the PHP client tests, the security tests and the smoke test against
# live servers. Needs a JDK 21+ ($JAVA_HOME/bin or the PATH) and, for the client tests, PHP 8.2+ with
# ext-openssl (php on the PATH, or set PHP=/path/to/php; extra PHP flags in PHP_FLAGS, for example
# PHP_FLAGS="-c test-php.ini").
set -euo pipefail
cd "$(dirname "$0")"

./build.sh

VERSION="$(tr -d ' \r\n' < VERSION)"
BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
PHP="${PHP:-php}"
PORT="${TEST_PORT:-9399}"
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac

rm -rf build/test-classes
mkdir -p build/test-classes
find server/test -name '*.java' | sort > build/test-sources.txt
"${BIN}javac" --release 21 -encoding UTF-8 -Werror -cp build/classes -d build/test-classes @build/test-sources.txt

echo
echo "== Server tests"
"${BIN}java" -Dphp.java.bridge.internal=dist/internal -cp "build/classes${SEP}build/test-classes" php.java.bridge.ServerTests

if ! command -v "$PHP" > /dev/null 2>&1; then
    echo
    echo "PHP not found; skipping the client tests (set PHP=/path/to/php)"
    exit 0
fi

echo
echo "== Client tests (server on 127.0.0.1:$PORT, security tls-anonymous)"
rm -f build/test-server.log
"${BIN}java" -jar "dist/php-java-bridge-server-$VERSION.jar" --listen "127.0.0.1:$PORT" > build/test-server.log 2>&1 &
SERVER=$!
trap 'kill "$SERVER" 2> /dev/null || true' EXIT

for _ in $(seq 1 50); do
    if grep -q "listening on" build/test-server.log 2> /dev/null; then break; fi
    sleep 0.2
done

# shellcheck disable=SC2086
"$PHP" ${PHP_FLAGS:-} client/tests/run.php "127.0.0.1:$PORT"

echo
echo "== Security tests"
# shellcheck disable=SC2086
JAVA="${BIN}java" BRIDGE_JAR="dist/php-java-bridge-server-$VERSION.jar" "$PHP" ${PHP_FLAGS:-} client/tests/security.php

echo
echo "== Smoke test"
# shellcheck disable=SC2086
JAVA_BRIDGE_ADDRESS="127.0.0.1:$PORT" "$PHP" ${PHP_FLAGS:-} dist-template/examples/smoke-test.php
