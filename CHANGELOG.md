# Changelog

## 2.0.0 (2026-11-04)

A new wire protocol and security. A 2.x client talks only to a 2.x server.

**Protocol**

- JSON instead of XML, in both directions: length-prefixed messages, one `json_encode()`/`json_decode()` per
  message in PHP, Jackson in Java. Bulk transfers are 6 to 18 times faster end to end (10,000 elements:
  `java_values()` 1 to 2 ms instead of 18 to 26 ms); single calls cost the same.
- A `hello` per connection (protocol version check, client proof), a `begin` per PHP request without a reply,
  and error replies that tell the client why the server closes a connection.
- Values arrive intact: exact `long` values (PHP ints), exact doubles, `NaN` and the infinities, binary PHP
  strings, PHP map key order. No size limit for strings or arrays beyond memory (1.x: about 7.5 MB per
  result on PHP 8.2 and 8.3).

**Security**

- Security providers: `tls-anonymous` (default; TLS with a key generated at startup), `tls-certificates`
  (TLS; server and client certificates checked against trusted roots; allow list of client names) and
  `plain`.
- Certificates as files (standard mutual TLS) or as PEM text in memory: the client then checks the server
  certificate itself and proves its identity by signing a challenge. Credentials from a callable (PHP) or a
  supplier (Java), so keys can come from a secret store and server certificates can be renewed while running.
- A certificate's names are its common name and the DNS names and URIs among its subject alternative names:
  the server's allow list (`--tls-allow-client`, `BridgeSecurity.tlsCertificates(..., names)`) and the
  client's `peer_name` match any of them. Certificates can name a service and the services it may call
  (`urn:example:service:web`, `urn:example:audience:bridge`) instead of hosts, for servers whose host names and
  addresses are not known in advance. See [docs/security.md](docs/security.md#service-names-instead-of-host-names).
  With `ca_file`, a `peer_name` that is a URI is checked by the client after the handshake, as PHP's TLS layer
  matches host names only.
- `--make-demo-certs DIR` writes a demo root, server and client certificate, each also named by a URI
  (`urn:php-java-bridge:demo:server`, `urn:php-java-bridge:demo:client`).
- Before a client is let in, the server accepts messages of at most 64 KB and gives it 20 seconds.

**Server**

- The internal libraries (Jackson core 3.2.4, Bouncy Castle 1.86) live in `server/internal/` and are loaded
  in a class loader of their own, never visible to PHP or to the jars in `server/lib/`.
- New options `--security`, `--tls-cert`, `--tls-key`, `--tls-key-password-env`, `--tls-ca`,
  `--tls-allow-client`, `--make-demo-certs`; `BridgeServer.Builder.security(...)`, `BridgeSecurity`,
  `TlsCredentials`, `SecurityProvider`.
- A PHP integer passed to an `Object` parameter (for example `List.add`) becomes a `Long` when it does not fit
  an `Integer`; it was truncated. The same for integers in PHP arrays.
- Refused connections are logged and counted as errors in `stats()`. A connection closed before it sends
  anything - a TCP check of the port, as service monitors and load balancers make - is not: it is logged at
  debug level. A custom `SecurityProvider` signals it by throwing `EOFException` from `secure()`.
- Removed: the XML parser and writer, the ping, `ISocketFactory`.

**PHP client**

- `java_configure()` options `security` and `tls` (`ca`/`ca_file`, `cert`/`cert_file`, `key`/`key_file`,
  `key_passphrase`, `peer_name`, `credentials`).
- Needs `ext-openssl` for TLS (the default); no longer needs `ext-xml`.
- PHP objects that are not Java objects are sent as maps of their properties instead of `null`.
- `foreach` over a Java collection no longer stops at a `false` element.
- Calls on objects returned by methods that declare exceptions are cached correctly (the result's class is
  always known).
- `JAVA_RECV_SIZE` is gone.

**Project**

- Security tests (`client/tests/security.php`) and one example per security provider
  (`examples/security/`).
- The build downloads the internal libraries once, checked against SHA-256 sums in `dependencies.txt`.

## 1.0.0 (not released)

The first version of this fork of the PHP/Java Bridge 6.2.1. It was never released on its own: 2.0.0 is the
first release and includes all of it. See
[docs/differences-from-upstream.md](docs/differences-from-upstream.md) for the details.

**Server**

- One dependency-free jar, `php-java-bridge-server-1.0.0.jar`, for Java 21 and newer. Only the standalone
  socket server remains; the servlet, HTTP, FastCGI, JSR 223 and callback code is gone.
- Calls to public methods of non-public JDK classes (iterators, key sets) work without `--add-opens`.
- New command line: `--listen [ADDRESS:]PORT`, `--threads`, `--log-level`, `--log-file`, `--version`,
  `--help`; listens on 127.0.0.1:9267 by default. The upstream `INET_LOCAL:PORT` form still works.
- `BridgeServer` for running the server inside another Java application, with statistics and an
  `IExceptionListener` for exceptions returned to PHP. Start-up errors throw instead of exiting the JVM.
- Exceptions returned to PHP are no longer logged as errors.
- Setting a primitive field when results are sent as values no longer fails.
- The bundled SSL keystore (with its published private key) is removed, and so is the Apache-licensed base64
  encoder (replaced by `java.util.Base64`, same output).

**PHP client**

- One file, `client/JavaBridge.php`, for PHP 8.2 and newer, talking to the server over a TCP socket only.
- Same functions and classes as upstream's `Java.inc` for everything it still supports.
- `java_configure()` for host, port, persistent connections, timeouts, result mode and a logger.
- No warnings or deprecations on PHP 8.4; fatal errors became exceptions; the repeated-call path no longer
  creates a dynamic property.
- Results larger than about 7.5 MB work on PHP 8.4 (`XML_OPTION_PARSE_HUGE`).

**Project**

- JDK-only build, test and packaging scripts for Linux, macOS and Windows.
- Release zip with start scripts, the PHP client, examples and a smoke test.
- New documentation; MIT license with the upstream notices kept.
