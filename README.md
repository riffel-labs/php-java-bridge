# PHP/Java Bridge

A clean, generic bridge that lets PHP use Java: any class on the bridge server's classpath, from the JDK
itself to every jar you put in `server/lib/`. A small Java server runs next to your PHP application; the PHP
client sends it calls over a TLS-encrypted local socket and gets the results back as PHP values and objects.

```php
require 'client/JavaBridge.php';

$list = new Java('java.util.ArrayList');
$list->add('hello');
$list->add('world');

echo java('java.lang.System')->getProperty('java.version'), "\n";
print_r(java_values($list));        // Array ( [0] => hello [1] => world )
```

This is a fork of the [PHP/Java Bridge](https://sourceforge.net/projects/php-java-bridge/) 6.2.1 by Jost
Boekemeier, last released in 2010. Upstream had grown into a toolkit for hosting PHP and Java in each other in
every possible way; this fork keeps only the bridge, brings it up to current Java and PHP, and drops the rest.
Release 2.0 also replaces upstream's XML protocol with JSON and adds TLS and certificate authentication.
See [NOTICE.md](NOTICE.md) for where the code comes from.

## How it works

- PHP creates a Java object: `new Java('java.util.HashMap')`. The server creates it, keeps it in a table that
  belongs to the PHP request, and sends back a handle.
- PHP calls methods and reads fields through the handle: `$map->put('a', 1)`. The server finds the method by
  name and argument types with reflection, converts the arguments, calls it, and sends back the result: a PHP
  value (strings, numbers, booleans, arrays) or another handle.
- Static methods and fields go through the class: `java('java.lang.Math')->max(1, 2)`.
- Java exceptions arrive in PHP as `JavaException`, which you can catch.
- When the PHP request ends, the server releases every object of that request.
- Calls and results travel as JSON messages over one connection per PHP worker, kept open between requests and
  encrypted with TLS.

## Architecture

```
PHP worker                                       Bridge server (one JVM)
+---------------------------+                    +--------------------------------------------------+
| your PHP code             |                    | php-java-bridge-server.jar                       |
|   new Java(...), java()   |   TLS connection   |   security provider: TLS, certificate checks     |
| client/JavaBridge.php     | <----------------> |   protocol: JSON messages, object table          |
|   json_encode/json_decode |   JSON messages    |   reflection: finds and calls Java methods       |
|   ext-openssl for TLS     |                    |        |                         |               |
+---------------------------+                    |        | private class loader    | classpath     |
                                                 |        v                         v               |
                                                 |   internal/                  lib/ + the JDK      |
                                                 |   Jackson, Bouncy Castle     your jars: all      |
                                                 |   (never visible to PHP)     visible to PHP      |
                                                 +--------------------------------------------------+
```

What changed in 2.0:

- **JSON instead of XML.** Both directions. The PHP client encodes and decodes whole messages with PHP's
  built-in `json_encode()` and `json_decode()` (no `ext-xml` anymore); the server uses Jackson. See
  [Why JSON](#why-json).
- **Security providers.** Every connection goes through a pluggable provider: TLS with a generated key by
  default, TLS with certificates checked on both sides, or plain. See [Security providers](#security-providers).
- **Internal libraries in their own class loader.** The server's own libraries (Jackson for JSON, Bouncy
  Castle for certificates and PEM keys) live in `server/internal/` and are loaded in a class loader that sees
  only the JDK. PHP looks classes up on the classpath, which never contains them: your jars in `server/lib/`
  can bring their own (other) versions of the same libraries, and PHP code that wants a JSON library adds its
  own jar. See [docs/server.md](docs/server.md#internal-libraries).
- **A connection handshake.** Each new connection starts with a `hello` that checks the protocol version and,
  with certificates, the client's proof of identity. A reused persistent connection skips all of it; each PHP
  request only sends a short `begin` without waiting for an answer. See [docs/protocol.md](docs/protocol.md).

## Why JSON

The XML protocol cost PHP code for every value: the XML parser called back into PHP for each element, and PHP
code decoded hex numbers and base64 strings one by one. With JSON the whole message is one call into C.

| 10,000 elements, end to end | 1.0 (XML) | 2.0 (JSON, TLS) | |
| --- | --- | --- | --- |
| `java_values()` of a list of numbers | 18.4 ms | 1.0 ms | 18x faster |
| `java_values()` of a list of strings | 25.8 ms | 1.6 ms | 16x faster |
| `new Java('java.util.ArrayList', $numbers)` | 7.9 ms | 1.1 ms | 7x faster |
| `new Java('java.util.ArrayList', $strings)` | 9.7 ms | 1.4 ms | 7x faster |

A single call costs about the same as before (0.05 to 0.1 ms; the socket round trip dominates), even with
TLS. Beyond speed:

- **Values arrive intact.** `long` values are exact PHP integers (they were floats, wrong beyond 2^53).
  Doubles round-trip exactly (they were cut to 15 digits). `NaN` and the infinities arrive as such (they were
  `null`). PHP binary strings reach Java byte for byte (they arrived as empty strings). PHP map keys keep their
  order and types.
- **No size limit.** A string or array is limited only by memory; 1.x stopped at about 7.5 MB per result on
  PHP 8.2 and 8.3.
- **Smaller messages.** No markup, decimal numbers instead of hex, UTF-8 strings instead of base64: a list of
  10,000 numbers is 67 KB instead of 355 KB.
- **A simpler client.** One `json_encode()`/`json_decode()` per message instead of a callback-driven parser,
  and the client needs no `ext-xml`.

Details and more numbers: [docs/performance.md](docs/performance.md).

## Security providers

A client the bridge lets in can run any Java code in the server's JVM. The security provider decides who gets
in and whether the traffic can be read. Server and client choose the same one:

| Provider | Encryption | Server authenticated | Client authenticated | Setup |
| --- | --- | --- | --- | --- |
| `tls-anonymous` (default) | TLS | no | no | none: the server makes a key at startup |
| `tls-certificates` | TLS | yes, against a root the client trusts | yes, against a root the server trusts | a private CA: server and client certificates |
| `plain` | none | no | no | none |

```
sh server/start-server.sh                                    # tls-anonymous
sh server/start-server.sh --security tls-certificates \
    --tls-cert server.pem --tls-key server-key.pem --tls-ca ca.pem --tls-allow-client php-client
```

```php
java_configure(['security' => 'tls-certificates', 'tls' => [
    'ca_file' => 'ca.pem', 'cert_file' => 'client.pem', 'key_file' => 'client-key.pem',     // files, or
    // 'ca' => $caPem, 'cert' => $certPem, 'key' => $keyPem,                               // PEM text from memory
]]);
```

- **`tls-anonymous`** encrypts every connection without any setup. It does not prove who is on either end, so
  keep the server on 127.0.0.1 on a machine you trust.
- **`tls-certificates`** is mutual authentication: both sides check the other's certificate against a root
  they trust, and the server can admit only certain client names. Certificates can be files (standard mutual
  TLS) or PEM text held in memory, for keys that must not touch the disk. PHP's TLS layer only takes files, so
  for text the client checks the server's certificate itself right after the handshake, and proves its own
  identity by signing a challenge from the server. Same checks, standard signatures, still encrypted by TLS.
  The server can renew its certificates while it runs.
- **`plain`** turns TLS off, for a trusted loopback setup or PHP without `ext-openssl`.

`--make-demo-certs DIR` writes a demo root, server and client certificate to try it out, and
`examples/security/` has one example per provider. The full story: [docs/security.md](docs/security.md).

## What's left

| Part | What it is |
| --- | --- |
| **Server** `php-java-bridge-server-<version>.jar` | One jar, about 120 KB, Java 21+. A TCP listener (127.0.0.1 by default) with one thread per PHP connection, the security providers, the per-request object table, reflection-based calls, field access and type conversion, logging, a small command line, and `BridgeServer` for starting it inside your own Java application. |
| **Internal libraries** `server/internal/` | The bridge's internal jar (its JSON and certificate code), Jackson core 3 and Bouncy Castle, loaded privately (about 10 MB). |
| **Client** `client/JavaBridge.php` | One PHP file, PHP 8.2+. `new Java()`, `java()`, `java_values()`, `java_is_null()`/`_true()`/`_false()`, `java_instanceof()`, `JavaException`, `foreach` and array access on Java collections, and `java_configure()` for the connection and its security. |

That is the whole project. Which Java classes PHP can use is decided by one thing: the server's classpath.

## What was ripped out

Upstream 6.2.1 shipped three jars and a WAR, about 15,500 lines in its core packages plus servlet and
script-engine packages, a 2,100-line PHP client, and a 70 MB source tree. Removed:

- **Servlet hosting.** Running inside Tomcat or another servlet container: `php-servlet.jar`, the WAR files,
  `web.xml`, and Java sessions and contexts shared with servlets and JSPs.
- **The HTTP transport.** A built-in HTTP server, HTTP tunnelling, redirects between hosts, cookies, chunked
  streams.
- **The XML protocol** (replaced by JSON in 2.0), with its hand-written parser on the Java side and the
  pure-PHP XML parser of the client.
- **Running PHP inside Java.** The JSR 223 script engine (`php-script.jar`), FastCGI connection pools, PHP-CGI
  discovery, and the `launcher.exe`/`launcher.sh` helpers the server wrote to disk at every start, whether
  it used them or not.
- **Callbacks from Java to PHP** (PHP closures implementing Java interfaces), **sessions**, serialization of
  Java objects, the autoloader that turned Java classes into PHP classes, `java_require()` and its relatives.
- **Bundled binaries.** PHP 5 `php-cgi` builds for Windows, Linux, FreeBSD and Solaris, and 178
  third-party jars (Eclipse BIRT, ICU, Derby, iText, POI, Lucene, log4j ...).
- **Security liabilities.** An SSL keystore inside the jar, private key included, with its password in the
  source code. Security manager support (Java 24 removed it). The "promiscuous" switches of the servlet and
  HTTP modes, which accepted connections from other hosts.
- **Legacy plumbing.** Daemon mode, re-launching itself in another JVM, native library loading, a Swing
  start-up dialog, log4j/Chainsaw loggers, `global.properties`, Mono/.NET and named-pipe support, the PHP
  scripts that generated parts of the build.
- **Packaging and extras.** Examples and tests that needed the bundled jars, Debian/RPM/SELinux packaging,
  Eclipse project files.
- **Third-party code.** A base64 encoder adapted from Apache code, replaced by `java.util.Base64`.

Along the way the fork fixed what stopped the bridge from working on current platforms: calls on JDK
objects such as `ArrayList` iterators no longer need `--add-opens`, the client runs warning-free on PHP 8.4,
start-up errors no longer kill the JVM, and Java exceptions returned to PHP are no longer logged as server
errors. [docs/differences-from-upstream.md](docs/differences-from-upstream.md) has the complete list, and
`git diff upstream-6.2.1 main` every line.

## No sessions: keep state in PHP

Java objects live for one PHP request. When the request ends, the server releases them, even though the
connection itself stays open for the next request. There is no Java session, and Java objects cannot be
serialized into a PHP session.

If you need context between PHP executions, keep it on the PHP side: in `$_SESSION`, a database or a
cache. Store plain values (IDs, strings, numbers, arrays from `java_values()`) and rebuild the Java objects
from them in the next request.

The one exception is static state in Java: a singleton or a static field lives as long as the server and is
shared by every PHP request and every PHP worker at once. That makes it right for shared, thread-safe
services (a connection pool, a cache, a scheduler) and wrong for per-user state.

## Quick start

1. Download `php-java-bridge-<version>.zip` from the
   [releases](https://github.com/riffel-labs/php-java-bridge/releases) and unzip it. You need Java 21 or newer
   and PHP 8.2 or newer with `ext-openssl` (for TLS, the default).
2. Start the server:

   ```
   server\start-server.cmd          (Windows)
   sh server/start-server.sh        (Linux, macOS)
   ```

   It listens on `127.0.0.1:9267` with `tls-anonymous` security.
3. In another terminal, run the examples:

   ```
   php examples/hello.php
   php examples/smoke-test.php
   ```

   `smoke-test.php` checks the most important features and prints PASS or FAIL for each.
4. To try it in a browser: `php -S localhost:8000 -t examples`, then open
   <http://localhost:8000/hello-page.php>.

To use your own Java libraries, put their jars in `server/lib/` and restart the server.

The release zip contains:

```
server/   php-java-bridge-server-<version>.jar, start-server.cmd, start-server.sh,
          lib/ (your jars), internal/ (the bridge's own libraries)
client/   JavaBridge.php, the PHP client: one file to include
examples/ hello.php, hello-page.php, smoke-test.php, security/ (one example per security provider)
docs/     the guides below
```

## Performance

Every call whose result PHP uses is a round trip to the server: about 0.05 to 0.1 ms on a local socket, or
10,000 to 20,000 calls a second per PHP worker. The bridge is fast enough for real work as long as PHP does not
chat with Java in tight loops:

- Move data in bulk: `java_values($list)` brings 10,000 elements back in about 1 to 2 ms, and a PHP array of
  10,000 elements passed to a constructor takes about as long. Calling `$list->get($i)` 10,000 times takes
  one to two seconds.
- Move loops into Java: a small helper class in a jar in `server/lib/` does in one call what PHP would need
  thousands of calls for.
- Give the server at least as many threads as you have PHP workers using it (`--threads`): each connected
  worker holds one thread. Too few threads make PHP requests wait.
- Keep the log level at 2 in production.

[docs/performance.md](docs/performance.md) has the measurements and the details.

## Security

A client the server lets in can run any Java code in it. With the default `tls-anonymous` provider the
connection is encrypted, but anyone who can reach the port gets in: the server listens on 127.0.0.1 only and
must not be reachable from other machines or untrusted local users. Use `tls-certificates` when that is not
enough. Read [docs/security.md](docs/security.md) and [SECURITY.md](SECURITY.md) before deploying it.

## Documentation

- [PHP client](docs/php-client.md): configuration, API, type conversion, exceptions
- [Running the server](docs/server.md): options, internal libraries, threads, logging, running as a service
- [Security providers](docs/security.md): what each one protects against, certificates, files or memory
- [Performance](docs/performance.md)
- [Embedding the server](docs/embedding.md) in your own Java application
- [Wire protocol](docs/protocol.md)
- [Differences from upstream 6.2.1](docs/differences-from-upstream.md)
- [Building from source](docs/building.md) and [releasing](docs/releasing.md)

## Building

The build needs a JDK and, once, network access to download the four library jars listed in
`dependencies.txt` from Maven Central (each checked against its SHA-256):

```
./build.sh       ./test.sh       ./package.sh          (Linux, macOS, Git Bash)
.\build.ps1      .\test.ps1      .\package.ps1         (Windows PowerShell)
```

See [docs/building.md](docs/building.md).

## License

MIT, see [LICENSE](LICENSE). The upstream copyright notices are kept; [NOTICE.md](NOTICE.md) explains the
licensing of the upstream parts. The libraries in `server/internal/` keep their own licenses: Jackson
(Apache License 2.0) and Bouncy Castle (the MIT-style Bouncy Castle Licence).
