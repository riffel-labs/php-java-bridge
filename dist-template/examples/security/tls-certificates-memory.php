<?php

/**
 * tls-certificates-memory.php - both sides prove who they are, with
 * certificates the client holds as text: nothing is read from a file by the
 * TLS layer. For keys that come from a secret store, a vault or another
 * process instead of the disk.
 *
 * Start the server exactly as for tls-certificates-files.php (steps 1 and 2
 * there), then run:
 *
 *     php examples/security/tls-certificates-memory.php
 *
 * PHP's TLS layer can take certificates only from files, so with text the
 * client does the two checks itself, right after the TLS handshake:
 *
 * - it checks the server's certificate against the root (signatures, validity
 *   dates, the server name), before it sends anything else;
 * - the server sends a random challenge and the client signs it, together with
 *   the server's certificate, with its private key; the server checks the
 *   signature and the client certificate.
 *
 * The connection is still encrypted by TLS. See docs/security.md.
 */

require __DIR__ . '/../../client/JavaBridge.php';

$certs = __DIR__ . '/certs';
if (!is_file("{$certs}/ca.pem")) {
    exit("Make the demo certificates first (step 1 in tls-certificates-files.php).\n");
}

/**
 * Stands in for a secret store: returns the PEM text. The client calls it only
 * when it opens a new connection; a persistent connection reused by a later
 * PHP request is already checked and does not need it.
 *
 * @return array{ca: string, cert: string, key: string}
 */
function credentials(): array
{
    $certs = __DIR__ . '/certs';
    echo "(credentials requested)\n";
    return [
        'ca' => file_get_contents("{$certs}/ca.pem"),
        'cert' => file_get_contents("{$certs}/client.pem"),
        'key' => file_get_contents("{$certs}/client-key.pem"),
    ];
}

java_configure([
    'port' => 9267,
    'security' => 'tls-certificates',
    'tls' => [
        'credentials' => 'credentials',
        // or give the text directly: 'ca' => $caPem, 'cert' => $certPem, 'key' => $keyPem
    ],
]);

$version = java('java.lang.System')->getProperty('java.version');
echo "Mutual authentication with PEM text: connected to Java ", $version, "\n";
