# PHP client

`client/JavaBridge.php` is a single-file PHP library that lets PHP code create Java objects, call their
methods and read their fields. The objects live in the bridge server, a Java process; the client talks to it
over a TCP connection, encrypted with TLS by default.

## Requirements

- PHP 8.2 or later. JSON support is part of every PHP 8 build.
- The `openssl` extension for the TLS security modes, including the default `tls-anonymous`. With
  `'security' => 'plain'` the client works without it.
- A running bridge server of the same release (see [server.md](server.md)). By default the client connects
  to `127.0.0.1:9267`.

No Composer package or other PHP extension is needed.

## Including the client

```php
require_once '/path/to/client/JavaBridge.php';
```

Include the file once per request. It declares global functions and classes, so including it a second time is
a fatal error; use `require_once`.

## A first example

```php
require_once 'client/JavaBridge.php';

$list = new Java('java.util.ArrayList');      // a java.util.ArrayList in the server
$list->add('apple');
$list->add('banana');

echo $list->size(), "\n";                     // 2
print_r(java_values($list));                  // Array ( [0] => apple [1] => banana )

echo java('java.lang.Math')->max(3, 7), "\n"; // static method: 7

try {
    java('java.lang.Integer')->parseInt('not a number');
} catch (JavaException $e) {
    echo $e->toString(), "\n";                // describes the NumberFormatException
}
```

More examples are in `examples/` of the release (`hello.php`, `hello-page.php`, `smoke-test.php`), and one
per security mode in `examples/security/`.

## Configuration

Call `java_configure()` before the first Java call. The first call opens the connection, after which the
configuration can no longer change.

```php
java_configure([
    'host'            => '127.0.0.1',
    'port'            => 9267,
    'security'        => 'tls-anonymous',
    'tls'             => [],
    'persistent'      => true,
    'connect_timeout' => 20,
    'prefer_values'   => false,
    'logger'          => null,
]);
```

| Option | Type | Default | Meaning |
| --- | --- | --- | --- |
| `host` | string | `'127.0.0.1'` | Server host name or IP address. |
| `port` | int | `9267` | Server port (1-65535; a string of digits is accepted). |
| `security` | string | `'tls-anonymous'` | Must match the server's `--security`: `'tls-anonymous'`, `'tls-certificates'` or `'plain'`. See [security.md](security.md). |
| `tls` | array | `[]` | Certificates for `'tls-certificates'`; see below. |
| `persistent` | bool | `true` | `true`: keep the connection open and reuse it for later requests handled by the same PHP process. `false`: close it at the end of every request. |
| `connect_timeout` | int or float | `20` | Seconds to wait for the connection, the TLS handshake and the server's greeting. There is no timeout for calls once connected. |
| `prefer_values` | bool | `false` | Return primitive results as PHP values instead of proxies. See [Proxies and values](#proxies-and-values). |
| `logger` | callable or null | `null` | Receives the client's diagnostics as `function (string $level, string $message): void`; `$level` is `'warning'` or `'error'`. Without a logger, diagnostics are raised as `E_USER_WARNING`. See [Diagnostics](#diagnostics). |

Options that are not given keep their current value, so several calls can be combined. An unknown option or
an invalid value throws `InvalidArgumentException`; a call after the connection is established throws
`java_IllegalStateException`.

### TLS options

Used with `'security' => 'tls-certificates'`. The client needs the root certificate the server's certificate
must chain to, and its own certificate and private key. Each can be a file or PEM text held in memory:

| Key | Meaning |
| --- | --- |
| `ca_file` | File with the root certificate(s). PHP's TLS layer checks the server certificate. |
| `ca` | The root certificate(s) as PEM text. The client checks the server certificate itself, right after the handshake. |
| `cert_file`, `key_file` | Files with the client's certificate (plus any intermediates) and private key. Sent in the TLS handshake. |
| `cert`, `key` | The client's certificate and private key as PEM text. The client proves its identity with a signed challenge after the handshake. |
| `key_passphrase` | The password of an encrypted private key. |
| `peer_name` | The name the server certificate must carry: its common name, or a DNS name, IP address or URI among its subject alternative names (for example `urn:example:service:bridge`, see [security.md](security.md#service-names-instead-of-host-names)). Default: `host`. |
| `credentials` | A callable that returns an array with any of the keys above. It is called only when the client opens a new connection, not when it reuses a persistent one. |

Files and text can be mixed, for example `ca_file` with `cert` and `key`. [security.md](security.md) explains
how each combination works and what it protects against.

```php
java_configure([
    'security' => 'tls-certificates',
    'tls' => [
        'credentials' => fn () => [
            'ca'   => $secrets->get('bridge-ca'),
            'cert' => $secrets->get('bridge-client-cert'),
            'key'  => $secrets->get('bridge-client-key'),
        ],
    ],
]);
```

### Constants

These constants are read if they are defined before the first Java call. The client defines only
`JAVA_SEND_SIZE`, with its default, when it is not defined yet.

| Constant | Meaning |
| --- | --- |
| `JAVA_HOSTS` | Default `host` and `port`, as `"host:port"` or a port number (`"9267"` means `127.0.0.1:9267`). |
| `JAVA_PREFER_VALUES` | Default for `prefer_values` (any truthy value turns it on). |
| `JAVA_LOG_LEVEL` | An int from 0 to 6. Asks the server to use this log level for the connection. |
| `JAVA_SEND_SIZE` | Bytes of deferred requests that are buffered before they are written to the socket (default 8192). |

Options passed to `java_configure()` take precedence over the constants.

## API reference

### Objects

**`new Java(string $class, mixed ...$args)`** creates an instance of a Java class. The constructor is chosen
by the number and types of the arguments, so overloads work:

```php
$a = new Java('java.lang.StringBuilder', 'abc');   // StringBuilder(String)
$b = new Java('java.lang.StringBuilder', 100);     // StringBuilder(int)
$n = new Java('java.lang.Long', 5000000000);
```

`new Java([$class, ...$args])`, with one array argument, is accepted as well.

On the resulting object:

| PHP | Java |
| --- | --- |
| `$obj->method($a, $b)` | instance method call; the result is again a Java object (or a PHP value, see below), so calls can be chained: `$sb->append('a')->append(1)` |
| `$obj->field` | read a public field, or a bean property through its getter |
| `$obj->field = $value` | write a public field, or a bean property through its setter |
| `(string) $obj`, `echo $obj`, `"{$obj}"` | the object's `toString()`, as a PHP string |
| `foreach ($obj as $key => $value)` | see [Iteration](#iteration) |
| `$obj[$key]`, `isset()`, `unset()` | see [Array access](#array-access) |

Methods that PHP defines itself take precedence over Java methods of the same name: `__toString()`,
`jsonSerialize()`, and on exceptions the methods of PHP's `Exception` class (`getMessage()`, `getCode()`,
`getTrace()` ...). The ArrayAccess and IteratorAggregate methods (`offsetGet()`, `getIterator()` ...) call Java
only when they are given more arguments than PHP's versions take.

`$a === $b` compares the PHP objects, not the Java objects. Use `$a->equals($b)`.

### Classes

**`java(string $class): JavaClass`** returns the class for static methods and fields. Repeated calls with the
same name return the same object, so `java('java.lang.Math') === java('java.lang.Math')`. `Java()` is the same
function (PHP function names are case-insensitive).

```php
$math = java('java.lang.Math');
$math->sqrt(2.0);                      // static method
java('java.lang.Integer')->MAX_VALUE;  // static field
```

**`new JavaClass(string $class)`** does the same without the cache.

### Functions

**`java_values(mixed $value): mixed`** converts a Java object to a PHP value: strings, numbers, booleans and
null become PHP scalars; Java arrays, Collections and Maps become PHP arrays, recursively. Other objects stay
proxies. PHP values are returned unchanged. See [Type conversion](#type-conversion).

**`java_is_null(mixed $value): bool`**, **`java_is_true(mixed $value): bool`**,
**`java_is_false(mixed $value): bool`** test `java_values($value)` with `is_null()`, a `bool` cast and `!`.
`java_is_null()` is the way to test a result for Java `null` when results are proxies.

**`java_instanceof(java_JavaType $object, JavaClass $class): bool`** returns whether the object is an
instance of the class:

```php
java_instanceof($list, java('java.util.List'));   // true
```

It throws `java_IllegalArgumentException` if either argument is not a Java object.

**`java_configure(array $options): void`**, see [Configuration](#configuration).

`java_get_values()`, `java_isnull()`, `java_istrue()` and `java_isfalse()` are kept as aliases of the
functions above.

### Classes for type checks

| Class or interface | Meaning |
| --- | --- |
| `java_JavaType` | Interface implemented by every PHP object that stands for a Java object. |
| `Java` | A Java object (also the base class of `JavaClass`). |
| `JavaClass` (and its parent `java_class`) | A Java class. |
| `JavaException` (and its parent `java_exception`) | A Java exception; extends PHP's `Exception`. |
| `java_InternalException` | Subclass of `JavaException` for exceptions that come from Java calls. |

## Proxies and values

With `prefer_values` off (the default), every result is a proxy, even an `int`, a `boolean` or `null`.
Echoing a proxy or casting it to string gives its `toString()`; for anything else, convert it with
`java_values()` or test it with `java_is_null()`, `java_is_true()` and `java_is_false()`:

```php
$n = java('java.lang.Integer')->parseInt('42');   // a proxy
$n + 1;                                           // wrong: $n is an object
java_values($n) + 1;                              // 43
```

With `prefer_values` on, results whose declared Java type is primitive (`int`, `long`, `double`,
`boolean`, `char` ...) and `null` results arrive as PHP values. Results of object types, including
`java.lang.String` and boxed numbers such as `Integer`, are still proxies.

| | `prefer_values` off | `prefer_values` on |
| --- | --- | --- |
| `int`, `long`, `boolean`, `double` results | proxies | PHP values |
| `null` results | a proxy (`java_is_null()` is true) | `null` |
| Object results | proxies | proxies |
| Unchecked exceptions | reported as a diagnostic and returned as the result (see [Exceptions](#exceptions)) | thrown |
| Deferred calls (see [The connection](#the-connection)) | yes | no |

`java_values()`, `java_instanceof()`, `isset($obj[...])` and string conversion return PHP values in both
modes.

## Type conversion

### PHP to Java

Arguments are converted to the parameter types of the method or constructor that the server selects.

| PHP | Java |
| --- | --- |
| `string` | `String` (or `char`, `byte[]` ... as the parameter requires). A string that is not valid UTF-8 (binary data) reaches a `byte[]` parameter byte for byte; a `String` parameter gets it decoded as UTF-8, invalid bytes replaced by U+FFFD. |
| `int` | `long`, or the integer or floating point parameter type (`int`, `short`, `double`, `Integer`, `Long` ...). For an `Object` parameter: `Integer` if the value fits, otherwise `Long`. A value too large for a narrower parameter type wraps around. |
| `float` | `double` (or `float`, `Double` ...), exactly. `NAN`, `INF` and `-INF` arrive as `NaN` and the infinities. |
| `bool` | `boolean` |
| `null` | `null` |
| `array` | A Java array, `List`/`Collection` or `Map`, as the parameter requires: `new Java('java.util.ArrayList', [1, 2, 3])`, `new Java('java.util.HashMap', ['a' => 1])`. Nested arrays are converted recursively. Keys keep their PHP types (`7` is an `Integer` key, `'b'` a `String` key), and a `Map` keeps the PHP order. Inside an array, binary strings become `String`s. |
| a Java object | the Java object itself |
| any other object | a `Map` of its public properties, or of what its `jsonSerialize()` returns |
| a resource | `null`, with a diagnostic |

### Java to PHP

This table applies to `java_values()` and, for primitive results, to `prefer_values` mode.

| Java | PHP |
| --- | --- |
| `String` | `string` |
| `char` | `string` with `prefer_values`; `java_values()` of a `Character` object returns the proxy, so use `(string)` |
| `byte`, `short`, `int`, `long` and their wrapper classes | `int`, exactly (PHP integers are 64 bits) |
| `float`, `double`, `BigInteger`, `BigDecimal` and other numbers | `float` (`BigInteger`/`BigDecimal`: use `toString()` to keep precision) |
| `NaN`, `Infinity`, `-Infinity` | `NAN`, `INF`, `-INF` |
| `boolean` and `Boolean` | `bool` |
| `null` | `null` |
| `byte[]` | `string` with the raw bytes |
| other arrays, `List`, `Set` and other Collections | `array` with keys 0, 1, 2 ... |
| `Map` | `array`, in the map's order; keys that are integral numbers become `int` keys, other keys `String.valueOf(key)` (and a string such as `"12"` becomes the PHP key `12`, as in any PHP array) |
| any other object | the proxy, unchanged |

### Binary data

Binary PHP strings travel as they are in both directions:

```php
$digest = java('java.security.MessageDigest')->getInstance('SHA-256')->digest($binary);   // byte[] parameter
bin2hex(java_values($digest));                                                            // same as hash('sha256', $binary)
```

They cost a little more than text on the wire (base64, see [protocol.md](protocol.md)). Inside a PHP array, a
binary string becomes a Java `String`; pass binary data as a direct argument.

## Iteration

`foreach` works on Java arrays, Collections (`List`, `Set` ...) and Maps. All elements are fetched in one
request when the loop starts:

```php
foreach ($list as $index => $element) { ... }   // keys 0, 1, 2 ...
foreach ($map as $key => $value) { ... }        // the map's keys
foreach ($map->keySet() as $key) { ... }
```

The elements are proxies, except `null` elements, which are PHP `null`; use `java_values()` on them as
needed, or iterate over `java_values($list)` to get PHP values throughout.

Other `Iterable` objects and `Iterator`s cannot be used with `foreach` (it throws a `JavaException`). Call
`hasNext()`/`next()`, or `toArray()` on a Collection.

## Array access

Java arrays, Lists and Maps support PHP's array syntax:

| PHP | Array | List | Map |
| --- | --- | --- | --- |
| `$x[$k]` | element | `get($k)` | `get($k)` |
| `$x[$k] = $v` | set element | `set($k, $v)` | `put($k, $v)` |
| `$x[] = $v` | - | `add($v)` | `put(size(), $v)` |
| `isset($x[$k])` | index in range | index in range | `containsKey($k)` (true also for a `null` value) |
| `unset($x[$k])` | sets the element to `null` | `remove($k)` (by index) | `remove($k)` |

Sets and other Collections do not support array access.

## Exceptions

A Java exception is thrown in PHP as a `java_InternalException`, which extends `JavaException`,
`java_exception` and PHP's `Exception`:

```php
try {
    java('java.lang.Integer')->parseInt('x');
} catch (JavaException $e) {
    echo $e->toString();        // Java's toString() of the exception
    echo (string) $e;           // description with the Java and the PHP stack trace
    $cause = $e->getCause();    // Java's getCause(), a JavaException or a Java null
}
```

- Methods of PHP's `Exception` class are PHP's own. In particular `getMessage()` returns an empty string for
  exceptions that come from Java; use `$e->toString()`, `(string) $e` or Java's `getLocalizedMessage()`.
- The exception the server reports can be a wrapper that describes the failed call, with the original
  exception as its cause. Walk the chain with `getCause()` until `java_is_null()` is true.
- With `prefer_values` off, an exception is thrown only if the method or constructor declares checked
  exceptions (`throws ...`), or if the call itself cannot be made (class, method or field not found).
  Other (unchecked) exceptions are reported as an `Unchecked exception detected` diagnostic and returned as the
  result of the call. At the end of such a request the client reports `An unchecked exception occurred during
  script execution` and closes the connection instead of reusing it. With `prefer_values` on, every exception
  is thrown.
- An exception in a deferred call (see [The connection](#the-connection)) is only reported at the end of
  the request.
- After an exception the connection remains usable.

`new JavaException('java.lang.IllegalStateException', 'message')` creates a Java exception object, for
example to pass it to a Java method. For these objects `getMessage()` returns the message.

The client throws these exceptions for its own errors:

| Exception | When |
| --- | --- |
| `java_ConnectException` | The connection cannot be opened, the TLS handshake fails, the server's certificate is not trusted, or the server rejects the client's certificate. The message says which. |
| `java_IOException` | The connection broke, or the server closed it after a protocol error (the message includes the server's reason). The connection is closed. |
| `java_IllegalStateException` | A call after the connection was closed; `java_configure()` after the connection was established; `serialize()` of a Java object. |
| `java_IllegalArgumentException` | A non-Java argument to `java_instanceof()`. |
| `InvalidArgumentException` | An invalid `java_configure()` option, an invalid `JAVA_HOSTS`, or arguments nested deeper than 10,000 levels. |

`java_ConnectException` extends `java_IOException`; `java_IllegalStateException` and
`java_IllegalArgumentException` extend `java_RuntimeException`; all of them extend `java_JavaException`,
which extends PHP's `Exception`.

## The connection

The first Java call of a request opens the connection, or takes over the persistent connection an earlier
request of the same PHP process left open. Messages are JSON documents; see [protocol.md](protocol.md).

**A new connection** starts with the TLS handshake (unless `security` is `'plain'`), then the client's
`hello`. With certificates, the client checks the server's certificate and proves its own identity at this
point (see [security.md](security.md)). This happens once per connection, not once per request.

**Each PHP request** starts with a short `begin` message that carries the request's options. It needs no
reply, so it costs no extra round trip.

**End of the request.** The client registers a shutdown function when it connects. It sends any requests that
are still buffered, then tells the server that the request is finished. The server releases every Java object
of the request; Java objects cannot be kept across PHP requests.

- With `persistent` on (the default) the connection stays open and the next request handled by the same PHP
  process (PHP-FPM worker, Apache child, `php -S`) reuses it, without a new handshake. If the server was
  restarted in between, PHP notices the dead connection and opens a new one.
- With `persistent` off the connection is closed, and every request pays for a new connection and handshake.
- The PHP session is not touched; session handlers that use Java must finish before the client's shutdown
  function runs.
- Shutdown functions that were registered before the first Java call run before the connection is finished
  and can still use Java; shutdown functions registered after it run afterwards, when calls throw
  `java_IllegalStateException`.
- The settings of a persistent connection cannot change during the life of the PHP process: a later request
  with other `tls` settings for the same host and port gets the existing connection.

**Deferred calls.** With `prefer_values` off, a call whose method, receiver class and argument types have been
seen before, and which declares no checked exceptions, is sent without waiting for its response. Its result is
a placeholder object that is resolved when it is used. Deferred calls are sent, in order, with the next request
that needs a response or at the end of the request. When the result of a deferred call is discarded right
away, the server does not create it.

**Errors.** When the connection breaks, or the server closes it after a protocol error, the client closes the
socket and throws `java_IOException`; every later call in the request throws `java_IllegalStateException`. The
client does not reconnect within a request.

## Diagnostics

The client reports these conditions through the `logger` option, or as `E_USER_WARNING` if no logger is set:

| Level | Message |
| --- | --- |
| `warning` | `Unchecked exception detected: ...` (an unchecked exception with `prefer_values` off) |
| `warning` | `argument of type ... cannot be passed to Java, using NULL instead` (resources) |
| `warning` | `Exception in Java::__toString(): ...` (`toString()` threw; the string conversion returns `''`) |
| `error` | `An unchecked exception occurred during script execution. ...` (at the end of the request) |
| `error` | Other problems at the end of the request, for example a connection that broke while it was being finished |

## Limitations

- **Numbers.** An `int` that does not fit a narrower parameter type (`int`, `short` ...) wraps around.
  `BigInteger` and `BigDecimal` results arrive as floats.
- **Binary strings inside arrays** become Java `String`s (see [Binary data](#binary-data)).
- **Nesting.** Arrays and collections can be nested up to 10,000 levels.
- **No callbacks.** Java cannot call PHP code; PHP objects cannot implement Java interfaces.
- **No sessions.** Java objects live for one PHP request. There is no shared Java session or context
  between requests, and Java objects cannot be serialized.
- **foreach** works only on arrays, Collections and Maps (see [Iteration](#iteration)).
- Java public fields named `__java`, `__client`, `__delegate`, `__signature`, `__factory` or
  `__cancelProxyCreationTag` cannot be accessed, because the PHP objects use these names.
- The client of release 2.x talks only to a server of release 2.x, and 1.x only to 1.x (the wire protocol
  changed from XML to JSON).

## Differences from the upstream Java.inc

The client is a fork of `Java.inc` from the PHP/Java Bridge 6.2.1. It keeps the public API listed above and
replaces the transport: JSON instead of XML, TLS, and no HTTP.

Removed:

- The XML protocol, and with it the requirement for `ext/xml` and the pure-PHP XML parser upstream used
  without it.
- The HTTP transport to a servlet container: `JAVA_SERVLET`, the `X_JAVABRIDGE_*` headers and environment
  variables, cookies, redirects, `ssl://` hosts, host lists in `JAVA_HOSTS`, and the default address
  `127.0.0.1:8080`.
- The automatic start of `java -jar JavaBridge.jar` when no server was listening.
- Calls from Java to PHP: `java_closure()`, `java_get_closure()`, `java_wrap()`, `java_unwrap()`.
- Context and session support: `java_context()`, `java_get_context()`, `java_session()`,
  `java_get_session()`, `java_server_name()`, `java_get_server_name()`.
- Serialization of Java objects (`__sleep()`/`__wakeup()`, `java_get_lifetime()`). `serialize()` now throws.
- `java_require()`, `java_autoload()`, `java_virtual()`, `java_call_with_continuation()`,
  `java_begin_document()`, `java_end_document()`, `java_last_exception_get()`, `java_last_exception_clear()`,
  `java_invoke()`, `java_inspect()`, `java_cast()`, `java_set_file_encoding()`, `java_set_encoding()`.
- The autoloader that made Java classes available as PHP classes (`new java_util_ArrayList()`,
  `new \java\util\ArrayList()`), and `JAVA_DISABLE_AUTOLOAD`.
- The `java.hosts`, `java.servlet`, `java.log_level` and `java.prefer_values` php.ini settings.
- The `JAVA_PEAR_VERSION` and `JAVA_RECV_SIZE` constants, and the definition of `JAVA_HOSTS`, `JAVA_SERVLET`,
  `JAVA_PREFER_VALUES` and `JAVA_LOG_LEVEL` by the client. The client only reads these constants.
- The `java_create_client()` and `java_get_default_channel()` hooks.

Changed:

- `java_configure()` is new, with TLS and certificate settings. The default address is `127.0.0.1:9267`.
  Connections are encrypted by default and can be non-persistent.
- Values that upstream lost now arrive intact: binary strings, `long` values beyond 2^53 (now PHP ints), full
  double precision, `NaN` and the infinities, the order of PHP map keys, and PHP integers too large for an
  `Integer` passed to `Object` parameters. Strings and arrays have no size limit beyond memory.
- PHP objects that are not Java objects are sent as maps of their properties instead of `null`.
- `foreach` over a Java collection no longer stops early at a `false` element.
- Errors that were fatal (`E_USER_ERROR`) are exceptions: `java_ConnectException`, `java_IOException`,
  `java_IllegalStateException`. Diagnostics go to the configured logger.
- The shutdown function is registered at the first Java call, not when the file is included.
- The file can be included only once (`require_once`).
- PHP 8.2+ is required. The code has PHP 8 constructors, return types on the Iterator and ArrayAccess methods,
  and no dynamic properties, so it runs without deprecation warnings.
- A resource argument is sent as `null` with a diagnostic; upstream silently dropped the argument.
