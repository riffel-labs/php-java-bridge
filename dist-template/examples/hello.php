<?php

/**
 * hello.php - a first look at the PHP/Java Bridge client.
 *
 * Start the bridge server, then run:
 *
 *     php examples/hello.php
 *
 * The client connects to 127.0.0.1:9267. Set JAVA_BRIDGE_ADDRESS=host:port
 * to use another address, and JAVA_BRIDGE_SECURITY=plain for a server started
 * with --security plain.
 */

// Load the client. In the release zip it sits in client/ next to examples/;
// in the source repository it is two levels up.
$clientFile = __DIR__ . '/../client/JavaBridge.php';
if (!file_exists($clientFile)) {
    $clientFile = __DIR__ . '/../../client/JavaBridge.php';
}
require $clientFile;

// Tell the client where the server is. This must happen before the first
// Java call; the connection is opened by that call.
[$host, $port] = explode(':', getenv('JAVA_BRIDGE_ADDRESS') ?: '127.0.0.1:9267', 2);
// The server's default security is tls-anonymous (needs PHP's openssl extension); for a server started
// with --security plain, set JAVA_BRIDGE_SECURITY=plain.
java_configure(['host' => $host, 'port' => (int) $port, 'security' => getenv('JAVA_BRIDGE_SECURITY') ?: 'tls-anonymous']);

// 1. Static methods: java('class name') returns the Java class.
//    getProperty() returns a Java String; echo turns it into PHP text.
$version = java('java.lang.System')->getProperty('java.version');
echo "Java version: ", $version, "\n";

// 2. Objects: new Java('class name', constructor arguments...) creates a
//    Java object. Its methods are called like PHP methods, and calls can be
//    chained because append() returns the StringBuilder itself.
$greeting = new Java('java.lang.StringBuilder', 'Hello');
$greeting->append(', ')->append('world')->append('!');
echo $greeting, "\n";

// 3. Collections: fill a java.util.ArrayList, then convert it to a PHP array
//    with java_values(). java_values() also converts single values, like the
//    int returned by size().
$fruits = new Java('java.util.ArrayList');
foreach (['apple', 'banana', 'cherry'] as $fruit) {
    $fruits->add($fruit);
}
echo "The list has ", java_values($fruits->size()), " elements:\n";
print_r(java_values($fruits));

// 4. Exceptions: a Java exception arrives as a PHP exception (JavaException)
//    that you can catch. $e->toString() calls Java's toString() and describes
//    what went wrong on the Java side.
try {
    java('java.lang.Integer')->parseInt('not a number');
} catch (JavaException $e) {
    echo "Caught a Java exception: ", $e->toString(), "\n";
}
