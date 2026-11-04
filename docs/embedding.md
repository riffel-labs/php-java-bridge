# Embedding the server

Instead of running the server as its own process, you can start it inside your own Java application. PHP
then works with the objects of your application's JVM: its classes, its singletons, its scheduler, its
connection pools.

Put `php-java-bridge-server-<version>.jar` on your application's classpath, keep the `internal/` folder of
the release next to it (see below), and start a `BridgeServer`:

```java
import php.java.bridge.BridgeServer;

BridgeServer server = BridgeServer.builder()
        .address("127.0.0.1")      // the default
        .port(9267)                // the default; 0 picks a free port, see getPort()
        .threads(50)
        .logLevel(2)
        .start();                  // binds the port; throws IOException if it cannot

Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
```

`start()` returns as soon as the port is listening. The connections are served on the server's own
threads. `stop()` closes the listening socket and the open connections and interrupts calls in progress;
`awaitTermination()` blocks until the server has been stopped. `BridgeServer` is `AutoCloseable`.

## Internal libraries

The bridge loads its own libraries (Jackson, Bouncy Castle and its internal jar) from a directory, in a class
loader that sees only the JDK. Your application's classpath never contains them, and your application can use
other versions of the same libraries. See [server.md](server.md#internal-libraries).

The directory is `internal/` next to the server jar. If your build puts the jar somewhere else, copy the
release's `server/internal/` folder anywhere and set the system property before the first `start()`:

```
-Dphp.java.bridge.internal=/opt/myapp/php-java-bridge-internal
```

or `System.setProperty("php.java.bridge.internal", dir)`. `start()` throws `IllegalStateException` if the
directory has no jars. Do not repackage the internal jars into your application's jar or classpath.

## Security

`security(...)` chooses how connections are secured; the default is `BridgeSecurity.tlsAnonymous()`. See
[security.md](security.md) for what each provider protects against.

```java
import php.java.bridge.BridgeSecurity;
import php.java.bridge.TlsCredentials;

// TLS with a generated key; anyone who can reach the port gets in
BridgeServer.builder().security(BridgeSecurity.tlsAnonymous())

// no TLS
BridgeServer.builder().security(BridgeSecurity.plain())

// TLS with certificates from files; clients need a certificate from ca.pem named "php-client"
TlsCredentials files = TlsCredentials.fromFiles(Path.of("server.pem"), Path.of("server-key.pem"), null, Path.of("ca.pem"));
BridgeServer.builder().security(BridgeSecurity.tlsCertificates(files, "php-client"))

// the same with PEM text from memory, for example from a secret store; nothing is written to disk
TlsCredentials pem = TlsCredentials.fromPem(serverChainPem, serverKeyPem, null, caPem);
BridgeServer.builder().security(BridgeSecurity.tlsCertificates(pem))

// or with JDK key objects
TlsCredentials keys = TlsCredentials.of(privateKey, List.of(serverCert), List.of(rootCert));
```

`TlsCredentials` checks that the private key belongs to the server certificate. Private keys can be PKCS#8
(plain or encrypted), PKCS#1 RSA or SEC1 EC keys; key types RSA and EC (and Ed25519 for the server key).

**Changing credentials while running.** Short-lived certificates must be renewed without a restart. Pass a
`Supplier<TlsCredentials>`: the server asks it for every new connection and builds a new TLS context when it
returns a different object. Connections already open keep the credentials they started with.

```java
AtomicReference<TlsCredentials> current = new AtomicReference<>(initial);
BridgeServer server = BridgeServer.builder()
        .security(BridgeSecurity.tlsCertificates(current::get, "php-client"))
        .start();
...
current.set(TlsCredentials.fromPem(newChainPem, newKeyPem, null, caPem));   // later connections use these
```

The supplier must return the same object as long as the credentials do not change, and it runs on the
connection threads, so it should be quick (return a cached object, do not fetch on every call).

**Your own provider.** `SecurityProvider` is a small interface: `secure(Socket)` turns an accepted connection
into the socket to use (for example after a TLS handshake), `requiresProof(Socket)` says whether the client must
still prove its identity with a signed challenge, and `verifyProof(...)` checks that proof. The built-in
providers are the reference; most needs are covered by `tlsCertificates` with a supplier.

## Logging

The bridge logs through an `ILogger`. The default writes to standard error. To use your application's
logging, pass your own:

```java
import java.util.logging.Level;
import java.util.logging.Logger;
import php.java.bridge.ILogger;

final class JulBridgeLogger implements ILogger {
    private static final Logger LOG = Logger.getLogger("php-java-bridge");

    public void log(int level, String msg) {
        switch (level) {
        case FATAL: LOG.severe(msg); break;
        case ERROR: LOG.warning(msg); break;
        case INFO:  LOG.info(msg); break;
        default:    LOG.fine(msg); break;
        }
    }

    public void printStackTrace(Throwable t) {
        LOG.log(Level.WARNING, "Bridge failure", t);
    }

    public void warn(String msg) {
        LOG.warning(msg);
    }
}

BridgeServer server = BridgeServer.builder().logger(new JulBridgeLogger()).start();
```

The log level and the logger are JVM-wide settings of the bridge, applied by `start()`. Run one bridge
server per JVM.

## Exceptions returned to PHP

When Java code called from PHP throws, PHP receives the exception as a `JavaException`. For the bridge this
is a normal result, so it logs it only at log level 3 and above. To see these exceptions in your own
logs, add a listener:

```java
BridgeServer server = BridgeServer.builder()
        .exceptionListener((cause, message) -> LOG.log(Level.INFO, "Java exception returned to PHP: " + message, cause))
        .start();
```

`cause` is the exception thrown by the Java code; `message` is the text PHP receives, for example
`Invoke failed: [[c:Integer]]->parseInt((o:String)[o:String]). Cause: java.lang.NumberFormatException: ...`.
The listener runs on the connection's thread.

## Statistics

```java
BridgeServer.Stats s = server.stats();
s.busyThreads();          // threads holding a PHP connection
s.idleThreads();          // started threads waiting for a connection
s.maxThreads();           // the limit given to threads(...)
s.connectionsAccepted();  // since start
s.errors();               // bridge errors and fatal errors logged since start
```

A connected PHP worker holds its thread between requests, so `busyThreads()` counts connected workers, not
calls in progress. Exceptions returned to PHP are not counted in `errors()`; refused connections (failed TLS
handshakes, rejected certificates) are.

## Which classes PHP can use

PHP can use every class the server's threads can load. The threads use the context class loader of the
thread that called `start()`. To limit or extend what PHP sees, set that thread's context class loader
before calling `start()`. The bridge's internal libraries are never visible this way.

## The command line entry point

`php.java.bridge.Standalone.main(String[])` is the command line program described in
[server.md](server.md). It blocks until the process is stopped and exits the JVM with status 1 if the server
cannot start. In your own application, use `BridgeServer` instead.
