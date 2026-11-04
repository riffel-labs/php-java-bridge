The bridge's own libraries. Do not add, remove or move jars here.

The bridge loads these jars in a class loader of their own: the Java code PHP
calls never sees them, and the jars in lib/ can contain other versions of the
same libraries without a conflict. Do not put them on the classpath.

  php-java-bridge-internal-<version>.jar   the bridge's JSON and certificate code (MIT, this project)
  jackson-core-<version>.jar               JSON reading and writing; Apache License 2.0
                                           (bundles FastDoubleParser, MIT; license texts in META-INF/)
  bcprov-, bcutil-, bcpkix-jdk18on-<version>.jar
                                           Bouncy Castle: certificates and PEM keys;
                                           Bouncy Castle Licence (MIT style), see META-INF/

To patch a library (for example after a security advisory), replace its jar
with a compatible version of the same library; see docs/server.md.
