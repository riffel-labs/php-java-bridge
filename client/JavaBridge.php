<?php

/**
 * JavaBridge.php - PHP client for the PHP/Java Bridge server.
 *
 * Copyright (C) 2003-2010 Jost Boekemeier and others.
 * Distributed under the MIT license.
 *
 * Copyright (c) 2026 Jason Riffel (changes in this fork).
 *
 * Fork of the PHP/Java Bridge 6.2.1 Java.inc.
 * See LICENSE in the project root.
 *
 * Requires PHP 8.2 or later and a running bridge server of the same release.
 * The TLS security modes (the default is tls-anonymous) need ext-openssl.
 * Messages are JSON (ext-json is always part of PHP 8).
 *
 * Example:
 *
 *     require_once 'JavaBridge.php';
 *
 *     $list = new Java('java.util.ArrayList');
 *     $list->add('hello');
 *     echo java('java.lang.Math')->max(1, 2), "\n";
 *     print_r(java_values($list));
 *
 * Configuration (optional), before the first Java call:
 *
 *     java_configure([
 *         'host'            => '127.0.0.1',     // server host
 *         'port'            => 9267,            // server port
 *         'security'        => 'tls-anonymous', // must match the server: tls-anonymous, tls-certificates or plain
 *         'tls'             => [],              // certificates for tls-certificates, see docs/security.md
 *         'persistent'      => true,            // reuse the connection across PHP requests
 *         'connect_timeout' => 20,              // seconds
 *         'prefer_values'   => false,           // return results as PHP values instead of proxies
 *         'logger'          => null,            // callable(string $level, string $message): void
 *     ]);
 *
 * Without a logger, diagnostics are raised as E_USER_WARNING.
 *
 * Constants that are read when defined before the first Java call:
 *
 *     JAVA_HOSTS          "host:port" or a port number: default host and port
 *     JAVA_PREFER_VALUES  default for 'prefer_values'
 *     JAVA_LOG_LEVEL      0-6: the server's log level for this connection
 *     JAVA_SEND_SIZE      send buffer threshold in bytes for calls sent without waiting (default 8192)
 *
 * See docs/php-client.md for the full guide.
 */

if (!defined('JAVA_SEND_SIZE')) {
    define('JAVA_SEND_SIZE', 8192);
}

class java_JavaException extends Exception
{
    public function __toString(): string
    {
        return $this->getMessage();
    }
}

class java_RuntimeException extends java_JavaException
{
}

class java_IOException extends java_JavaException
{
}

class java_ConnectException extends java_IOException
{
}

class java_IllegalStateException extends java_RuntimeException
{
}

class java_IllegalArgumentException extends java_RuntimeException
{
    public function __construct($ob)
    {
        parent::__construct('illegal argument: ' . gettype($ob));
    }
}

/**
 * Marker interface implemented by every PHP object that stands for a Java object.
 */
interface java_JavaType
{
}

/**
 * Turns a decoded result into the value handed to PHP code. The simple factory
 * passes values through unchanged; the subclasses create proxies.
 */
class java_SimpleFactory
{
    public java_Client $client;

    public function __construct(java_Client $client)
    {
        $this->client = $client;
    }

    public function getProxy($result, $signature, $exception, $wrap)
    {
        return $result;
    }

    public function checkResult($result): void
    {
    }
}

class java_ProxyFactory extends java_SimpleFactory
{
    public function create($result, $signature)
    {
        return new java_JavaProxy($result, $signature);
    }

    public function createInternal($proxy)
    {
        return new java_InternalJava($proxy);
    }

    public function getProxy($result, $signature, $exception, $wrap)
    {
        $proxy = $this->create($result, $signature);
        if ($wrap) {
            $proxy = $this->createInternal($proxy);
        }
        return $proxy;
    }
}

class java_ArrayProxyFactory extends java_ProxyFactory
{
    public function create($result, $signature)
    {
        return new java_ArrayProxy($result, $signature);
    }
}

class java_IteratorProxyFactory extends java_ProxyFactory
{
    public function create($result, $signature)
    {
        return new java_IteratorProxy($result, $signature);
    }
}

class java_ExceptionProxyFactory extends java_SimpleFactory
{
    public function create($result, $signature)
    {
        return new java_ExceptionProxy($result, $signature);
    }

    public function getProxy($result, $signature, $exception, $wrap)
    {
        $proxy = $this->create($result, $signature);
        if ($wrap) {
            $proxy = new java_InternalException($proxy, $exception);
        }
        return $proxy;
    }
}

/**
 * Factory for exceptions returned by a call. Declared exceptions (and all
 * exceptions when values are preferred) are thrown; undeclared ones are
 * reported as a warning and returned as the call's result.
 */
class java_ThrowExceptionProxyFactory extends java_ExceptionProxyFactory
{
    public function getProxy($result, $signature, $exception, $wrap)
    {
        return new java_InternalException($this->create($result, $signature), $exception);
    }

    public function checkResult($result): void
    {
        if ($this->client->preferValues || $result->__hasDeclaredExceptions == 'T') {
            throw $result;
        }
        $this->client->log('warning', 'Unchecked exception detected: ' . java_truncate((string) $result));
    }
}

/**
 * What the client remembers about a method or constructor signature whose
 * first call declared no checked exceptions. Later calls with the same
 * signature are sent without waiting for the reply.
 */
class java_CacheEntry
{
    public function __construct(
        public string $signature,
        public java_SimpleFactory $factory,
        public bool $resultVoid
    ) {
    }
}

/**
 * The result of the current request.
 */
class java_Arg
{
    public java_Client $client;
    public java_SimpleFactory $factory;
    public $exception = null;
    public $val = null;
    public $signature = null;

    public function __construct(java_Client $client)
    {
        $this->client = $client;
        $this->factory = $client->simpleFactory;
    }

    public function getResult($wrap)
    {
        $rc = $this->factory->getProxy($this->val, $this->signature, $this->exception, $wrap);
        $factory = $this->factory;
        $this->factory = $this->client->simpleFactory;
        $factory->checkResult($rc);
        return $rc;
    }

    public function setVoidSignature(): void
    {
        $this->signature = '@V';
        $key = $this->client->currentCacheKey;
        if ($key && $key[0] != '~') {
            $this->client->methodCache[$key] = new java_CacheEntry($this->signature, $this->factory, true);
        }
    }

    public function setSignature($signature): void
    {
        $this->signature = $signature;
        $key = $this->client->currentCacheKey;
        if ($key && $key[0] != '~') {
            $this->client->methodCache[$key] = new java_CacheEntry($this->signature, $this->factory, false);
        }
    }
}

/**
 * The connection to the server: TCP or TLS, the opening "hello" and, if the
 * server asks for it, the client's proof of identity. Messages are JSON
 * documents prefixed with their length.
 */
class java_SocketChannel
{
    private java_Client $client;
    private $peer;
    public bool $persistent;
    private array $config;
    private ?array $credentials = null;

    public function __construct(java_Client $client, array $config)
    {
        $this->client = $client;
        $this->config = $config;
        $this->persistent = $config['persistent'];
        $security = $config['security'];
        $tls = $config['tls'];
        $host = $config['host'];
        $port = $config['port'];
        $address = (str_contains($host, ':') && $host[0] !== '[') ? "[{$host}]" : $host;

        $ssl = [];
        $scheme = 'tcp';
        if ($security !== 'plain') {
            if (!extension_loaded('openssl')) {
                throw new java_ConnectException(
                    "security '{$security}' needs the PHP openssl extension; enable it, or run the server and the client with security 'plain'"
                );
            }
            $scheme = 'tls';
            $ssl = [
                'crypto_method' => STREAM_CRYPTO_METHOD_TLSv1_2_CLIENT | STREAM_CRYPTO_METHOD_TLSv1_3_CLIENT,
                'capture_peer_cert' => true,
                'capture_peer_cert_chain' => true,
                'verify_peer' => false,
                'verify_peer_name' => false,
                'allow_self_signed' => true,
            ];
            if ($security === 'tls-certificates') {
                self::checkTlsConfig($tls);
                if (isset($tls['ca_file'])) {
                    $peerName = $tls['peer_name'] ?? $host;
                    $ssl['verify_peer'] = true;
                    $ssl['allow_self_signed'] = false;
                    $ssl['cafile'] = $tls['ca_file'];
                    if (self::isUri($peerName)) {
                        // PHP's TLS layer matches host names only: the name is checked below, after the handshake
                        $ssl['verify_peer_name'] = false;
                        $uriPeerName = $peerName;
                    } else {
                        $ssl['verify_peer_name'] = true;
                        $ssl['peer_name'] = $peerName;
                    }
                }
                if (isset($tls['cert_file'])) {
                    $ssl['local_cert'] = $tls['cert_file'];
                    $ssl['local_pk'] = $tls['key_file'];
                    if (isset($tls['key_passphrase'])) {
                        $ssl['passphrase'] = $tls['key_passphrase'];
                    }
                }
            }
        }

        $errno = 0;
        $errstr = '';
        $flags = STREAM_CLIENT_CONNECT | ($this->persistent ? STREAM_CLIENT_PERSISTENT : 0);
        $peer = @stream_socket_client(
            "{$scheme}://{$address}:{$port}",
            $errno,
            $errstr,
            $config['connect_timeout'],
            $flags,
            stream_context_create(['ssl' => $ssl])
        );
        if ($peer === false) {
            $hint = $scheme === 'tls' && $errno === 0
                ? " (TLS handshake failed: is the server running with --security {$security}? Are the certificates right?)"
                : '';
            throw new java_ConnectException(
                "Could not connect to the Java server at {$host}:{$port}: {$errstr} ({$errno}){$hint}"
            );
        }
        $this->peer = $peer;

        // A persistent connection that was opened and accepted by an earlier
        // PHP request has read and written bytes already.
        if (ftell($peer) === 0) {
            stream_set_timeout($peer, (int) ceil($config['connect_timeout']));
            if (isset($uriPeerName)) {
                $leaf = $this->peerCertificate();
                if ($leaf === null || !self::certificateNames(openssl_x509_parse($leaf), $uriPeerName)) {
                    $this->fail("the server certificate is not issued to '{$uriPeerName}' (set tls 'peer_name' to the name it carries)");
                }
            }
            $this->hello($security, $tls, $host);
        }
        stream_set_timeout($peer, -1);
    }

    private static function checkTlsConfig(array $tls): void
    {
        $dynamic = isset($tls['credentials']);
        if (!$dynamic && !isset($tls['ca']) && !isset($tls['ca_file'])) {
            throw new java_ConnectException("security 'tls-certificates' needs the server's root certificate: tls 'ca' or 'ca_file'");
        }
        if (isset($tls['cert_file']) !== isset($tls['key_file'])) {
            throw new java_ConnectException("tls 'cert_file' and 'key_file' go together");
        }
        if (!$dynamic && !isset($tls['cert_file']) && (!isset($tls['cert']) || !isset($tls['key']))) {
            throw new java_ConnectException(
                "security 'tls-certificates' needs a client certificate: tls 'cert' and 'key', or 'cert_file' and 'key_file'"
            );
        }
    }

    /**
     * The certificate settings, with the values from the 'credentials'
     * callable, which is called at most once per connection.
     */
    private function credentials(): array
    {
        if ($this->credentials === null) {
            $tls = $this->config['tls'];
            if (isset($tls['credentials'])) {
                $more = ($tls['credentials'])();
                if (!is_array($more)) {
                    $this->fail("the tls 'credentials' callable must return an array");
                }
                $tls = array_merge($tls, $more);
            }
            $this->credentials = $tls;
        }
        return $this->credentials;
    }

    private function hello(string $security, array $tls, string $host): void
    {
        if ($security === 'tls-certificates' && !isset($tls['ca_file'])) {
            $creds = $this->credentials();
            if (!isset($creds['ca'])) {
                $this->fail("security 'tls-certificates' needs the server's root certificate: tls 'ca' or 'ca_file'");
            }
            $this->verifyServerCertificate($creds['ca'], $creds['peer_name'] ?? $host);
        }
        $this->write(java_Client::frame('{"op":"hello","v":2}'));
        try {
            $reply = $this->readMessage();
        } catch (java_IOException $e) {
            $hint = str_contains($e->getMessage(), 'unexpectedly')
                ? ". Does the server's --security match 'security' => '{$security}' here?"
                : '';
            $this->fail($e->getMessage() . $hint);
        }
        if (($reply['hello'] ?? null) !== 'php-java-bridge' || ($reply['v'] ?? null) !== 2) {
            $this->fail('the server is not a PHP/Java Bridge server of this version: ' . java_truncate(json_encode($reply)));
        }
        if (isset($reply['challenge'])) {
            $this->prove($security, $reply['challenge']);
        }
    }

    /**
     * Prove the client's identity: sign the server's challenge and the server
     * certificate of this connection with the client's private key.
     */
    private function prove(string $security, string $challenge): void
    {
        $creds = $security === 'tls-certificates' ? $this->credentials() : [];
        if (!isset($creds['cert'], $creds['key'])) {
            $this->fail(
                "the Java server requires a client certificate: use security 'tls-certificates' with tls 'cert' and 'key' (or 'cert_file' and 'key_file')"
            );
        }
        $server = $this->peerCertificate();
        $nonce = base64_decode($challenge, true);
        if ($server === null || $nonce === false) {
            $this->fail('cannot answer the server\'s challenge');
        }
        $key = openssl_pkey_get_private($creds['key'], $creds['key_passphrase'] ?? '');
        if ($key === false) {
            $this->fail("cannot read the tls 'key': " . self::opensslErrors());
        }
        $data = "php-java-bridge-auth-v1\0" . $nonce . openssl_x509_fingerprint($server, 'sha256', true);
        $signature = '';
        if (!openssl_sign($data, $signature, $key, OPENSSL_ALGO_SHA256)) {
            $this->fail('cannot sign the server\'s challenge: ' . self::opensslErrors());
        }
        $this->write(java_Client::frame(json_encode(
            ['op' => 'auth', 'cert' => $creds['cert'], 'sig' => base64_encode($signature)],
            JSON_UNESCAPED_SLASHES
        )));
        $reply = $this->readMessage();
        if (($reply['auth'] ?? null) !== 'ok') {
            $this->fail('the server did not accept the client certificate: ' . java_truncate(json_encode($reply)));
        }
    }

    private function peerCertificate()
    {
        $params = stream_context_get_params($this->peer);
        return $params['options']['ssl']['peer_certificate'] ?? null;
    }

    /**
     * Check the server's certificate against root certificates given as PEM
     * text: PHP's own TLS check needs them in a file ('ca_file'). The chain
     * must lead to one of the roots by valid signatures, every certificate
     * must be within its validity period, intermediates must be CAs, and the
     * server certificate must name $peerName and allow TLS server use.
     */
    private function verifyServerCertificate(string $caPem, string $peerName): void
    {
        $params = stream_context_get_params($this->peer);
        $ssl = $params['options']['ssl'] ?? [];
        $leaf = $ssl['peer_certificate'] ?? null;
        if ($leaf === null) {
            $this->fail('the server sent no certificate');
        }
        $roots = self::readCertificates($caPem);
        if ($roots === []) {
            $this->fail("tls 'ca' contains no certificate");
        }
        $info = openssl_x509_parse($leaf);
        self::checkValidity($info) or $this->fail('the server certificate is expired or not yet valid');
        $eku = $info['extensions']['extendedKeyUsage'] ?? null;
        if ($eku !== null && !str_contains($eku, 'TLS Web Server Authentication') && !str_contains($eku, 'Any Extended Key Usage')) {
            $this->fail('the server certificate is not meant for TLS servers');
        }
        if (!self::certificateNames($info, $peerName)) {
            $this->fail("the server certificate is not issued to '{$peerName}' (set tls 'peer_name' to the name it carries)");
        }
        $intermediates = array_slice($ssl['peer_certificate_chain'] ?? [], 1);
        $current = $leaf;
        $currentInfo = $info;
        for ($depth = 0; $depth < 8; $depth++) {
            foreach ($roots as $root) {
                $rootInfo = openssl_x509_parse($root);
                if ($currentInfo['issuer'] == $rootInfo['subject'] && openssl_x509_verify($current, $root) === 1) {
                    self::checkValidity($rootInfo) or $this->fail('the root certificate in tls \'ca\' is expired');
                    return;
                }
            }
            $next = null;
            foreach ($intermediates as $i => $candidate) {
                $candidateInfo = openssl_x509_parse($candidate);
                if ($currentInfo['issuer'] == $candidateInfo['subject']
                    && openssl_x509_verify($current, $candidate) === 1
                    && str_contains($candidateInfo['extensions']['basicConstraints'] ?? '', 'CA:TRUE')
                    && self::checkValidity($candidateInfo)) {
                    $next = $candidate;
                    $nextInfo = $candidateInfo;
                    unset($intermediates[$i]);
                    break;
                }
            }
            if ($next === null) {
                break;
            }
            $current = $next;
            $currentInfo = $nextInfo;
        }
        $this->fail("the server certificate is not issued by a root in tls 'ca'");
    }

    private static function readCertificates(string $pem): array
    {
        preg_match_all('/-----BEGIN CERTIFICATE-----.+?-----END CERTIFICATE-----/s', $pem, $m);
        $certs = [];
        foreach ($m[0] as $block) {
            $cert = openssl_x509_read($block);
            if ($cert !== false) {
                $certs[] = $cert;
            }
        }
        return $certs;
    }

    private static function checkValidity(array $info): bool
    {
        $now = time();
        return $now >= $info['validFrom_time_t'] && $now <= $info['validTo_time_t'];
    }

    /** Whether a name is a URI, such as urn:example:service:bridge, rather than a host name or IP address. */
    private static function isUri(string $name): bool
    {
        return preg_match('/^[A-Za-z][A-Za-z0-9+.-]*:.+$/', $name) === 1 && @inet_pton(trim($name, '[]')) === false;
    }

    /**
     * Whether the certificate carries the name: as its common name, a DNS name or URI of its subject alternative
     * names, or, for an IP address, an IP address there.
     */
    private static function certificateNames(array $info, string $name): bool
    {
        $ip = @inet_pton(trim($name, '[]'));
        $names = [];
        if (isset($info['subject']['CN'])) {
            $names = array_merge($names, (array) $info['subject']['CN']);
        }
        foreach (explode(',', $info['extensions']['subjectAltName'] ?? '') as $entry) {
            $entry = trim($entry);
            if (str_starts_with($entry, 'DNS:')) {
                $names[] = substr($entry, 4);
            } elseif (str_starts_with($entry, 'URI:')) {
                if (substr($entry, 4) === $name) {
                    return true; // URIs compare exactly
                }
            } elseif (str_starts_with($entry, 'IP Address:') && $ip !== false) {
                if (@inet_pton(substr($entry, 11)) === $ip) {
                    return true;
                }
            }
        }
        foreach ($names as $n) {
            if (strcasecmp($n, $name) === 0) {
                return true;
            }
        }
        return false;
    }

    private static function opensslErrors(): string
    {
        $errors = [];
        while (($e = openssl_error_string()) !== false) {
            $errors[] = $e;
        }
        return $errors === [] ? 'unknown error' : implode('; ', $errors);
    }

    /**
     * A failure while the connection is set up: close it, so that a
     * persistent connection is never reused unchecked, and throw.
     */
    private function fail(string $message): never
    {
        if (is_resource($this->peer)) {
            @stream_socket_shutdown($this->peer, STREAM_SHUT_RDWR);
            fclose($this->peer);
        }
        throw new java_ConnectException("Could not connect to the Java server: {$message}");
    }

    public function write(string $data): void
    {
        $length = strlen($data);
        for ($written = 0; $written < $length; $written += $count) {
            $count = @fwrite($this->peer, $written === 0 ? $data : substr($data, $written));
            if (!$count) {
                $this->shutdownBrokenConnection('Could not send data to the Java server: the connection is broken');
            }
        }
    }

    private function readExactly(int $length): string
    {
        $data = @stream_get_contents($this->peer, $length);
        if ($data === false || strlen($data) !== $length) {
            $this->shutdownBrokenConnection('The Java server closed the connection unexpectedly');
        }
        return $data;
    }

    /**
     * Reads one message. A message with "error" means the server closes the
     * connection; it is raised as a java_IOException.
     */
    public function readMessage(): array
    {
        $length = unpack('N', $this->readExactly(4))[1];
        $body = $length > 0 ? $this->readExactly($length) : '';
        try {
            $message = json_decode($body, true, java_Client::JSON_DEPTH, JSON_THROW_ON_ERROR);
        } catch (JsonException $e) {
            $this->shutdownBrokenConnection('protocol error: ' . $e->getMessage() . ': ' . java_truncate($body));
        }
        if (!is_array($message)) {
            $this->shutdownBrokenConnection('protocol error: ' . java_truncate($body));
        }
        if (isset($message['error'])) {
            $this->shutdownBrokenConnection('The Java server closed the connection: ' . $message['error']);
        }
        return $message;
    }

    /**
     * Reads the server's answer to "end" and closes the socket unless it can
     * be reused. Returns the status letter: A (recycled) or E (closed); lower
     * case if an unchecked exception occurred during the request.
     */
    public function keepAlive(): string
    {
        $status = (string) ($this->readMessage()['end'] ?? '');
        if (!$this->persistent || $status !== 'A') {
            fclose($this->peer);
        }
        return $status;
    }

    public function shutdownBrokenConnection(string $message): never
    {
        if (is_resource($this->peer)) {
            fclose($this->peer);
        }
        $this->client->closed();
        throw new java_IOException($message);
    }
}

/**
 * The bridge client of one PHP request: encodes the calls, decodes the
 * replies, and sends calls whose result is not needed yet without waiting.
 */
class java_Client
{
    /** The client of this PHP request, created by the first Java call. */
    public static ?java_Client $instance = null;

    /** Options passed to java_configure(). */
    public static array $options = [];

    /** Set when json_encode() meets a Java object; see encodeArgs(). */
    public static bool $jsonProxySeen = false;

    public const JSON_FLAGS = JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION;
    public const JSON_DEPTH = 10000;

    public ?java_SocketChannel $channel = null;
    public java_SimpleFactory $simpleFactory;
    public java_ProxyFactory $proxyFactory;
    public java_IteratorProxyFactory $iteratorProxyFactory;
    public java_ArrayProxyFactory $arrayProxyFactory;
    public java_ExceptionProxyFactory $exceptionProxyFactory;
    public java_ThrowExceptionProxyFactory $throwExceptionProxyFactory;
    public java_Arg $simpleArg;
    public array $methodCache = [];
    public ?string $currentCacheKey = null;
    public Java $cachedJavaPrototype;
    public string $sendBuffer = '';
    /** The last call sent without waiting: [request, args], kept back so that it can still drop its result. */
    public ?array $prepared = null;
    public int $asyncCtx = 0;
    public int $cancelProxyCreationTag = 0;
    public bool $inArgs = false;
    public bool $preferValues;
    private ?Closure $logger;

    public function __construct(array $config)
    {
        $this->preferValues = $config['prefer_values'];
        $this->logger = $config['logger'] === null ? null : Closure::fromCallable($config['logger']);
        $this->simpleFactory = new java_SimpleFactory($this);
        $this->proxyFactory = new java_ProxyFactory($this);
        $this->arrayProxyFactory = new java_ArrayProxyFactory($this);
        $this->iteratorProxyFactory = new java_IteratorProxyFactory($this);
        $this->exceptionProxyFactory = new java_ExceptionProxyFactory($this);
        $this->throwExceptionProxyFactory = new java_ThrowExceptionProxyFactory($this);
        $this->cachedJavaPrototype = new java_JavaProxyProxy($this);
        $this->simpleArg = new java_Arg($this);
        $this->channel = new java_SocketChannel($this, $config);
        $begin = ['op' => 'begin', 'values' => $this->preferValues];
        if (defined('JAVA_LOG_LEVEL') && is_int(JAVA_LOG_LEVEL)) {
            $begin['log'] = 7 & JAVA_LOG_LEVEL;
        }
        $this->sendBuffer = self::frame(json_encode($begin));
    }

    /**
     * Returns the client of this PHP request, connecting on first use.
     */
    public static function getInstance(): java_Client
    {
        if (self::$instance === null) {
            self::$instance = new java_Client(self::getConfiguration());
            register_shutdown_function('java_shutdown');
        }
        return self::$instance;
    }

    /**
     * The java_configure() options merged with the defaults.
     *
     * @return array{host: string, port: int, security: string, tls: array, persistent: bool, connect_timeout: float, prefer_values: bool, logger: ?callable}
     */
    private static function getConfiguration(): array
    {
        $host = '127.0.0.1';
        $port = 9267;
        if (defined('JAVA_HOSTS')) {
            [$host, $port] = self::parseHosts(JAVA_HOSTS);
        }
        return self::$options + [
            'host' => $host,
            'port' => $port,
            'security' => 'tls-anonymous',
            'tls' => [],
            'persistent' => true,
            'connect_timeout' => 20.0,
            'prefer_values' => defined('JAVA_PREFER_VALUES') && JAVA_PREFER_VALUES,
            'logger' => null,
        ];
    }

    /**
     * @return array{0: string, 1: int}
     */
    private static function parseHosts($hosts): array
    {
        $hosts = trim((string) $hosts);
        if (preg_match('/^\d+$/', $hosts)) {
            return ['127.0.0.1', self::validateOption('port', $hosts)];
        }
        if (preg_match('/^(.+):(\d+)$/', $hosts, $match)) {
            return [trim($match[1], '[]'), self::validateOption('port', $match[2])];
        }
        throw new InvalidArgumentException("JAVA_HOSTS must be \"host:port\" or a port number, got \"{$hosts}\"");
    }

    private const TLS_STRINGS = ['ca', 'ca_file', 'cert', 'cert_file', 'key', 'key_file', 'key_passphrase', 'peer_name'];

    /**
     * Validates one java_configure() option and returns its normalized value.
     */
    public static function validateOption($name, $value)
    {
        switch ((string) $name) {
            case 'host':
                if (is_string($value) && $value !== '') {
                    return $value;
                }
                $expected = 'a non-empty string';
                break;
            case 'port':
                if (is_string($value) && preg_match('/^\d+$/', $value)) {
                    $value = (int) $value;
                }
                if (is_int($value) && $value >= 1 && $value <= 65535) {
                    return $value;
                }
                $expected = 'an integer between 1 and 65535';
                break;
            case 'security':
                if (in_array($value, ['tls-anonymous', 'tls-certificates', 'plain'], true)) {
                    return $value;
                }
                $expected = "'tls-anonymous', 'tls-certificates' or 'plain'";
                break;
            case 'tls':
                if (is_array($value)) {
                    foreach ($value as $key => $item) {
                        if (in_array($key, self::TLS_STRINGS, true)) {
                            if ($item !== null && !is_string($item)) {
                                throw new InvalidArgumentException("java_configure(): tls '{$key}' must be a string");
                            }
                        } elseif ($key === 'credentials') {
                            if (!is_callable($item)) {
                                throw new InvalidArgumentException("java_configure(): tls 'credentials' must be a callable");
                            }
                        } else {
                            throw new InvalidArgumentException("java_configure(): unknown tls option '{$key}'");
                        }
                    }
                    return array_filter($value, static fn ($item) => $item !== null);
                }
                $expected = 'an array';
                break;
            case 'persistent':
            case 'prefer_values':
                if (is_bool($value)) {
                    return $value;
                }
                $expected = 'a boolean';
                break;
            case 'connect_timeout':
                if ((is_int($value) || is_float($value)) && $value > 0) {
                    return (float) $value;
                }
                $expected = 'a number of seconds greater than 0';
                break;
            case 'logger':
                if ($value === null || is_callable($value)) {
                    return $value;
                }
                $expected = 'a callable or null';
                break;
            default:
                throw new InvalidArgumentException("java_configure(): unknown option '{$name}'");
        }
        throw new InvalidArgumentException(
            "java_configure(): '{$name}' must be {$expected}, " . get_debug_type($value) . ' given'
        );
    }

    /**
     * Reports a bridge diagnostic to the configured logger, or as E_USER_WARNING.
     */
    public function log(string $level, string $message): void
    {
        if ($this->logger !== null) {
            ($this->logger)($level, $message);
            return;
        }
        trigger_error($message, E_USER_WARNING);
    }

    /** A message as sent on the wire: its length, then the JSON. */
    public static function frame(string $json): string
    {
        return pack('N', strlen($json)) . $json;
    }

    public function getWrappedResult($wrap)
    {
        return $this->simpleArg->getResult($wrap);
    }

    public function getInternalResult()
    {
        return $this->getWrappedResult(false);
    }

    public function getResult()
    {
        return $this->getWrappedResult(true);
    }

    public function getProxyFactory(string $type): java_SimpleFactory
    {
        switch ($type[0]) {
            case 'E':
                return $this->exceptionProxyFactory;
            case 'C':
                return $this->iteratorProxyFactory;
            case 'A':
                return $this->arrayProxyFactory;
            default:
                return $this->proxyFactory;
        }
    }

    /**
     * Encodes a request. Arguments go out in plain form, where JSON objects
     * are PHP arrays with keys, if json_encode() can write them as they are:
     * no Java objects, all strings UTF-8, no NAN or INF. Otherwise they are
     * converted to the tagged form (see docs/protocol.md).
     */
    private function encode(array $request, array $args): string
    {
        $json = json_encode($request, self::JSON_FLAGS | JSON_INVALID_UTF8_SUBSTITUTE);
        if ($args !== []) {
            [$encoded, $tagged] = $this->encodeArgs(array_values($args));
            $json = substr($json, 0, -1) . ',"args":' . $encoded . ($tagged ? ',"tagged":true}' : '}');
        }
        return self::frame($json);
    }

    /**
     * @return array{0: string, 1: bool} the JSON of the arguments and whether it is in tagged form
     */
    private function encodeArgs(array $args): array
    {
        $this->inArgs = true;
        try {
            self::$jsonProxySeen = false;
            try {
                $json = json_encode($args, self::JSON_FLAGS | JSON_THROW_ON_ERROR, self::JSON_DEPTH);
                if (!self::$jsonProxySeen) {
                    return [$json, false];
                }
            } catch (JsonException) {
                // binary strings, NAN or INF: use the tagged form
            }
            try {
                return [json_encode($this->toWire($args), self::JSON_FLAGS | JSON_THROW_ON_ERROR, self::JSON_DEPTH), true];
            } catch (JsonException $e) {
                throw new InvalidArgumentException('The arguments cannot be sent to Java: ' . $e->getMessage(), 0, $e);
            }
        } finally {
            $this->inArgs = false;
        }
    }

    /** Converts arguments to the tagged form. */
    private function toWire($value)
    {
        if (is_string($value)) {
            return preg_match('//u', $value) ? $value : ['bytes' => base64_encode($value)];
        }
        if (is_array($value)) {
            $out = [];
            foreach ($value as $key => $item) {
                $out[$key] = $this->toWire($item);
            }
            return array_is_list($value) ? $out : ['map' => $out];
        }
        if (is_float($value)) {
            if (is_nan($value)) {
                return ['float' => 'NaN'];
            }
            if (is_infinite($value)) {
                return ['float' => $value > 0 ? 'INF' : '-INF'];
            }
            return $value;
        }
        if ($value instanceof java_JavaType) {
            return ['obj' => $value->__java];
        }
        if (is_object($value)) {
            return $this->toWire($value instanceof JsonSerializable ? $value->jsonSerialize() : get_object_vars($value));
        }
        if (is_resource($value)) {
            $this->log('warning', 'argument of type ' . get_debug_type($value) . ' cannot be passed to Java, using NULL instead');
            return null;
        }
        return $value;
    }

    /** Converts a reply value in tagged form to PHP values and proxies. */
    private function fromWire($value)
    {
        if (!is_array($value)) {
            return $value;
        }
        if (array_is_list($value)) {
            foreach ($value as $i => $item) {
                if (is_array($item)) {
                    $value[$i] = $this->fromWire($item);
                }
            }
            return $value;
        }
        if (isset($value['obj'])) {
            $this->asyncCtx = $value['obj'];
            return $this->getProxyFactory($value['kind'] ?? 'O')->getProxy($value['obj'], $value['class'] ?? null, null, true);
        }
        if (array_key_exists('map', $value)) {
            $map = $value['map'];
            foreach ($map as $key => $item) {
                if (is_array($item)) {
                    $map[$key] = $this->fromWire($item);
                }
            }
            return $map;
        }
        if (isset($value['bytes'])) {
            return base64_decode($value['bytes']);
        }
        if (isset($value['float'])) {
            return match ($value['float']) {
                'NaN' => NAN,
                'INF' => INF,
                default => -INF,
            };
        }
        $this->channel?->shutdownBrokenConnection('protocol error: unknown value ' . java_truncate(json_encode($value)));
        throw new java_IOException('protocol error');
    }

    /** Stores the reply of the current request in simpleArg. */
    private function handleReply(array $reply): void
    {
        $arg = $this->simpleArg;
        $arg->factory = $this->simpleFactory;
        $arg->signature = null;
        $arg->exception = null;
        if (array_key_exists('value', $reply)) {
            $value = $reply['value'];
            if (isset($reply['tagged'])) {
                if (is_array($value) && isset($value['obj'])) {
                    $arg->factory = $this->getProxyFactory($value['kind'] ?? 'O');
                    $arg->val = $this->asyncCtx = $value['obj'];
                    if (empty($value['throws'])) {
                        $arg->setSignature($value['class']);
                    } else {
                        $arg->signature = $value['class'];
                    }
                    return;
                }
                $value = $this->fromWire($value);
            }
            $arg->val = $value;
            return;
        }
        if (isset($reply['void'])) {
            $arg->val = null;
            if (empty($reply['throws'])) {
                $arg->setVoidSignature();
            }
            return;
        }
        if (isset($reply['exception'])) {
            $arg->factory = $this->throwExceptionProxyFactory;
            $arg->exception = empty($reply['throws']) ? 'F' : 'T';
            $arg->val = $this->asyncCtx = $reply['exception'];
            return;
        }
        $this->getChannel()->shutdownBrokenConnection('protocol error: unexpected reply ' . java_truncate(json_encode($reply)));
    }

    /**
     * Returns the channel, or throws if the connection has been closed.
     */
    private function getChannel(): java_SocketChannel
    {
        return $this->channel ?? throw new java_IllegalStateException('The connection to the Java server is closed');
    }

    /** Sends a request with everything queued before it, and reads its reply. */
    private function request(array $request, array $args): void
    {
        $channel = $this->getChannel();
        $this->flushPrepared();
        $this->sendBuffer .= $this->encode($request, $args);
        $channel->write($this->sendBuffer);
        $this->sendBuffer = '';
        $this->handleReply($channel->readMessage());
        $this->currentCacheKey = null;
    }

    public function createObject($name, array $args)
    {
        $this->request(['op' => 'new', 'class' => (string) $name], $args);
        return $this->getInternalResult();
    }

    public function referenceObject($name, array $args)
    {
        $this->request(['op' => 'class', 'class' => (string) $name], $args);
        return $this->getInternalResult();
    }

    public function getProperty($object, $property)
    {
        $this->request(['op' => 'prop', 'obj' => $object, 'name' => (string) $property], []);
        return $this->getResult();
    }

    public function setProperty($object, $property, $arg): void
    {
        $this->request(['op' => 'prop', 'obj' => $object, 'name' => (string) $property], [$arg]);
        $this->getResult();
    }

    public function invokeMethod($object, $method, array $args)
    {
        $this->request(['op' => 'call', 'obj' => $object, 'name' => (string) $method], $args);
        return $this->getResult();
    }

    /**
     * Queues a request that is sent without waiting for its reply. It stays
     * in $prepared until the next request, so that it can still be changed
     * to drop its result (see Java::__destruct()).
     */
    public function deferRequest(array $request, array $args): void
    {
        $this->flushPrepared();
        if (strlen($this->sendBuffer) >= JAVA_SEND_SIZE) {
            $this->getChannel()->write($this->sendBuffer);
            $this->sendBuffer = '';
        }
        $this->prepared = [$request, $args];
    }

    /** Moves the kept-back request into the send buffer. */
    public function flushPrepared(): void
    {
        if ($this->prepared !== null) {
            [$request, $args] = $this->prepared;
            $this->prepared = null;
            $this->sendBuffer .= $this->encode($request, $args);
        }
    }

    public function unref($object): void
    {
        if ($this->channel === null) {
            return;
        }
        $this->flushPrepared();
        $this->sendBuffer .= self::frame('{"op":"unref","obj":' . (int) $object . '}');
    }

    /**
     * Ends the request: sends any queued requests and "end", then keeps the
     * connection for reuse (persistent) or closes it.
     */
    public function close(): void
    {
        $channel = $this->channel;
        if ($channel === null || $this->inArgs) {
            return;
        }
        try {
            $this->flushPrepared();
            $this->sendBuffer .= self::frame($channel->persistent ? '{"op":"end","keep":true}' : '{"op":"end","keep":false}');
            $channel->write($this->sendBuffer);
            $this->sendBuffer = '';
            $status = $channel->keepAlive();
        } catch (java_JavaException $e) {
            $this->log('error', $e->getMessage());
            return;
        } finally {
            $this->closed();
        }
        if ($status === 'a' || $status === 'e') {
            $this->log('error', 'An unchecked exception occurred during script execution. Please check the server log files for details.');
        } elseif ($status !== 'A' && $status !== 'E') {
            $this->log('error', 'The Java server did not acknowledge the end of the request; the connection was closed.');
        }
    }

    /**
     * Marks the connection as closed. Later calls throw java_IllegalStateException.
     */
    public function closed(): void
    {
        $this->channel = null;
        $this->methodCache = [];
        $this->sendBuffer = '';
        $this->prepared = null;
    }
}

/**
 * Configures the connection. Must be called before the first Java call;
 * repeated calls merge their options.
 *
 * @param array{host?: string, port?: int, security?: string, tls?: array, persistent?: bool, connect_timeout?: int|float, prefer_values?: bool, logger?: ?callable} $options
 *
 * @throws InvalidArgumentException for an unknown option or an invalid value
 * @throws java_IllegalStateException if the connection is already established
 */
function java_configure(array $options): void
{
    if (java_Client::$instance !== null) {
        throw new java_IllegalStateException(
            'java_configure() must be called before the first Java call; the connection is already established'
        );
    }
    $validated = [];
    foreach ($options as $name => $value) {
        $validated[$name] = java_Client::validateOption($name, $value);
    }
    java_Client::$options = $validated + java_Client::$options;
}

/**
 * Returns the client of this PHP request, connecting on first use.
 */
function __javaproxy_Client_getClient(): java_Client
{
    return java_Client::getInstance();
}

/**
 * Shutdown function: sends queued requests and recycles or closes the
 * connection. The PHP session is left alone; it belongs to the application.
 */
function java_shutdown(): void
{
    $client = java_Client::$instance;
    if ($client === null || $client->channel === null) {
        return;
    }
    $client->close();
}

function java_truncate($str)
{
    $str = (string) $str;
    if (strlen($str) > 955) {
        return substr($str, 0, 475) . '[...]' . substr($str, -475);
    }
    return $str;
}

/**
 * Returns the JavaClass for a class name; repeated calls return the same object.
 */
function Java($name)
{
    static $classMap = [];
    return $classMap[$name] ??= new JavaClass($name);
}

/**
 * Converts a Java object to a PHP value: strings, numbers, booleans and null
 * become PHP scalars; arrays, Collections and Maps become PHP arrays,
 * recursively. Other Java objects stay proxies. PHP values are returned as is.
 */
function java_values($object)
{
    if (!$object instanceof java_JavaType) {
        return $object;
    }
    return __javaproxy_Client_getClient()->invokeMethod(0, 'getValues', [$object]);
}

function java_get_values($arg)
{
    return java_values($arg);
}

function java_is_null($value): bool
{
    return is_null(java_values($value));
}

function java_isnull($value): bool
{
    return java_is_null($value);
}

function java_is_true($value): bool
{
    return (bool) java_values($value);
}

function java_istrue($value): bool
{
    return java_is_true($value);
}

function java_is_false($value): bool
{
    return !java_values($value);
}

function java_isfalse($value): bool
{
    return java_is_false($value);
}

/**
 * Returns true if the Java object is an instance of the given JavaClass.
 */
function java_instanceof($ob, $clazz): bool
{
    if (!$ob instanceof java_JavaType) {
        throw new java_IllegalArgumentException($ob);
    }
    if (!$clazz instanceof java_JavaType) {
        throw new java_IllegalArgumentException($clazz);
    }
    return __javaproxy_Client_getClient()->invokeMethod(0, 'instanceOf', [$ob, $clazz]);
}

/**
 * Proxy for a Java object, identified by its object id on the server.
 */
class java_JavaProxy implements java_JavaType, JsonSerializable
{
    public $__java;
    public $__signature;
    public $__client;

    public function __construct($java, $signature)
    {
        $this->__java = $java;
        $this->__signature = $signature;
        $this->__client = __javaproxy_Client_getClient();
    }

    public function __destruct()
    {
        if (isset($this->__client)) {
            $this->__client->unref($this->__java);
        }
    }

    public function __get($key)
    {
        return $this->__client->getProperty($this->__java, $key);
    }

    public function __set($key, $val): void
    {
        $this->__client->setProperty($this->__java, $key, $val);
    }

    public function __call($method, $args)
    {
        return $this->__client->invokeMethod($this->__java, $method, $args);
    }

    public function __toString(): string
    {
        try {
            return $this->__client->invokeMethod(0, 'ObjectToString', [$this]);
        } catch (JavaException $ex) {
            $this->__client->log('warning', 'Exception in Java::__toString(): ' . java_truncate((string) $ex));
            return '';
        }
    }

    public function jsonSerialize(): mixed
    {
        java_Client::$jsonProxySeen = true;
        return null;
    }

    public function __serialize(): array
    {
        throw new java_IllegalStateException('Java objects cannot be serialized');
    }
}

/**
 * Iterates over the elements of a Java array, Collection or Map, which are
 * fetched in one request.
 */
class java_ObjectIterator implements Iterator
{
    private array $var;

    public function __construct(java_JavaProxy $javaProxy)
    {
        $this->var = $javaProxy->__client->invokeMethod(0, 'castToArray', [$javaProxy]);
    }

    public function rewind(): void
    {
        reset($this->var);
    }

    public function valid(): bool
    {
        return key($this->var) !== null;
    }

    public function next(): void
    {
        next($this->var);
    }

    public function key(): mixed
    {
        return key($this->var);
    }

    public function current(): mixed
    {
        return current($this->var);
    }
}

/**
 * Proxy for a Java Collection (foreach).
 */
class java_IteratorProxy extends java_JavaProxy implements IteratorAggregate
{
    public function getIterator(): Iterator
    {
        return new java_ObjectIterator($this);
    }
}

/**
 * Proxy for a Java array, List or Map (foreach and ArrayAccess).
 */
class java_ArrayProxy extends java_IteratorProxy implements ArrayAccess
{
    public function offsetExists(mixed $idx): bool
    {
        return $this->__client->invokeMethod(0, 'offsetExists', [$this, $idx]);
    }

    public function offsetGet(mixed $idx): mixed
    {
        return $this->__client->invokeMethod(0, 'offsetGet', [$this, $idx]);
    }

    public function offsetSet(mixed $idx, mixed $val): void
    {
        $this->__client->invokeMethod(0, 'offsetSet', [$this, $idx, $val]);
    }

    public function offsetUnset(mixed $idx): void
    {
        $this->__client->invokeMethod(0, 'offsetUnset', [$this, $idx]);
    }
}

/**
 * Proxy for a Java Throwable.
 */
class java_ExceptionProxy extends java_JavaProxy
{
    public function __toExceptionString($trace)
    {
        return $this->__client->invokeMethod(0, 'ObjectToString', [$this, $trace]);
    }
}

/**
 * Base class of the user-visible Java objects. The proxy that does the work
 * (__delegate) is created on first use; until then the object may stand for
 * the result of a request that has been sent but not answered yet.
 */
abstract class java_AbstractJava implements IteratorAggregate, ArrayAccess, java_JavaType, JsonSerializable
{
    public $__client;
    public $__delegate;
    public $__factory;
    public $__java;
    public $__signature;
    public $__cancelProxyCreationTag;

    protected function __createDelegate(): void
    {
        $proxy = $this->__delegate = $this->__factory->create($this->__java, $this->__signature);
        $this->__java = $proxy->__java;
        $this->__signature = $proxy->__signature;
    }

    public function __get($key)
    {
        if (!isset($this->__delegate)) {
            $this->__createDelegate();
        }
        return $this->__delegate->__get($key);
    }

    public function __set($key, $val): void
    {
        if (!isset($this->__delegate)) {
            $this->__createDelegate();
        }
        $this->__delegate->__set($key, $val);
    }

    public function __call($method, $args)
    {
        if (!isset($this->__delegate)) {
            $this->__createDelegate();
        }
        return $this->__delegate->__call($method, $args);
    }

    public function __toString(): string
    {
        if (!isset($this->__delegate)) {
            $this->__createDelegate();
        }
        return $this->__delegate->__toString();
    }

    public function jsonSerialize(): mixed
    {
        java_Client::$jsonProxySeen = true;
        return null;
    }

    public function __serialize(): array
    {
        throw new java_IllegalStateException('Java objects cannot be serialized');
    }

    /**
     * foreach support. Called with arguments, it invokes the Java method
     * getIterator() instead.
     */
    public function getIterator(): Traversable
    {
        if (!isset($this->__delegate)) {
            $this->__createDelegate();
        }
        if (func_num_args() == 0) {
            return $this->__delegate->getIterator();
        }
        return $this->__call('getIterator', func_get_args());
    }

    public function offsetExists($idx): bool
    {
        if (!isset($this->__delegate)) {
            $this->__createDelegate();
        }
        if (func_num_args() == 1) {
            return $this->__delegate->offsetExists($idx);
        }
        return java_is_true($this->__call('offsetExists', func_get_args()));
    }

    public function offsetGet($idx): mixed
    {
        if (!isset($this->__delegate)) {
            $this->__createDelegate();
        }
        if (func_num_args() == 1) {
            return $this->__delegate->offsetGet($idx);
        }
        return $this->__call('offsetGet', func_get_args());
    }

    public function offsetSet($idx, $val): void
    {
        if (!isset($this->__delegate)) {
            $this->__createDelegate();
        }
        if (func_num_args() == 2) {
            $this->__delegate->offsetSet($idx, $val);
        } else {
            $this->__call('offsetSet', func_get_args());
        }
    }

    public function offsetUnset($idx): void
    {
        if (!isset($this->__delegate)) {
            $this->__createDelegate();
        }
        if (func_num_args() == 1) {
            $this->__delegate->offsetUnset($idx);
        } else {
            $this->__call('offsetUnset', func_get_args());
        }
    }
}

/**
 * A Java object. new Java('java.util.ArrayList', ...$args) creates an instance
 * on the server; method calls, property access, foreach and ArrayAccess are
 * forwarded to it.
 *
 * Calls whose signature has been seen before and which declare no checked
 * exceptions are sent without waiting for the reply; their result is a
 * placeholder object that is resolved when it is first used.
 */
class Java extends java_AbstractJava
{
    public function __construct(...$args)
    {
        $client = $this->__client = __javaproxy_Client_getClient();
        $name = array_shift($args);
        if (is_array($name)) {
            $args = $name;
            $name = array_shift($args);
        }
        $sig = self::getArgumentSignature("&@{$name}", $args);
        if (isset($client->methodCache[$sig])) {
            $cacheEntry = $client->methodCache[$sig];
            $client->deferRequest(['op' => 'new', 'class' => (string) $name, 'async' => 'ref'], $args);
            $this->__java = ++$client->asyncCtx;
            $this->__factory = $cacheEntry->factory;
            $this->__signature = $cacheEntry->signature;
            $this->__cancelProxyCreationTag = ++$client->cancelProxyCreationTag;
        } else {
            $client->currentCacheKey = $sig;
            $delegate = $this->__delegate = $client->createObject($name, $args);
            $this->__java = $delegate->__java;
            $this->__signature = $delegate->__signature;
        }
    }

    /**
     * If this object is the unused result of the last deferred request, the
     * request is changed to not create a result on the server. Otherwise the
     * server-side object is released.
     */
    public function __destruct()
    {
        if (!isset($this->__client) || $this->__java === null) {
            return;
        }
        $client = $this->__client;
        if ($client->prepared !== null
            && $client->cancelProxyCreationTag === $this->__cancelProxyCreationTag) {
            $client->prepared[0]['async'] = 'void';
            $client->flushPrepared();
            $client->asyncCtx -= 1;
        } elseif (!isset($this->__delegate)) {
            $client->unref($this->__java);
        }
    }

    public function __call($method, $args)
    {
        $client = $this->__client;
        $sig = self::getArgumentSignature("@{$this->__signature}@{$method}", $args);
        if (!isset($client->methodCache[$sig])) {
            $client->currentCacheKey = $sig;
            return parent::__call($method, $args);
        }
        $cacheEntry = $client->methodCache[$sig];
        $client->deferRequest([
            'op' => 'call',
            'obj' => $this->__java,
            'name' => (string) $method,
            'async' => $cacheEntry->resultVoid ? 'void' : 'ref',
        ], $args);
        if ($cacheEntry->resultVoid) {
            $client->cancelProxyCreationTag += 1;
            return null;
        }
        $result = clone $client->cachedJavaPrototype;
        $result->__factory = $cacheEntry->factory;
        $result->__java = ++$client->asyncCtx;
        $result->__signature = $cacheEntry->signature;
        $result->__cancelProxyCreationTag = ++$client->cancelProxyCreationTag;
        return $result;
    }

    /**
     * Appends the argument types to the cache key $sig. Arrays, resources and
     * non-Java objects make the call uncacheable.
     */
    private static function getArgumentSignature(string $sig, array $args): string
    {
        foreach ($args as $val) {
            switch (gettype($val)) {
                case 'boolean':
                    $sig .= '@b';
                    break;
                case 'integer':
                    $sig .= '@i';
                    break;
                case 'double':
                    $sig .= '@d';
                    break;
                case 'string':
                    $sig .= '@s';
                    break;
                case 'NULL':
                    $sig .= '@N';
                    break;
                case 'object':
                    if ($val instanceof java_JavaType) {
                        $sig .= "@o{$val->__signature}";
                    } else {
                        return '~INVALID';
                    }
                    break;
                case 'array':
                case 'resource':
                    return '~INVALID';
                default:
                    throw new java_IllegalArgumentException($val);
            }
        }
        return $sig;
    }
}

/**
 * Wraps a proxy returned by a call.
 */
class java_InternalJava extends Java
{
    public function __construct($proxy)
    {
        $this->__delegate = $proxy;
        $this->__java = $proxy->__java;
        $this->__signature = $proxy->__signature;
        $this->__client = $proxy->__client;
    }
}

/**
 * A Java class, for static methods and fields: new JavaClass('java.lang.Math').
 * java('java.lang.Math') returns a cached instance.
 */
class java_class extends Java
{
    public function __construct(...$args)
    {
        $this->__client = __javaproxy_Client_getClient();
        $name = array_shift($args);
        if (is_array($name)) {
            $args = $name;
            $name = array_shift($args);
        }
        $delegate = $this->__delegate = $this->__client->referenceObject($name, $args);
        $this->__java = $delegate->__java;
        $this->__signature = $delegate->__signature;
    }
}

class JavaClass extends java_class
{
}

/**
 * A Java exception as a PHP exception. new JavaException('java.lang.Exception',
 * 'message') creates one on the server; exceptions thrown by Java calls are
 * java_InternalException instances. Method calls are forwarded to the Java
 * object, except those defined by PHP's Exception class (getMessage() etc.).
 */
class java_exception extends Exception implements java_JavaType, JsonSerializable
{
    public $__java;
    public $__client;
    public $__delegate;
    public $__signature;
    public $__hasDeclaredExceptions;

    public function __construct(...$args)
    {
        $this->__client = __javaproxy_Client_getClient();
        $name = array_shift($args);
        if (is_array($name)) {
            $args = $name;
            $name = array_shift($args);
        }
        parent::__construct(count($args) == 0 ? $name : $args[0]);
        $delegate = $this->__delegate = $this->__client->createObject($name, $args);
        $this->__java = $delegate->__java;
        $this->__signature = $delegate->__signature;
        $this->__hasDeclaredExceptions = 'T';
    }

    public function __get($key)
    {
        return $this->__delegate->__get($key);
    }

    public function __set($key, $val): void
    {
        $this->__delegate->__set($key, $val);
    }

    public function __call($method, $args)
    {
        return $this->__delegate->__call($method, $args);
    }

    public function __toString(): string
    {
        return $this->__delegate->__toExceptionString($this->getTraceAsString());
    }

    public function jsonSerialize(): mixed
    {
        java_Client::$jsonProxySeen = true;
        return null;
    }

    public function __serialize(): array
    {
        throw new java_IllegalStateException('Java objects cannot be serialized');
    }
}

class JavaException extends java_exception
{
}

/**
 * An exception thrown by, or returned from, a Java call.
 */
class java_InternalException extends JavaException
{
    public function __construct($proxy, $exception)
    {
        $this->__delegate = $proxy;
        $this->__java = $proxy->__java;
        $this->__signature = $proxy->__signature;
        $this->__client = $proxy->__client;
        $this->__hasDeclaredExceptions = $exception;
    }
}

/**
 * Prototype that is cloned for the results of deferred requests.
 */
class java_JavaProxyProxy extends Java
{
    public function __construct(java_Client $client)
    {
        $this->__client = $client;
    }
}
