<?php

/**
 * Security tests for the PHP client: starts bridge servers with each security
 * provider and connects with matching and mismatching settings.
 *
 * Usage:
 *
 *     php client/tests/security.php
 *
 * Needs ext-openssl and a built server (./build.sh). Environment:
 *
 *     JAVA        the java command (default: java)
 *     BRIDGE_JAR  the server jar (default: dist/php-java-bridge-server-<VERSION>.jar)
 *
 * The exit code is 1 if any test fails.
 */

error_reporting(E_ALL);
ini_set('display_errors', '1');

require __DIR__ . '/../JavaBridge.php';

$root = dirname(__DIR__, 2);
$java = getenv('JAVA') ?: 'java';
$jar = getenv('BRIDGE_JAR') ?: $root . '/dist/php-java-bridge-server-' . trim(file_get_contents($root . '/VERSION')) . '.jar';
$passed = 0;
$failed = 0;
$servers = [];

if (!extension_loaded('openssl')) {
    fwrite(STDERR, "The security tests need ext-openssl.\n");
    exit(1);
}

function check(bool $condition, string $message): void
{
    if (!$condition) {
        throw new RuntimeException($message);
    }
}

/** Runs a test; a fresh "PHP request" each time: the client state is reset, persistent connections stay. */
function test(string $name, callable $fn): void
{
    global $passed, $failed;
    java_Client::$instance = null;
    java_Client::$options = [];
    try {
        $fn();
        $passed++;
        echo "PASS {$name}\n";
    } catch (Throwable $e) {
        $failed++;
        echo "FAIL {$name}: " . get_class($e) . ': ' . strtok($e->getMessage(), "\n") . "\n";
    }
    if (java_Client::$instance !== null && java_Client::$instance->channel !== null) {
        java_Client::$instance->close();
    }
}

/** Expects $fn to throw an exception whose message contains $needle. */
function fails(string $needle, callable $fn): void
{
    try {
        $fn();
    } catch (Throwable $e) {
        check(str_contains($e->getMessage(), $needle), "expected '{$needle}' in: " . $e->getMessage());
        return;
    }
    throw new RuntimeException("expected a failure containing '{$needle}'");
}

function freePort(): int
{
    $s = stream_socket_server('tcp://127.0.0.1:0');
    $name = stream_socket_get_name($s, false);
    fclose($s);
    return (int) substr($name, strrpos($name, ':') + 1);
}

/** Starts a server with the given options and returns its port. */
function server(string ...$options): int
{
    global $java, $jar, $servers;
    $port = freePort();
    $command = array_merge([$java, '-jar', $jar, '--listen', "127.0.0.1:{$port}"], $options);
    $log = tempnam(sys_get_temp_dir(), 'bridge');
    $process = proc_open($command, [1 => ['file', $log, 'a'], 2 => ['file', $log, 'a']], $pipes);
    $servers[] = [$process, $log];
    for ($i = 0; $i < 100; $i++) {
        $output = (string) file_get_contents($log);
        if (str_contains($output, 'listening on')) {
            return $port;
        }
        if (!proc_get_status($process)['running']) {
            break;
        }
        usleep(100000);
    }
    throw new RuntimeException('the server did not start: ' . file_get_contents($log));
}

function demoCertificates(string $dir): array
{
    global $java, $jar;
    exec(escapeshellarg($java) . ' -jar ' . escapeshellarg($jar) . ' --make-demo-certs ' . escapeshellarg($dir), $out, $code);
    check($code === 0, 'make-demo-certs failed: ' . implode("\n", $out));
    $files = [];
    foreach (['ca', 'server', 'server-key', 'client', 'client-key'] as $name) {
        $files[$name] = "{$dir}/{$name}.pem";
    }
    return $files;
}

function call(): string
{
    // not java(): its cache would outlive the simulated PHP request
    return java_values((new JavaClass('java.lang.Thread'))->currentThread()->getName());
}

$tmp = sys_get_temp_dir() . '/php-java-bridge-security-' . getmypid();
$pki = demoCertificates("{$tmp}/a");
$other = demoCertificates("{$tmp}/b");
$pem = static fn (string $file): string => file_get_contents($file);

echo "PHP/Java Bridge security tests (PHP " . PHP_VERSION . ", " . OPENSSL_VERSION_TEXT . ")\n";

// ---------------------------------------------------------------- plain and tls-anonymous

$plain = server('--security', 'plain');
$anonymous = server();

test('plain server, plain client', static function () use ($plain): void {
    java_configure(['port' => $plain, 'security' => 'plain']);
    check(str_starts_with(call(), 'JavaBridge-connection-'), 'call works');
});

test('plain server, TLS client: refused', static function () use ($plain): void {
    java_configure(['port' => $plain, 'connect_timeout' => 5]);
    fails('Could not connect', static fn () => call());
});

test('tls-anonymous server, default client', static function () use ($anonymous): void {
    java_configure(['port' => $anonymous]);
    check(str_starts_with(call(), 'JavaBridge-connection-'), 'call works');
});

test('tls-anonymous server, plain client: refused', static function () use ($anonymous): void {
    java_configure(['port' => $anonymous, 'security' => 'plain', 'connect_timeout' => 5]);
    fails('closed the connection', static fn () => call());
});

test('tls options are checked', static function (): void {
    fails("unknown tls option 'cafile'", static fn () => java_configure(['tls' => ['cafile' => 'x']]));
    fails("tls 'credentials' must be a callable", static fn () => java_configure(['tls' => ['credentials' => 'nope_xyz']]));
    fails("'security' must be", static fn () => java_configure(['security' => 'tls']));
});

// ---------------------------------------------------------------- tls-certificates

$certs = server('--security', 'tls-certificates', '--tls-cert', $pki['server'], '--tls-key', $pki['server-key'],
                '--tls-ca', $pki['ca'], '--tls-allow-client', 'php-client');

$files = ['ca_file' => $pki['ca'], 'cert_file' => $pki['client'], 'key_file' => $pki['client-key']];
$memory = ['ca' => $pem($pki['ca']), 'cert' => $pem($pki['client']), 'key' => $pem($pki['client-key'])];

test('certificates from files (TLS client certificate)', static function () use ($certs, $files): void {
    java_configure(['port' => $certs, 'security' => 'tls-certificates', 'tls' => $files]);
    check(str_starts_with(call(), 'JavaBridge-connection-'), 'call works');
});

$thread = null;
test('certificates as PEM text (signed challenge)', static function () use ($certs, $memory, &$thread): void {
    java_configure(['port' => $certs, 'security' => 'tls-certificates', 'tls' => $memory]);
    $thread = call();
    check(str_starts_with($thread, 'JavaBridge-connection-'), 'call works');
});

test('the next PHP request reuses the connection without a new handshake', static function () use ($certs, &$thread): void {
    $calls = 0;
    java_configure(['port' => $certs, 'security' => 'tls-certificates', 'tls' => [
        'credentials' => static function () use (&$calls): array {
            $calls++;
            return [];
        },
    ]]);
    check(call() === $thread, 'same server connection');
    check($calls === 0, 'credentials were not needed: ' . $calls);
});

test('credentials from a callable, asked once per new connection', static function () use ($certs, $memory): void {
    $calls = 0;
    java_configure(['port' => $certs, 'security' => 'tls-certificates', 'persistent' => false, 'tls' => [
        'credentials' => static function () use (&$calls, $memory): array {
            $calls++;
            return $memory;
        },
    ]]);
    check(str_starts_with(call(), 'JavaBridge-connection-'), 'call works');
    check($calls === 1, 'called once: ' . $calls);
});

test('server not issued by the configured root: refused (text)', static function () use ($certs, $memory, $other, $pem): void {
    java_configure(['port' => $certs, 'security' => 'tls-certificates', 'persistent' => false,
                    'tls' => ['ca' => $pem($other['ca'])] + $memory]);
    fails("not issued by a root in tls 'ca'", static fn () => call());
});

test('server not issued by the configured root: refused (file)', static function () use ($certs, $files, $other): void {
    java_configure(['port' => $certs, 'security' => 'tls-certificates', 'persistent' => false, 'connect_timeout' => 5,
                    'tls' => ['ca_file' => $other['ca']] + $files]);
    fails('Could not connect', static fn () => call());
});

test('server certificate for another name: refused', static function () use ($certs, $memory, $files): void {
    java_configure(['port' => $certs, 'security' => 'tls-certificates', 'persistent' => false,
                    'tls' => ['peer_name' => 'bridge.example.com'] + $memory]);
    fails("not issued to 'bridge.example.com'", static fn () => call());
    java_Client::$instance = null;
    java_Client::$options = [];
    java_configure(['port' => $certs, 'security' => 'tls-certificates', 'persistent' => false, 'connect_timeout' => 5,
                    'tls' => ['peer_name' => 'bridge.example.com'] + $files]);
    fails('Could not connect', static fn () => call());
});

test('server named by a URI instead of its host (peer_name)', static function () use ($certs, $memory, $files): void {
    foreach (['text' => $memory, 'file' => $files] as $how => $tls) {
        java_Client::$instance = null;
        java_Client::$options = [];
        java_configure(['port' => $certs, 'security' => 'tls-certificates', 'persistent' => false,
                        'tls' => ['peer_name' => 'urn:php-java-bridge:demo:server'] + $tls]);
        check(str_starts_with(call(), 'JavaBridge-connection-'), "call works ({$how})");
        java_Client::$instance->close();
    }
    foreach (['text' => $memory, 'file' => $files] as $how => $tls) {
        java_Client::$instance = null;
        java_Client::$options = [];
        java_configure(['port' => $certs, 'security' => 'tls-certificates', 'persistent' => false,
                        'tls' => ['peer_name' => 'urn:php-java-bridge:demo:other'] + $tls]);
        fails("not issued to 'urn:php-java-bridge:demo:other'", static fn () => call());
    }
});

test('client allowed by a URI name', static function () use ($pki, $memory, $files): void {
    $byUri = server('--security', 'tls-certificates', '--tls-cert', $pki['server'], '--tls-key', $pki['server-key'],
                    '--tls-ca', $pki['ca'], '--tls-allow-client', 'urn:php-java-bridge:demo:client');
    foreach (['text' => $memory, 'file' => $files] as $how => $tls) {
        java_Client::$instance = null;
        java_Client::$options = [];
        java_configure(['port' => $byUri, 'security' => 'tls-certificates', 'persistent' => false, 'tls' => $tls]);
        check(str_starts_with(call(), 'JavaBridge-connection-'), "call works ({$how})");
        java_Client::$instance->close();
    }
});

test('client certificate from another root: refused (text)', static function () use ($certs, $pki, $other, $pem): void {
    java_configure(['port' => $certs, 'security' => 'tls-certificates', 'persistent' => false, 'tls' => [
        'ca' => $pem($pki['ca']), 'cert' => $pem($other['client']), 'key' => $pem($other['client-key']),
    ]]);
    fails('client certificate rejected: the client certificate is not trusted', static fn () => call());
});

test('client certificate from another root: refused (file)', static function () use ($certs, $pki, $other): void {
    java_configure(['port' => $certs, 'security' => 'tls-certificates', 'persistent' => false, 'connect_timeout' => 5, 'tls' => [
        'ca_file' => $pki['ca'], 'cert_file' => $other['client'], 'key_file' => $other['client-key'],
    ]]);
    fails('', static fn () => call());
});

test('key that does not belong to the certificate: refused', static function () use ($certs, $memory, $other, $pem): void {
    java_configure(['port' => $certs, 'security' => 'tls-certificates', 'persistent' => false,
                    'tls' => ['key' => $pem($other['client-key'])] + $memory]);
    fails('the signature does not match the certificate', static fn () => call());
});

test('tls-anonymous client at a tls-certificates server: refused', static function () use ($certs): void {
    java_configure(['port' => $certs, 'persistent' => false]);
    fails('requires a client certificate', static fn () => call());
});

test('client name not on the allow list: refused', static function () use ($pki, $memory, $files): void {
    $strict = server('--security', 'tls-certificates', '--tls-cert', $pki['server'], '--tls-key', $pki['server-key'],
                     '--tls-ca', $pki['ca'], '--tls-allow-client', 'someone-else');
    java_configure(['port' => $strict, 'security' => 'tls-certificates', 'persistent' => false, 'tls' => $memory]);
    fails('not one of the allowed clients', static fn () => call());
    java_Client::$instance = null;
    java_Client::$options = [];
    java_configure(['port' => $strict, 'security' => 'tls-certificates', 'persistent' => false, 'connect_timeout' => 5,
                    'tls' => $files]);
    fails('', static fn () => call());
});

// ---------------------------------------------------------------- clean up

foreach ($servers as [$process, $log]) {
    proc_terminate($process);
    proc_close($process);
    @unlink($log);
}
foreach (['a', 'b'] as $d) {
    array_map('unlink', glob("{$tmp}/{$d}/*.pem"));
    rmdir("{$tmp}/{$d}");
}
rmdir($tmp);

echo "\n{$passed} passed, {$failed} failed\n";
exit($failed > 0 ? 1 : 0);
