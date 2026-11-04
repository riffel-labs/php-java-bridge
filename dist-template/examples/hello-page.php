<?php

/**
 * hello-page.php - the hello example as a web page.
 *
 * Start the bridge server, then from the release folder run:
 *
 *     php -S localhost:8000 -t examples
 *
 * and open http://localhost:8000/hello-page.php
 *
 * The client connects to 127.0.0.1:9267. Set JAVA_BRIDGE_ADDRESS=host:port
 * (in the environment of the php -S process) to use another address.
 */

$clientFile = __DIR__ . '/../client/JavaBridge.php';
if (!file_exists($clientFile)) {
    $clientFile = __DIR__ . '/../../client/JavaBridge.php';
}
require $clientFile;

/**
 * Escapes text for HTML output.
 */
function h($text): string
{
    return htmlspecialchars((string) $text, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

[$host, $port] = explode(':', getenv('JAVA_BRIDGE_ADDRESS') ?: '127.0.0.1:9267', 2);
java_configure(['host' => $host, 'port' => (int) $port, 'security' => getenv('JAVA_BRIDGE_SECURITY') ?: 'tls-anonymous']);

$error = null;
try {
    $version = (string) java('java.lang.System')->getProperty('java.version');

    $greeting = new Java('java.lang.StringBuilder', 'Hello');
    $greeting->append(', ')->append('world')->append('!');
    $greeting = (string) $greeting;

    $fruits = new Java('java.util.ArrayList');
    foreach (['apple', 'banana', 'cherry'] as $fruit) {
        $fruits->add($fruit);
    }
    $fruits = java_values($fruits);

    try {
        java('java.lang.Integer')->parseInt('not a number');
        $exception = 'none';
    } catch (JavaException $e) {
        $exception = (string) $e->toString();
    }
} catch (java_ConnectException $e) {
    $error = $e->getMessage() . '. Is the bridge server running?';
}
?>
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Hello from Java</title>
    <style>
        body { font-family: system-ui, sans-serif; max-width: 40rem; margin: 2rem auto; padding: 0 1rem; line-height: 1.5; }
        code { background: #f2f2f2; padding: 0 .25rem; }
        .error { color: #a00; }
    </style>
</head>
<body>
<h1>Hello from Java</h1>
<?php if ($error !== null): ?>
    <p class="error"><?= h($error) ?></p>
<?php else: ?>
    <p>Java version: <code><?= h($version) ?></code></p>
    <p>A <code>java.lang.StringBuilder</code> says: <?= h($greeting) ?></p>
    <p>A <code>java.util.ArrayList</code>, converted with <code>java_values()</code>:</p>
    <ul>
        <?php foreach ($fruits as $fruit): ?>
            <li><?= h($fruit) ?></li>
        <?php endforeach; ?>
    </ul>
    <p>A caught Java exception:</p>
    <pre><?= h($exception) ?></pre>
<?php endif; ?>
</body>
</html>
