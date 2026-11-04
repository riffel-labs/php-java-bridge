# Performance

The cost that matters is the round trip: a call whose result PHP needs waits until the server has answered.
Bulk data used to be the other cost; with JSON it mostly is not anymore. Everything below follows from that.

## What things cost

Measured with release 2.0.0 on a laptop (Intel i7-1265U, Windows 11, Java 25, PHP 8.4, client and server on
the same machine, persistent connection), next to release 1.0.0 (upstream's XML protocol) on the same machine:

| Operation | 1.0 (XML) | 2.0, `plain` | 2.0, `tls-anonymous` |
| --- | --- | --- | --- |
| A call whose result is used (`java_values($sb->length())`) | 0.07 ms | 0.05 ms | 0.06 ms |
| A call whose result is not used (`$sb->append('a')`), sent without waiting | 0.001 ms | 0.002 ms | 0.002 ms |
| `java_values($list)`, 10,000 numbers | 18.4 ms | 1.0 ms | 1.0 ms |
| `java_values($list)`, 10,000 strings | 25.8 ms | 1.6 ms | 1.6 ms |
| `new Java('java.util.ArrayList', $array)`, 10,000 numbers | 7.9 ms | 1.4 ms | 1.1 ms |
| `new Java('java.util.ArrayList', $array)`, 10,000 strings | 9.7 ms | 1.2 ms | 1.4 ms |

About 10,000 to 20,000 calls a second per PHP worker. Expect a Linux server to be somewhat faster and a busy
machine slower; the proportions stay the same. TLS adds a few microseconds per call, and the handshake
(a millisecond or two) happens once per persistent connection, not once per request.

## Why JSON

In 1.x, every value of a reply went through PHP's XML parser as an element whose attributes a PHP callback
decoded (hex numbers, base64 strings), and every element of an array sent to Java was written by PHP code. For
10,000 elements that is tens of thousands of PHP function calls. In 2.0 the whole message is one
`json_decode()` or `json_encode()` call, done in C:

| 10,000 elements, PHP side only | XML (1.x) | JSON (2.0) |
| --- | --- | --- |
| decode a reply of numbers | 15.1 ms (355 KB) | 0.7 ms (67 KB) |
| decode a reply of strings | 17.3 ms (542 KB) | 1.0 ms (203 KB) |
| encode a request of numbers | 4.6 ms (391 KB) | 0.1 ms (47 KB) |
| encode a request of strings | 7.3 ms (473 KB) | 0.2 ms (203 KB) |

Messages are also smaller: no markup, decimal numbers, and strings as UTF-8 instead of base64. On the Java side,
Jackson reads and writes the messages.

## Make fewer round trips

- **Fetch collections in one call.** `java_values()` converts a whole array, `List`, `Set` or `Map` (nested
  ones too) in one round trip. `foreach` over a Java collection also fetches all elements at once. Calling
  `get($i)` in a loop costs one or two round trips per element.
- **Send collections in one call.** Pass a PHP array to a constructor or method (`new Java('java.util.ArrayList',
  $rows)`, `$map->putAll($assoc)`) instead of calling `add()` in a loop.
- **Move loops into Java.** When PHP needs thousands of calls to get something done (walking a tree, filling a
  spreadsheet cell by cell), write a small Java helper class that does the work and returns the result, build
  it into a jar and put it in `server/lib/`. One call instead of thousands.
- **Let unused results go.** With `prefer_values` off (the default), a call whose method, receiver class and
  argument types the client has seen before, and whose result is not used, is sent without waiting for the
  answer. Builder-style code (`$sb->append(...)`, setters, `add()`) runs at memory speed this way.
- **Or prefer values.** With `prefer_values` on, `int`, `long`, `boolean` and `double` results come back as PHP
  values in the same round trip, so code that uses most results needs fewer calls. Calls are then never sent
  without waiting. Pick the mode that matches your code: values for code that reads results, proxies (the
  default) for code that mostly calls methods for their effect. See
  [php-client.md](php-client.md#proxies-and-values).

## Threads

Each PHP connection is served by its own server thread, and a PHP process keeps its connection between
requests. So the server needs one thread for every PHP worker that uses the bridge:

- Set `--threads` to the number of PHP worker processes that use the bridge (PHP-FPM `pm.max_children`, Apache
  `MaxRequestWorkers` with mod_php), plus some headroom. The default is 20.
- When all threads are busy, new connections wait for a free one, and so do their PHP requests. Hanging PHP
  requests under load usually mean too few threads.
- A thread costs a stack (1 MB by default on 64-bit JVMs, set with `-Xss`) while it exists. Idle threads end
  after 60 seconds.
- `BridgeServer.stats()` shows busy and idle threads if you embed the server ([embedding.md](embedding.md)).

## Connections

Keep `persistent` on (the default): the connection, its TLS session and the server thread are reused for the
next request of the same PHP process. With `persistent` off, every PHP request opens a new connection, does a
TLS handshake (and, with certificates in memory, the identity check), and the server starts or reuses a thread
for it.

## Memory

Every object PHP creates stays referenced in the server until PHP drops its handle or the PHP request ends.
A long request that creates many objects (hundreds of thousands of rows, each one an object) holds them all.
Give the JVM enough heap for your largest request (`-Xmx`, through `JAVA_OPTS` with the start scripts), or
release large objects early by unsetting the PHP variables that hold them.

A message is held in memory as a whole on both sides while it is read or written, so a 100 MB string
briefly needs a few times that.

## Logging

Keep the log level at 2 (the default) in production. Level 3 writes a line for every Java exception
returned to PHP, level 4 adds stack traces and every message, and level 5 writes every call and its result,
which slows the server down considerably.

## JVM settings

The defaults work. For a server that runs for a long time:

- set `-Xmx` (and optionally `-Xms` to the same value) so the heap does not depend on the machine's memory;
- add `-Djava.awt.headless=true` if PHP uses image or font classes on a machine without a display;
- keep the JVM's default garbage collector unless measurements say otherwise.
