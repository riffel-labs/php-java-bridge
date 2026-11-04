# Building from source

The build uses a JDK (`javac` and `jar`) and no build tool. The four library jars the server uses internally
are downloaded once from Maven Central and checked against the SHA-256 in `dependencies.txt`; nothing else is
downloaded.

## Requirements

- JDK 21 or newer (`$JAVA_HOME/bin`, or `javac` on the `PATH`)
- `curl` and `sha256sum` (or `shasum`) for the shell scripts; the PowerShell scripts need nothing extra
- Network access to `repo1.maven.org` for the first build (afterwards the jars are in `build/deps/`)
- PHP 8.2 or newer with `ext-openssl`, to run the client tests (optional)

## Scripts

Each script comes in a shell version (Linux, macOS, Git Bash on Windows) and a PowerShell version (Windows
PowerShell 5.1 or PowerShell 7).

| Script | What it does |
| --- | --- |
| `./build.sh`, `.\build.ps1` | Downloads the libraries if needed, compiles the server and its internal jar, and writes `dist/php-java-bridge-server-<version>.jar` and `dist/internal/` |
| `./test.sh`, `.\test.ps1` | Builds, runs the server tests, then starts the jar and runs the PHP client tests, the security tests and the smoke test |
| `./package.sh`, `.\package.ps1` | Builds and assembles `dist/php-java-bridge-<version>.zip` and `dist/SHA256SUMS` |

The version comes from the `VERSION` file. The build treats compiler warnings as errors.

Settings for the scripts:

| Variable | Used by | Meaning |
| --- | --- | --- |
| `JAVA_HOME` | all | JDK to use; otherwise the tools on the `PATH` |
| `PHP` | test | PHP binary for the client tests (default `php`); the client tests are skipped if it is missing |
| `PHP_FLAGS` | test | Extra arguments for PHP, for example `-c test-php.ini` for a php.ini that enables `openssl` |
| `TEST_PORT` | test | Port for the test server (default 9399) |

Example on Windows:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21'
$env:PHP = 'C:\php\php.exe'
.\test.ps1
```

## Dependencies

`dependencies.txt` lists each library with its file name, SHA-256 and download URL. The build downloads a file
only if `build/deps/` does not have it with the right checksum, and stops if a download does not match. To
build without network access, put the files in `build/deps/` yourself.

To update a library, change its line, check the new file against the checksum the project publishes, and
put the new SHA-256 in `dependencies.txt`. The jars are not in git.

## Layout

| Path | Contents |
| --- | --- |
| `server/src/` | Server sources, package `php.java.bridge`; `php.java.bridge.spi` holds the interfaces the internal jar implements |
| `server/internal/` | Sources of the internal jar, package `php.java.bridge.internal`: the only code that uses Jackson and Bouncy Castle |
| `server/test/` | `ServerTests`, a self-contained test runner (no test framework): each test starts a real server in-process on a free port and speaks the wire protocol over a socket |
| `client/JavaBridge.php` | The PHP client, a single file |
| `client/tests/run.php` | Client tests: plain PHP, run against a live server |
| `client/tests/security.php` | Security tests: starts servers with each security provider and connects with matching and wrong settings |
| `dist-template/` | Files copied into the release zip as they are: start scripts, the README files of `lib/` and `internal/`, examples |
| `dependencies.txt` | The internal libraries: file, SHA-256, URL |
| `docs/` | Documentation |
| `build/`, `dist/` | Build output (not in git) |

To work on the server in an IDE, mark `server/src` as a source root and `server/test` as a test source root;
for `server/internal` add the jars in `build/deps/` as its only libraries. Do not give `server/src` those
jars: the server code must not use them directly.

## Running the tests by hand

```
./build.sh
javac --release 21 -cp build/classes -d build/test-classes server/test/php/java/bridge/*.java
java -Dphp.java.bridge.internal=dist/internal -cp build/classes:build/test-classes php.java.bridge.ServerTests

java -jar dist/php-java-bridge-server-2.0.0.jar --listen 127.0.0.1:9399 &
php client/tests/run.php 127.0.0.1:9399
JAVA_BRIDGE_ADDRESS=127.0.0.1:9399 php dist-template/examples/smoke-test.php
php client/tests/security.php
```

On Windows, use `;` instead of `:` in the classpath.
