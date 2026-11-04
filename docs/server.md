# Running the server

The server is one jar plus its internal libraries in `server/internal/`. It needs Java 21 or newer.

## Starting it

From the release folder:

```
server\start-server.cmd                 (Windows)
sh server/start-server.sh               (Linux, macOS)
```

Both listen on `127.0.0.1:9267` with `tls-anonymous` security unless told otherwise, put every jar in
`server/lib/` on the classpath, and pass their arguments on to the server:

```
sh server/start-server.sh --listen 127.0.0.1:9300 --threads 50 --log-level 3
```

They use `$JAVA_HOME/bin/java` when `JAVA_HOME` is set, otherwise `java` from the `PATH`. JVM options go in
`JAVA_OPTS`, for example `JAVA_OPTS="-Xmx1g -Djava.awt.headless=true"`.

Without the scripts:

```
java -jar server/php-java-bridge-server-2.0.0.jar --listen 127.0.0.1:9267
```

`java -jar` ignores `-cp`. To make your own jars available to PHP, name the main class instead:

```
java -cp "server/php-java-bridge-server-2.0.0.jar:server/lib/*" php.java.bridge.Standalone     (Linux, macOS)
java -cp "server\php-java-bridge-server-2.0.0.jar;server\lib\*" php.java.bridge.Standalone     (Windows)
```

Java expands `lib/*` itself; keep the quotes so the shell does not. Never put `server/internal/` on the
classpath (see below).

When the server is ready it prints one line to standard error:

```
PHP/Java Bridge 2.0.0 listening on 127.0.0.1:9267 (security tls-anonymous, threads 20, log level 2)
```

and, at log level 3 and up, a line with the details: the security setup (for `tls-anonymous` the SHA-256
fingerprint of the certificate it generated), the Java version and the internal library versions.

## Options

| Option | Default | Meaning |
| --- | --- | --- |
| `--listen [ADDRESS:]PORT` | `127.0.0.1:9267` | Address and port to listen on. A port alone stays on 127.0.0.1. IPv6: `[::1]:9267`. Port `0` picks a free port. |
| `--threads N` | `20` | Connections served at once (see below). The system property `php.java.bridge.threads` changes the default. |
| `--log-level N` | `2` | `0` off, `1` fatal, `2` errors, `3` info, `4` debug, `5` every method call. |
| `--log-file FILE` | standard error | Append the log to FILE. |
| `--security PROVIDER` | `tls-anonymous` | `tls-anonymous`, `tls-certificates` or `plain`; see [security.md](security.md). |
| `--tls-cert FILE` | | `tls-certificates`: the server's certificate, then any intermediate certificates (PEM). |
| `--tls-key FILE` | | `tls-certificates`: the server's private key (PEM: PKCS#8, PKCS#1 or SEC1, plain or encrypted). |
| `--tls-key-password-env VAR` | | `tls-certificates`: the environment variable that holds the key's password. |
| `--tls-ca FILE` | | `tls-certificates`: the root certificate(s) client certificates must chain to (PEM). |
| `--tls-allow-client NAME` | any | `tls-certificates`: accept only client certificates with this common name, DNS name or URI (for example `urn:example:audience:bridge`). Repeat for more names. |
| `--make-demo-certs DIR` | | Write a demo root, server and client certificate to DIR and exit (see [security.md](security.md#trying-it-out)). |
| `--version` | | Print the version and exit. |
| `--help` | | Print the options and exit. |

The upstream argument form is accepted too: `INET_LOCAL:PORT [LOGLEVEL [LOGFILE]]` listens on 127.0.0.1,
`INET:PORT` on all interfaces (both with the default security).

The server exits with status 1 if the arguments are invalid, the port cannot be bound (for example because
it is in use) or the key material cannot be read.

A client the server lets in can run any Java code in it. With `tls-anonymous` or `plain`, that is anyone who
can reach the port: keep those on a loopback address. The server prints a warning when you do not. Read
[security.md](security.md) and [SECURITY.md](../SECURITY.md).

## Internal libraries

The server uses Jackson (JSON) and Bouncy Castle (certificates and PEM keys). They live in
`server/internal/` with the bridge's own internal jar, and the bridge loads them in a class loader of their
own, created when the server starts:

```
server/
  php-java-bridge-server-2.0.0.jar   the bridge: sockets, TLS, the protocol, calling Java
  internal/                          the bridge's libraries: PHP never sees these
    php-java-bridge-internal-2.0.0.jar
    jackson-core-3.2.4.jar
    bcprov-jdk18on-1.86.jar, bcutil-jdk18on-1.86.jar, bcpkix-jdk18on-1.86.jar
  lib/                               your jars: everything here is visible to PHP
```

That class loader sees the JDK and nothing else from the server's classpath. PHP looks classes up through
the classpath (the thread's context class loader), which does not contain `internal/`. So:

- `new Java('tools.jackson.core.json.JsonFactory')` fails with `ClassNotFoundException`. If your PHP code
  wants a JSON library, put your own jar in `server/lib/`.
- Your jars may contain other versions of Jackson or Bouncy Castle (or a Jackson 2 that some other library
  needs). They never meet the bridge's copies.
- Bouncy Castle is not registered as a JCA security provider, so it does not change cryptography for the
  rest of the JVM.
- A fixed version of one of these libraries is a file swap in `internal/`: replace the jar with a compatible
  version of the same library and restart. Keep the bridge's own internal jar as it is.

The bridge finds `internal/` next to the server jar. When the server jar is somewhere else (for example
inside an application, see [embedding.md](embedding.md)), set the system property
`-Dphp.java.bridge.internal=DIR`. If the libraries are missing, the server stops at start with a message that
says where it looked.

This keeps libraries apart; it is not a security boundary. Code PHP runs can still reach anything in the
JVM through reflection.

## Threads and connections

Each connection is served by its own thread. A PHP process keeps its connection open between requests, so
every PHP worker that has used the bridge holds one thread for as long as it lives. Set `--threads` to at
least the number of PHP workers that use the bridge (for PHP-FPM, `pm.max_children`), plus some headroom.

When all threads are busy, a new connection waits until a PHP worker exits and releases its thread. The PHP
request waits with it, so too few threads shows up as hanging PHP requests.

A new connection has 20 seconds to finish its TLS handshake and its `hello` (and, with certificates, its
proof); then it is closed. After that, a connection may stay idle as long as the PHP process wants.

Idle threads end after 60 seconds.

## Logging

The log goes to standard error, or to the file given with `--log-file`:

```
Nov 02 11:34:16 JavaBridge ERROR: Connection from /127.0.0.1:53110 refused by tls-anonymous: Unsupported or unrecognized SSL message
```

| Level | What is logged |
| --- | --- |
| 1 | fatal errors |
| 2 | bridge errors: protocol errors, refused connections (failed TLS handshakes, rejected certificates), failed connections, a released object used again |
| 3 | start and stop, and every Java exception returned to PHP (one line each) |
| 4 | debug output, including the stack trace of each exception returned to PHP and every message in both directions (the first 1000 bytes) |
| 5 | every method call and its result |

A Java exception that PHP receives as a `JavaException` is an ordinary result, not a bridge error, so it
is logged only from level 3. Neither is a connection closed before it sent anything, such as a service
monitor's or load balancer's TCP check of the port: it is logged only from level 4.

## Stopping

Ctrl+C, `kill` (SIGTERM) or a service stop shut the server down: it stops accepting connections, closes the
open ones and interrupts calls in progress.

## Running as a service

Any service manager works. Run the server in the foreground and let the manager restart it if it exits.

**systemd:**

```ini
[Unit]
Description=PHP/Java Bridge
After=network.target

[Service]
User=javabridge
WorkingDirectory=/opt/php-java-bridge/server
ExecStart=/usr/bin/java -Xmx512m -Djava.awt.headless=true \
    -cp /opt/php-java-bridge/server/php-java-bridge-server-2.0.0.jar:/opt/php-java-bridge/server/lib/* \
    php.java.bridge.Standalone --listen 127.0.0.1:9267 --threads 50
Restart=on-failure
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

**Windows, with [WinSW](https://github.com/winsw/winsw)** (an XML file next to the WinSW executable in the
`server` folder):

```xml
<service>
  <id>php-java-bridge</id>
  <name>PHP/Java Bridge</name>
  <executable>%JAVA_HOME%\bin\java.exe</executable>
  <arguments>-Xmx512m -Djava.awt.headless=true -cp "%BASE%\php-java-bridge-server-2.0.0.jar;%BASE%\lib\*" php.java.bridge.Standalone --listen 127.0.0.1:9267 --threads 50</arguments>
  <log mode="roll" />
</service>
```

Run the service under an account that can access only what the Java code needs.

## Java notes

- **Headless servers.** Code that uses `java.awt` classes (images, fonts) needs `-Djava.awt.headless=true`
  on a machine without a display.
- **No `--add-opens` needed.** When PHP calls a public method on an object whose class is not public (for
  example the iterator of an `ArrayList`), the server calls it through the public interface or superclass
  that declares the method.
- **Memory.** Every object PHP creates stays alive until PHP releases it or the PHP request ends. Long PHP
  requests that create many objects need heap to match.
- **Custom runtimes.** A runtime made with `jlink` needs `java.base` and `java.desktop` (the bridge uses
  `java.beans` for properties), on Java 21 also `jdk.crypto.ec` (EC keys for TLS; part of `java.base` from
  Java 22), plus whatever your own Java code needs.
