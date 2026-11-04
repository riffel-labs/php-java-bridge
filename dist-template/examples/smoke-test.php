<?php

/**
 * smoke-test.php - quick check that the client and the server work together.
 *
 * Start the bridge server, then run:
 *
 *     php examples/smoke-test.php
 *
 * Prints PASS or FAIL for each check and exits with status 1 if any check
 * fails. The client connects to 127.0.0.1:9267; set JAVA_BRIDGE_ADDRESS=host:port
 * to use another address, and JAVA_BRIDGE_SECURITY=plain for a server started
 * with --security plain.
 */

error_reporting(E_ALL);

$clientFile = __DIR__ . '/../client/JavaBridge.php';
if (!file_exists($clientFile)) {
    $clientFile = __DIR__ . '/../../client/JavaBridge.php';
}
require $clientFile;

$address = getenv('JAVA_BRIDGE_ADDRESS') ?: '127.0.0.1:9267';
[$host, $port] = explode(':', $address, 2);
java_configure([
    'host' => $host,
    'port' => (int) $port,
    'connect_timeout' => 5,
    'security' => getenv('JAVA_BRIDGE_SECURITY') ?: 'tls-anonymous',
]);

/** @var array<string, callable(): bool> $checks */
$checks = [
    'connect' => static fn (): bool => java_values(java('java.lang.System')->currentTimeMillis()) > 0,
    'create an object' => static fn (): bool => (string) new Java('java.lang.StringBuilder', 'abc') === 'abc',
    'call a method' => static fn (): bool => java_values((new Java('java.lang.String', 'Hello'))->toUpperCase()) === 'HELLO',
    'call a static method' => static fn (): bool => java_values(java('java.lang.Integer')->parseInt('42')) === 42,
    'convert values' => static function (): bool {
        $map = new Java('java.util.LinkedHashMap');
        $map->put('list', new Java('java.util.ArrayList', [1, 'two', true, null]));
        return java_values($map) === ['list' => [1, 'two', true, null]];
    },
    'foreach' => static function (): bool {
        $seen = [];
        foreach (new Java('java.util.ArrayList', ['a', 'b', 'c']) as $index => $value) {
            $seen[$index] = java_values($value);
        }
        return $seen === ['a', 'b', 'c'];
    },
    'catch a Java exception' => static function (): bool {
        try {
            java('java.lang.Integer')->parseInt('x');
        } catch (JavaException $e) {
            return str_contains((string) $e, 'NumberFormatException');
        }
        return false;
    },
    'unicode' => static function (): bool {
        $text = new Java('java.lang.String', 'Grüße 😀');
        return java_values($text) === 'Grüße 😀' && java_values($text->length()) === 8;
    },
];

echo "PHP/Java Bridge smoke test against {$address}\n";
$failed = 0;
foreach ($checks as $name => $check) {
    try {
        $ok = $check();
        $detail = $ok ? '' : 'unexpected result';
    } catch (Throwable $e) {
        $ok = false;
        $detail = get_class($e) . ': ' . strtok((string) $e, "\n");
    }
    echo $ok ? 'PASS' : 'FAIL', " {$name}", $detail === '' ? '' : ": {$detail}", "\n";
    if (!$ok) {
        $failed++;
        if ($name === 'connect') {
            echo "Is the bridge server running at {$address}?\n";
            exit(1);
        }
    }
}

echo $failed === 0 ? "All checks passed.\n" : "{$failed} check(s) failed.\n";
exit($failed === 0 ? 0 : 1);
