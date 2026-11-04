# Notice

This project is a fork of the **PHP/Java Bridge 6.2.1** by Jost Boekemeier and others
(<https://sourceforge.net/projects/php-java-bridge/>). It is not affiliated with or endorsed by the
original author.

## Where the code comes from

| Part | Upstream source |
| --- | --- |
| Java server (`server/src/php/java/bridge/`) | Subversion `svn://svn.code.sf.net/p/php-java-bridge/svnrepo`, path `trunk/php-java-bridge/server/php/java/bridge`, revision r1048 (2010-10-31, "Release-6-2-1") |
| PHP client (`client/JavaBridge.php`) | `java/Java.inc` from `documentation/src.zip` in the official release `php-java-bridge_6.2.1_documentation.zip` (SHA-256 of `Java.inc`: `d26d335b4d508e56a1ad79134d1b277bdb2ab47fb0c6f3d8dd2047e8aa1c6418`) |
| Protocol description | `PROTOCOL.TXT`, same Subversion revision. Release 2.0 replaced the protocol; `docs/protocol.md` describes the new one. |

The git tag `upstream-6.2.1` holds exactly those upstream files, unmodified. Every change made by this fork
is in the history after it:

```
git diff upstream-6.2.1 main
```

The complete upstream 6.2.1 release (all 750 files of the Subversion tree at r1048, the original
`JavaBridgeTemplate621.war` and the documentation zip) is attached to the `upstream-6.2.1` release of this
repository.

New files written for this fork (for example `BridgeServer`, `BridgeSecurity`, the `spi` and `internal`
packages, the tests and the documentation) carry the fork's copyright line only.

## What this fork removed

Upstream could run inside a servlet container, start PHP from Java, call PHP back from Java, and shipped PHP
binaries, third-party jars, examples and Linux packaging. This fork keeps only the standalone socket server
and the PHP client that talks to it. See [docs/differences-from-upstream.md](docs/differences-from-upstream.md)
for the full list.

## Third-party libraries

The server uses these libraries, unmodified. They are not in the git repository: the build downloads them
from Maven Central and checks them against the SHA-256 sums in `dependencies.txt`. The release zip ships them
in `server/internal/`, each jar with its own license files in `META-INF/`.

| Library | Version | License |
| --- | --- | --- |
| Jackson core (`tools.jackson.core:jackson-core`) | 3.2.4 | Apache License 2.0; bundles FastDoubleParser (MIT) |
| Bouncy Castle (`org.bouncycastle:bcprov-jdk18on`, `bcutil-jdk18on`, `bcpkix-jdk18on`) | 1.86 | Bouncy Castle Licence (MIT style) |

## License

The fork is distributed under the MIT license (see [LICENSE](LICENSE)); the third-party libraries above keep
their own licenses. The notes below explain how the MIT license follows from the upstream notices. They
describe the notices as found; they are not legal advice.

- **Java sources.** Every upstream Java file this fork uses carries an MIT permission notice
  ("Copyright (C) 2003-2007 Jost Boekemeier and others. Permission is hereby granted, free of charge, ...").
  Those notices are kept in each file; files changed by the fork add a second copyright line.
- **PHP client.** Upstream generated its PHP library `Java.inc` from several source files and stamped it
  "Copyright (C) 2003-2009 Jost Boekemeier. Distributed under the MIT license, see Options.inc for details."
  (`Options.inc` contains the MIT text.) The fork's client is derived from that released `Java.inc`. The
  individual upstream PHP source files it was generated from (`Client.inc`, `Protocol.inc`, `Parser.inc`, ...)
  carried GPL-2.0-or-later headers with a linking exception; those files are not part of this fork.
- **COPYING.** The upstream tree also contained a `COPYING` file with the GNU GPL version 2, last changed in
  2006, before the source files moved to the MIT notice. It is not part of this fork.

## Credits

The PHP/Java Bridge was written by Jost Boekemeier, with contributions credited in the upstream sources,
including Sam Ruby and Kai Londenberg (method selection and coercion in `JavaBridge.java`).
