<?php

/**
 * tls-anonymous.php - the default: an encrypted connection, nobody authenticated.
 *
 * Start the server with its defaults:
 *
 *     server/start-server.cmd                 (Windows)
 *     sh server/start-server.sh               (Linux, macOS)
 *
 * then run:
 *
 *     php examples/security/tls-anonymous.php
 *
 * The server makes a new key and certificate every time it starts; its log
 * line shows the certificate's SHA-256 fingerprint. The client encrypts the
 * connection without checking the certificate, so this protects against
 * someone reading the traffic, not against someone pretending to be the
 * server. Anyone who can reach the port can connect: keep the server on
 * 127.0.0.1. Needs PHP's openssl extension.
 */

require __DIR__ . '/../../client/JavaBridge.php';

java_configure([
    'port' => 9267,
    // 'security' => 'tls-anonymous' is the default
]);

$version = java('java.lang.System')->getProperty('java.version');
echo "Connected over TLS to Java ", $version, "\n";
echo "The server sees the connection as: ",
     java_values(java('java.lang.Thread')->currentThread()->getName()), "\n";
