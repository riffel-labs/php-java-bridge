# Security providers

A client the bridge lets in can create any Java object and call any method in the server's JVM, with the
server's permissions. Who gets in, and whether the traffic can be read on the way, is decided by the
**security provider**. Server and client must use the same one.

| Provider | Encryption | The client knows it reached the right server | The server knows who the client is | Use it for |
| --- | --- | --- | --- | --- |
| `tls-anonymous` (default) | TLS, key generated at startup | no | no | a loopback-only server on a machine you trust |
| `tls-certificates` | TLS | yes: the server certificate chains to a root the client trusts | yes: the client certificate chains to a root the server trusts | any server another process or host could reach |
| `plain` | none | no | no | a loopback-only server where TLS is unwanted, or PHP without ext-openssl |

Choose with `--security` on the server (and the `--tls-*` options, see [server.md](server.md)) and with the
`security` and `tls` options of `java_configure()` (see [php-client.md](php-client.md#tls-options)). An
application that embeds the server uses `BridgeSecurity` (see [embedding.md](embedding.md#security)).

Encryption and trust are separate. `tls-anonymous` encrypts every connection without any certificate setup,
which protects against reading the traffic, but not against a process that pretends to be the server, or a
client that should not be there. Only certificates (or something else that is authentic, like a pinned key)
protect against those.

## tls-anonymous

The server makes an EC key and a self-signed certificate every time it starts, in memory. The client
encrypts the connection with TLS 1.2 or 1.3 and does not check the certificate. The server lets in anyone who
can connect.

```
sh server/start-server.sh
```

```php
java_configure([]);   // 'security' => 'tls-anonymous' is the default
```

Keep this server on 127.0.0.1 (the default), on a machine where you trust every local user and process.

## tls-certificates

You need a private CA (a root certificate and its key) that issues:

- a **server certificate** with a name the client checks: the host name or IP address PHP connects to (for
  example `localhost` and `127.0.0.1`), or a service name that does not depend on the host (see
  [Service names instead of host names](#service-names-instead-of-host-names)); extended key usage `serverAuth`
  if any;
- one **client certificate** per client, or per kind of client; extended key usage `clientAuth` if any.

A certificate's names are its common name and the DNS names and URIs among its subject alternative names.

A root used only for the bridge (or only for your own services) is best: then a certificate from it means
"one of ours", and `--tls-allow-client` narrows it down by name.

```
sh server/start-server.sh --security tls-certificates \
    --tls-cert server.pem --tls-key server-key.pem --tls-ca ca.pem \
    --tls-allow-client php-client
```

Both sides check the other against the root:

- **The client checks the server.** Its certificate must chain to the root by valid signatures, be within its
  validity period, allow TLS server use and carry the expected name (`peer_name`, by default the host).
- **The server checks the client.** Its certificate must chain to the root, be valid, allow TLS client use and,
  if `--tls-allow-client` is given, carry one of those names (common name, DNS name or URI). The client must prove that it holds the
  certificate's private key.

The connection fails with a message that names the reason if any check fails. Revocation lists are not
checked; use short-lived certificates instead.

### Service names instead of host names

Where servers come and go and their host names and addresses are not known in advance, certificates can name
services instead of hosts. A URI in the subject alternative names is a name like any other, and is compared
exactly. For example, with a CA that every host can use:

| Certificate | Names |
| --- | --- |
| the bridge server's | `urn:example:service:bridge` |
| a web server's (a client of the bridge) | `urn:example:service:web`, `urn:example:audience:bridge` |
| a report worker's (not allowed to use the bridge) | `urn:example:service:reports` |

The server lets in clients whose certificate names it as their audience:

```
sh server/start-server.sh --security tls-certificates --tls-cert server.pem --tls-key server-key.pem \
    --tls-ca ca.pem --tls-allow-client urn:example:audience:bridge
```

and PHP checks that it reached the bridge, wherever it runs:

```php
java_configure([
    'host' => $bridgeHost,
    'security' => 'tls-certificates',
    'tls' => ['peer_name' => 'urn:example:service:bridge', 'ca' => $caPem, 'cert' => $certPem, 'key' => $keyPem],
]);
```

No host names or IP addresses are needed in any certificate. Set `peer_name`: without it the client expects the
host it connects to, which such a certificate does not carry, and refuses the server. PHP's TLS layer matches
host names only, so with `ca_file` the client checks a URI `peer_name` itself right after the handshake; with
`ca` it checks every name itself anyway.

### Files or memory

The client can hold its certificates in files or as PEM text. The checks are the same; how they happen
differs, because PHP's TLS layer accepts certificates only as file names.

**Files: standard mutual TLS.**

```php
java_configure([
    'security' => 'tls-certificates',
    'tls' => ['ca_file' => 'ca.pem', 'cert_file' => 'client.pem', 'key_file' => 'client-key.pem'],
]);
```

PHP's TLS layer checks the server certificate against `ca_file` during the handshake and presents the client
certificate in the handshake. The server's TLS layer checks it there.

**Memory: the same checks, done by the bridge right after the handshake.** For keys that must not be on disk,
or that come from a secret store, a vault or another process:

```php
java_configure([
    'security' => 'tls-certificates',
    'tls' => ['ca' => $caPem, 'cert' => $certPem, 'key' => $keyPem],
]);
```

```
1. TLS handshake. The connection is encrypted. PHP keeps a copy of the server's certificate.
2. PHP checks that certificate against the root text, in PHP code (openssl_x509_verify for each link of the
   chain, validity dates, CA flags, key usage, the name), before it sends anything else.
3. PHP sends hello; the server answers with a random challenge (32 bytes).
4. PHP signs  "php-java-bridge-auth-v1\0" + challenge + SHA-256(server certificate)  with its private key
   and sends its certificate and the signature.
5. The server checks the certificate (PKIX validation against the root, clientAuth, allowed names) and the
   signature with the certificate's public key. Then it answers "ok", or the reason and closes the connection.
```

This is what a TLS client certificate does in the handshake, moved one step later. The challenge makes every
proof good for one connection only. The server certificate in the signed data ties the proof to this TLS
connection, so it cannot be relayed through a server in the middle, on top of step 2. No cryptography of the
bridge's own is involved: standard X.509 validation and standard signatures (ECDSA or RSA with SHA-256).

Files and memory can be mixed: `ca_file` with `cert` and `key` uses PHP's TLS check for the server and the
challenge for the client.

**Supplying credentials lazily.** With a persistent connection (the default), steps 1 to 5 happen once per
connection, typically once per PHP worker process, not once per request. A `credentials` callable is called only
then:

```php
'tls' => ['credentials' => fn () => ['ca' => ..., 'cert' => ..., 'key' => ...]]
```

The server side can hold its key in memory too: `TlsCredentials.fromPem(...)` in an embedding application,
with a `Supplier<TlsCredentials>` for certificates that are renewed while the server runs (see
[embedding.md](embedding.md#security)).

### Trying it out

The server can write a demo PKI: a root, a server certificate for `localhost` and `127.0.0.1` that is also
named `urn:php-java-bridge:demo:server`, and a client certificate named `php-client` and
`urn:php-java-bridge:demo:client`, valid for a year:

```
sh server/start-server.sh --make-demo-certs examples/security/certs
```

Then follow `examples/security/tls-certificates-files.php` and `tls-certificates-memory.php`. The demo root's
private key is thrown away, and the client key is not encrypted; use your own CA for anything real.

## plain

No encryption, no authentication. Both sides must say so:

```
sh server/start-server.sh --security plain
```

```php
java_configure(['security' => 'plain']);
```

For a loopback-only server on a machine you trust, when TLS is not wanted or PHP has no openssl extension. The
server warns at startup when `plain` (or `tls-anonymous`) listens on an address other than loopback.

## What it does not cover

- **What a client may do once it is in.** Every admitted client can run any Java code. The bridge has no
  per-class or per-method permissions. Run the server under an account that can reach only what the Java code
  needs.
- **Denial of service.** A client that is let in can exhaust the server's memory or threads. Before a client is
  let in, the server limits its messages to 64 KB and gives it 20 seconds.
- **Who can read the key files.** With files, protect them with the file system's permissions.

Report security problems as described in [SECURITY.md](../SECURITY.md).
