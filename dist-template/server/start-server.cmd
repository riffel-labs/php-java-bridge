@echo off
rem Start the PHP/Java Bridge server from the release folder.
rem
rem   server\start-server.cmd                          listen on 127.0.0.1:9267
rem   server\start-server.cmd --listen 127.0.0.1:9300 --threads 50 --log-level 3
rem
rem   server\start-server.cmd --security tls-certificates --tls-cert server.pem --tls-key server-key.pem --tls-ca ca.pem
rem
rem Every jar in server\lib\ is on the classpath, so PHP can use its classes. server\internal\ holds
rem the bridge's own libraries (JSON, certificates); the bridge loads them separately, PHP never sees
rem them. The default security is tls-anonymous (encrypted); --security plain turns TLS off.
rem Needs Java 21 or newer: %JAVA_HOME%\bin\java, or java on the PATH.
rem Extra JVM options (for example -Xmx512m) can be set in JAVA_OPTS.
rem Run with --help for all options.

setlocal
set "DIR=%~dp0"
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
set "JAR="
for %%f in ("%DIR%php-java-bridge-server-*.jar") do set "JAR=%%~ff"
if not defined JAR (
  echo php-java-bridge-server-*.jar not found in %DIR% 1>&2
  exit /b 1
)

"%JAVA%" %JAVA_OPTS% -cp "%JAR%;%DIR%lib\*" php.java.bridge.Standalone %*
