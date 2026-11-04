<?php

/**
 * plain.php - no encryption, no authentication.
 *
 * For a loopback-only setup where you trust every local process and do not
 * want TLS (or PHP has no openssl extension). Start the server with:
 *
 *     server/start-server.cmd --security plain          (Windows)
 *     sh server/start-server.sh --security plain        (Linux, macOS)
 *
 * then run:
 *
 *     php examples/security/plain.php
 *
 * Both sides must agree: a TLS client cannot talk to a plain server and the
 * other way around.
 */

require __DIR__ . '/../../client/JavaBridge.php';

java_configure([
    'port' => 9267,
    'security' => 'plain',
]);

$version = java('java.lang.System')->getProperty('java.version');
echo "Connected without TLS to Java ", $version, "\n";
