# Wire protocol

The PHP client and the server talk over one TCP connection (TLS by default) using JSON messages. This page
describes version 2 of the protocol, used by release 2.x. You only need it to write another client or to
debug traffic; PHP code uses [the client](php-client.md).

Release 1.x spoke upstream's XML protocol. The two do not mix: a 2.x client needs a 2.x server.

## Messages

Every message, in both directions, is one JSON object (UTF-8) prefixed with its length in bytes as a 4-byte
big-endian unsigned integer:

```
00 00 00 14  {"op":"hello","v":2}
```

The client sends requests; the server answers most of them with exactly one reply, in order. Requests marked
"no reply" below are answered with nothing, so the client may send several requests before it reads.

Until the client is let in (after `hello` and, if asked, `auth`), the server accepts messages of at most 64 KB
and closes connections that take longer than 20 seconds.

## Connection

```
client connects (and completes the TLS handshake, unless the security provider is plain)
client  {"op":"hello","v":2}
server  {"hello":"php-java-bridge","v":2,"server":"2.0.0"}
        or, if the client must prove its identity:
        {"hello":"php-java-bridge","v":2,"server":"2.0.0","challenge":"<base64, 32 bytes>"}
client  {"op":"auth","cert":"<PEM>","sig":"<base64>"}         only after a challenge
server  {"auth":"ok"}

client  {"op":"begin","values":false}                         no reply; the first PHP request starts
client  requests ...  server  replies ...
client  {"op":"end","keep":true}                              the PHP request ends
server  {"end":"A"}                                           all objects of that request are released

client  {"op":"begin","values":true}                          the next PHP request, same connection
...
client  {"op":"end","keep":false}  server  {"end":"E"}  and the server closes the connection
```

**hello** must be the first message of a connection, and only the first. The server checks `v`, the protocol
version. A client knows a persistent connection is new when it has not read or written anything on it yet.

**auth** answers a challenge. `cert` is the client's certificate as PEM, optionally followed by intermediate
certificates. `sig` is the signature, with the certificate's private key, of these bytes:

```
"php-java-bridge-auth-v1" 0x00   (24 bytes)
challenge                        (the 32 decoded bytes)
SHA-256 of the server's certificate as sent in the TLS handshake (DER)   (32 bytes)
```

ECDSA (P-256 or larger curves) or RSA PKCS#1 v1.5, both with SHA-256. See [security.md](security.md) for the
checks.

**begin** carries the options of a PHP request. No reply.

| Key | Meaning |
| --- | --- |
| `values` | `true`: primitive results come back as values (see below). Default `false`. |
| `log` | 0-6: the server's log level for this connection; without it, the server's own level. |

**end** ends a PHP request. `keep: true` keeps the connection for the next request; `false` closes it after
the reply. The reply's `end` is `A` (kept) or `E` (closing), lower case (`a`, `e`) if an unchecked
exception occurred in a deferred call during the request.

**error.** When the client breaks the protocol, the server replies `{"error":"<reason>"}` and closes the
connection. This includes using an object after releasing it, a rejected certificate, malformed JSON, or an
unknown `op`.

## Requests

| Request | Meaning |
| --- | --- |
| `{"op":"new","class":"java.util.ArrayList","args":[...]}` | create an instance |
| `{"op":"class","class":"java.lang.Math"}` | a reference to a class, for static methods and fields |
| `{"op":"call","obj":5,"name":"add","args":[...]}` | invoke a method |
| `{"op":"prop","obj":5,"name":"x","args":[]}` | get a field or bean property (no arguments) or set it (one argument) |
| `{"op":"unref","obj":5}` | release an object; no reply |

`obj` is an object id handed out by the server (they count from 1 in each PHP request); `0` addresses the
bridge itself (see below). `args` may be left out when empty. Method names are matched without regard to
case; field names exactly first, then without regard to case.

`new`, `class`, `call` and `prop` may carry:

- `"async":"ref"`: no reply; the result is stored under the next object id, so the client can use it
  right away. The client uses this for calls whose result it does not need yet.
- `"async":"void"`: no reply, and the result is dropped.
- `"tagged":true`: the arguments are in tagged form (see [Values](#values)).

## Replies

| Reply | Meaning |
| --- | --- |
| `{"value":V}` | the result, in plain form |
| `{"value":V,"tagged":true}` | the result, in tagged form |
| `{"void":true}` | the method returned nothing; `"throws":true` if it declares checked exceptions |
| `{"exception":12}` | the call threw; 12 is the id of the exception object; `"throws":true` if the method declares checked exceptions |

With `values: false` (the default), every result is an object reference, even an `int` or `null`: the
client fetches the value with `getValues` on the bridge object when the PHP code needs it. With
`values: true`, results whose declared type is primitive, and `null`, come back as values. `String` results
are references in both modes.

When a call throws, the server wraps the cause in an exception whose message reads like:

```
Invoke failed: [[c:Integer]]->parseInt((o:String)[o:String]). Cause: java.lang.NumberFormatException: For input string: "x" VM: 25.0.5@https://adoptium.net/
```

## Values

Values are written in one of two forms. The sender picks the form for each message and marks the tagged form
with `"tagged":true`.

**Plain form**, used whenever possible: JSON values mean what they say.

| JSON | PHP | Java |
| --- | --- | --- |
| `null`, `true`, `false` | `null`, `bool` | `null`, `Boolean` |
| integer | `int` | `long` (and every narrower integer type the parameter needs) |
| number with a fraction or exponent | `float` | `double` |
| string | string (UTF-8) | `String` |
| array | list (keys 0, 1, 2 ...) | Java array, `List` or other `Collection` |
| object | array with keys | `Map` |

JSON object keys are strings. A key that is a decimal integer without leading zeros (`"12"`, `"-3"`) stands
for the PHP integer key, as it does in any PHP array; in Java it is an `Integer` (or a `Long` if it does not
fit).

**Tagged form**, used when a value contains something the plain form cannot carry: an object reference, a
string that is not valid UTF-8, or `NaN`/infinity. In this form, every JSON object is a tag:

| Tag | Meaning |
| --- | --- |
| `{"obj":5}` | the Java object with id 5 (from PHP: `{"obj":0}` is `null`) |
| `{"obj":5,"class":"java.util.Date","kind":"A","throws":true}` | in replies: an object reference with its runtime class; `kind` is `A` for arrays, Lists and Maps, `C` for other Collections, `E` for Throwables, left out for anything else; `throws` (top level only) if the method that produced it declares checked exceptions |
| `{"map":{...}}` | a PHP array with keys / a Java Map; its values are again in tagged form |
| `{"bytes":"<base64>"}` | a binary string |
| `{"float":"NaN"}`, `{"float":"INF"}`, `{"float":"-INF"}` | not-a-number and the infinities |

Arrays, strings, numbers, booleans and null mean the same in both forms. Example, a call with a Java object
and a binary string as arguments:

```json
{"op":"call","obj":3,"name":"put","args":[{"obj":7},{"bytes":"/w=="}],"tagged":true}
```

The PHP client sends plain form when `json_encode()` can write the arguments as they are, and builds the
tagged form otherwise. The server writes plain form first and starts over in tagged form if it meets a value
that needs a tag. Most messages with large data (lists of numbers or strings) are plain, which PHP decodes in
one `json_decode()` call.

## The bridge object

Invoking methods on object id `0` calls the bridge itself. The PHP client uses these:

| Method | Purpose |
| --- | --- |
| `getValues(obj)` | reply with the value of `obj`: strings and numbers as values, arrays, Lists and Maps as arrays, recursively |
| `castToArray(obj)` | reply with the elements of an array, Collection or Map as an array (used by `foreach`) |
| `ObjectToString(obj)` | `String.valueOf(obj)` as a string value |
| `ObjectToString(exception, phpTrace)` | the text of an exception with its Java stack trace and the given PHP trace |
| `instanceOf(obj, class)` | whether `obj` is an instance of `class` (an object or class reference), as a boolean |
| `offsetExists(container, key)` | whether the List, Map or array has `key`, as a boolean |
| `offsetGet`, `offsetSet`, `offsetUnset(container, key[, value])` | array access on a List, Map or array |

The bridge also answers `castToString`, `castToExact`, `castToInExact` and `castToBoolean` (convert to a
string, integer, double or boolean value). Method names on the bridge object are matched without regard to
case.

## Limits

- Messages up to 2 GB after the client is let in; JSON strings and documents of any size up to that.
- Arrays and objects nested up to 10,000 levels.

## Not supported

Upstream's protocol also had callbacks from Java to PHP, a mode for running PHP from Java, an HTTP transport
and a ping. This protocol has none of these.
