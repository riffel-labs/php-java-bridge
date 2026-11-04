# Security

## What the bridge allows

The bridge executes Java on behalf of the clients it lets in. A client can create any class on the server's
classpath and call any public method: read and write files, open network connections, start processes,
load code. **A client that gets in can do anything the server's Java process can do.**

Who gets in is decided by the security provider (see [docs/security.md](docs/security.md)):

- `tls-anonymous` (the default) encrypts the connection, but lets in anyone who can reach the port.
- `tls-certificates` lets in only clients with a certificate from a root the server trusts, and lets PHP
  check that it reached the right server.
- `plain` encrypts nothing and lets in anyone who can reach the port.

## Running it safely

- **Keep it on the loopback address** unless you use `tls-certificates`. The server listens on `127.0.0.1`
  by default and prints a warning when started on any other address without certificates. Do not expose a
  `tls-anonymous` or `plain` port to a network, a container network or a VPN.
- **Mind other local users.** On a machine shared with other users or untrusted processes, every local
  process can connect to `127.0.0.1`. Use `tls-certificates`, or run the bridge on a host or in a container
  where only the PHP application can reach it.
- **Protect the keys.** With certificate files, only the server's and PHP's accounts should be able to read
  the private keys. Keys can also stay in memory (see [docs/security.md](docs/security.md#files-or-memory)).
  Use short-lived certificates: the bridge does not check revocation lists.
- **Run it with the least privilege it needs.** Use a dedicated service account that can read only what
  the PHP application's Java code needs.
- **Limit the classpath.** Every class on the classpath is reachable from PHP. Put only the jars the
  application needs in `server/lib`.
- **Keep the internal libraries current.** `server/internal/` holds Jackson and Bouncy Castle. When one of
  them publishes a fix that concerns how the bridge uses it, replace the jar or update the bridge.

## Reporting a vulnerability

Please report security problems privately with GitHub's "Report a vulnerability" button on the
repository's Security tab, not in a public issue. Include the version, how the server was started (security
provider included), and the steps to reproduce.
