# Differences from upstream 6.2.1

This fork keeps one way of using the PHP/Java Bridge: a standalone Java server that PHP connects to over a
local TCP connection. Everything else upstream offered was removed, and release 2.0 replaced the wire
protocol and added security. `git diff upstream-6.2.1 main` shows every change line by line.

## Removed

**Hosting and transports**

- Running inside a servlet container (Tomcat and others): `php-servlet.jar`, `PhpJavaServlet`, the WAR
  files, `web.xml`, sessions and contexts shared with servlets and JSPs.
- The HTTP transport: the built-in HTTP server (`HTTP:`/`HTTPS:`/`SERVLET:` socket names), the HTTP tunnel,
  redirects between hosts, chunked streams.
- The XML wire protocol and its parser (2.0; release 1.x still speaks it). See [protocol.md](protocol.md).
- Named pipes and Mono/.NET support.
- The SSL helper and its keystore. Upstream shipped a keystore with a fixed password and private key inside
  `JavaBridge.jar`. Release 2.0 has security providers instead (see [security.md](security.md)).

**Running PHP from Java**

- The JSR 223 script engine (`php-script.jar`, `javax.script` support), FastCGI connection pools, PHP-CGI
  discovery and launching, and the `launcher.exe`/`launcher.sh` helpers the bridge used to write to disk at
  start-up.
- Callbacks from Java to PHP (PHP closures implementing Java interfaces).

**Smaller features**

- Sessions (`java_session`), the servlet/JSR 223 context (`java_context`), `serialize`/`deserialize` of Java
  objects, `java_inspect`, `typeExists` (the Java class autoloader), `java_set_file_encoding`,
  `java_begin_document`/`java_end_document`, `java_last_exception_get`/`clear`, `getPhpMap`, the ping.
- The Java security manager integration, log4j/Chainsaw loggers, `global.properties`, daemon mode,
  re-launching itself with another JVM, loading a native library, the Swing start-up dialog.
- Everything outside the bridge itself: bundled PHP 5 binaries, third-party jars (BIRT, POI, iText, ...),
  examples, tests that needed those, Debian/RPM/SELinux packaging, the PHP build scripts.

## Changed in the server

- **One jar plus private libraries.** `php-java-bridge-server-<version>.jar` replaces `JavaBridge.jar`,
  `php-script.jar` and `php-servlet.jar`. Its libraries (Jackson, Bouncy Castle) are in `server/internal/`,
  loaded in a class loader PHP never sees (see [server.md](server.md#internal-libraries)).
- **Java 21 or newer.** The code was compiled for Java 1.4; it now targets Java 21 and builds without
  warnings.
- **JSON protocol** (2.0): length-prefixed JSON messages in both directions, a `hello` per connection, and
  error replies that tell the client why a connection is closed. See [protocol.md](protocol.md).
- **Security providers** (2.0): `tls-anonymous` (the default), `tls-certificates` with checks on both sides and
  an allow list of client names, and `plain`. See [security.md](security.md).
- **No `--add-opens` needed.** Calling a public method on an object of a non-public JDK class (an
  `ArrayList` iterator, a `HashMap` key set) failed on Java 9 and newer unless the JVM was started with
  `--add-opens`. The server now calls such methods through the public interface or superclass that declares
  them. The upstream fallback for this case never ran: it kept the method it could not call.
- **Command line.** New options `--listen [ADDRESS:]PORT`, `--threads`, `--log-level`, `--log-file`,
  `--security` and the `--tls-*` options, `--make-demo-certs`, `--version`, `--help`. The upstream form
  `INET_LOCAL:PORT [LOGLEVEL [LOGFILE]]` still works. A port alone now means 127.0.0.1, and the server warns
  when it listens on anything else without certificates. The default port is still 9267, the default log
  level is now 2 (errors) instead of 3.
- **Embedding.** `BridgeServer` starts the server inside another application, with a security provider,
  reports statistics, and throws when the port cannot be bound. Upstream called `System.exit()` on any
  start-up error and redirected `System.out` to `System.err` for the whole JVM.
- **Exceptions returned to PHP** are logged only at log levels 3 and 4 (upstream logged their stack traces at
  level 2, as if they were bridge errors). An `IExceptionListener` can receive them.
- **Threads.** Connections are served by a bounded pool (`--threads`, default 20); idle threads end after
  60 seconds.
- **Version.** The server reports its version from the jar manifest.
- **Values.** `long` results are sent exactly (upstream sent them as doubles). A PHP integer passed to an
  `Object` parameter becomes a `Long` when it does not fit an `Integer` (upstream truncated it), and so does an
  integer in a PHP array. PHP arrays with string keys keep their order when they arrive as a `Map`.
- **Setting a primitive field** (for example `$point->x = 5`) when results are sent as values failed with
  `NoSuchFieldException` after setting the field. It now answers null, as for any other field.
- **No copied third-party code.** Upstream's base64 encoder was based on Apache code; it is gone (base64 is
  `java.util.Base64`, JSON is Jackson, unmodified, in `server/internal/`).

## Changed in the PHP client

The client is upstream's `Java.inc`, ported to PHP 8.2+ and cut down to the socket transport. Its public
API is unchanged for everything it still supports. [php-client.md](php-client.md#differences-from-the-upstream-javainc)
lists every difference; in short:

- One file, `client/JavaBridge.php`, included with `require_once`. It needs no `ext-xml` (2.0), and
  `ext-openssl` for TLS.
- Talks to the server over a TCP connection only, encrypted with TLS by default; the HTTP transport, the
  automatic start of a Java process and the pure-PHP XML parser are gone, as are the functions for the removed
  features.
- New `java_configure()` for host, port, security and certificates, persistent or non-persistent connections,
  timeouts, the result mode and a logger. The default address is `127.0.0.1:9267`.
- Values arrive intact: binary strings, exact `long`s and doubles, `NaN` and the infinities, key order.
- Runs without warnings on PHP 8.4. Errors that were fatal (`E_USER_ERROR`) are exceptions.
- The client no longer closes the PHP session at the end of the request.

## Unchanged

- Method selection, argument conversion (apart from the fixes above), and the text of exception messages sent
  to PHP (`Invoke failed: ... Cause: ... VM: ...`).
- The PHP functions and classes, for everything the client still supports.
- The Java package name, `php.java.bridge`.

Release 1.x also kept upstream's wire protocol: its client works with an upstream 6.2.1 server, and a client
built on upstream's `Java.inc` works with its server. Release 2.x does not.
