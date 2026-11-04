<?php

/**
 * tls-certificates-files.php - both sides prove who they are, with
 * certificates read from files.
 *
 * 1. Make demo certificates (a root, a server certificate for localhost and
 *    127.0.0.1, and a client certificate named php-client):
 *
 *        server/start-server.cmd --make-demo-certs examples/security/certs        (Windows)
 *        sh server/start-server.sh --make-demo-certs examples/security/certs      (Linux, macOS)
 *
 * 2. Start the server with them:
 *
 *        server/start-server.cmd --security tls-certificates ^
 *            --tls-cert examples/security/certs/server.pem ^
 *            --tls-key examples/security/certs/server-key.pem ^
 *            --tls-ca examples/security/certs/ca.pem ^
 *            --tls-allow-client php-client
 *
 *    (with sh server/start-server.sh, end the lines with \ instead of ^)
 *
 * 3. Run:
 *
 *        php examples/security/tls-certificates-files.php
 *
 * PHP checks the server's certificate against ca.pem, and presents its own
 * certificate in the TLS handshake; the server checks it against the same
 * root and against the --tls-allow-client names. This is ordinary mutual TLS.
 * Use your own CA for real deployments; the demo root's key is thrown away.
 */

require __DIR__ . '/../../client/JavaBridge.php';

$certs = __DIR__ . '/certs';
if (!is_file("{$certs}/ca.pem")) {
    exit("Make the demo certificates first (step 1 at the top of this file).\n");
}

java_configure([
    'port' => 9267,
    'security' => 'tls-certificates',
    'tls' => [
        'ca_file' => "{$certs}/ca.pem",            // the root the server certificate must chain to
        'cert_file' => "{$certs}/client.pem",      // this client's certificate
        'key_file' => "{$certs}/client-key.pem",   // and its private key
        // 'peer_name' => 'localhost',             // the name the server certificate must carry; default: the host
                                                   // (a URI works too: 'urn:php-java-bridge:demo:server')
    ],
]);

$version = java('java.lang.System')->getProperty('java.version');
echo "Mutual TLS with files: connected to Java ", $version, "\n";
