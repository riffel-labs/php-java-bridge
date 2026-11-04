# Releasing

Releases are built locally and uploaded to GitHub by hand. You need the [GitHub CLI](https://cli.github.com/)
(`gh`), logged in with access to the repository.

## Steps

1. **Version.** Put the new version in `VERSION` (for example `2.0.1`) and describe the changes under that
   version in `CHANGELOG.md`, with the release date.

2. **Test.** Run the tests on the oldest and newest Java versions you support (21 and the latest), with PHP
   8.2 or newer:

   ```
   JAVA_HOME=/path/to/jdk-21 ./test.sh
   JAVA_HOME=/path/to/jdk-25 ./test.sh
   ```

3. **Package.**

   ```
   ./package.sh
   ```

   This writes `dist/php-java-bridge-<version>.zip` and `dist/SHA256SUMS`. Unzip the zip into an empty
   folder, start `server/start-server.cmd` (or `sh server/start-server.sh`) and run
   `php examples/smoke-test.php` once as a final check.

4. **Commit and tag.**

   ```
   git commit -am "Release 2.0.1"
   git tag -a v2.0.1 -m "PHP/Java Bridge 2.0.1"
   git push origin main v2.0.1
   ```

5. **Publish.**

   ```
   gh release create v2.0.1 --verify-tag --title "PHP/Java Bridge 2.0.1" --notes-file notes.md \
       dist/php-java-bridge-2.0.1.zip \
       dist/SHA256SUMS
   ```

   Use the version's section of `CHANGELOG.md` as `notes.md`. The zip holds the server jar, its internal
   libraries (with their license files), the client, the examples and the documentation. `SHA256SUMS` also
   lists the server and internal jars, so a vendored copy can be checked after unzipping.

## The upstream release

The repository also has a release `upstream-6.2.1` on the tag of the same name. It holds the original
PHP/Java Bridge 6.2.1 files this fork is based on, for reference. It was created once:

```
gh release create upstream-6.2.1 --verify-tag --title "Upstream PHP/Java Bridge 6.2.1 (reference)" \
    --notes "The unmodified upstream release this fork is based on. See NOTICE.md." \
    php-java-bridge-6.2.1-svn-r1048.zip \
    JavaBridgeTemplate621.war \
    php-java-bridge_6.2.1_documentation.zip \
    SHA256SUMS
```
