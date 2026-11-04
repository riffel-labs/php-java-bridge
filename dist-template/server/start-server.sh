#!/bin/sh
# Start the PHP/Java Bridge server from the release folder.
#
#   sh server/start-server.sh                          listen on 127.0.0.1:9267
#   sh server/start-server.sh --listen 127.0.0.1:9300 --threads 50 --log-level 3
#
#   sh server/start-server.sh --security tls-certificates --tls-cert server.pem --tls-key server-key.pem --tls-ca ca.pem
#
# Every jar in server/lib/ is on the classpath, so PHP can use its classes. server/internal/ holds
# the bridge's own libraries (JSON, certificates); the bridge loads them separately, PHP never sees
# them. The default security is tls-anonymous (encrypted); --security plain turns TLS off.
# Needs Java 21 or newer: $JAVA_HOME/bin/java, or java on the PATH.
# Extra JVM options (for example -Xmx512m) can be set in JAVA_OPTS.
# Run with --help for all options.

DIR=$(cd "$(dirname "$0")" && pwd)
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR=$(ls "$DIR"/php-java-bridge-server-*.jar 2> /dev/null | head -n 1)

if [ -z "$JAR" ]; then
    echo "php-java-bridge-server-*.jar not found in $DIR" >&2
    exit 1
fi

CP="$JAR:$DIR/lib/*"
case "$(uname -s 2> /dev/null)" in
    MINGW* | MSYS* | CYGWIN*)   # Git Bash or Cygwin on Windows: Java wants Windows paths separated by ';'
        CP="$(cygpath -w "$JAR");$(cygpath -w "$DIR/lib")\\*" ;;
esac

# shellcheck disable=SC2086
exec "$JAVA" $JAVA_OPTS -cp "$CP" php.java.bridge.Standalone "$@"
