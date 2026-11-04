<?php

/**
 * Test suite for the PHP client (client/JavaBridge.php).
 *
 * Needs a running bridge server. Uses only JDK classes.
 *
 * Usage:
 *
 *     php client/tests/run.php [host:port] [options]
 *
 * The address defaults to the JAVA_BRIDGE_ADDRESS environment variable, then
 * to 127.0.0.1:9267.
 *
 * Options:
 *
 *     --mode=both|proxy|values  Result mode(s) to test (default both). "proxy"
 *                               runs with prefer_values off and a persistent
 *                               connection; "values" runs with prefer_values
 *                               on and a non-persistent connection. With
 *                               "both", the values run is a child process
 *                               started with the same PHP binary and php.ini
 *                               (or -n); other -d options are not passed on.
 *     --group=a,b               Run only these groups (the part of a test
 *                               name before the slash).
 *     --exclude=a,b             Skip these groups, e.g. --exclude=nonpublic.
 *     --list                    List the test names and exit.
 *
 * Every warning, notice or deprecation raised while a test runs, and every
 * diagnostic sent to the client's logger that the test does not expect,
 * fails the test. The exit code is 1 if any test fails.
 *
 * The "nonpublic" group calls methods on objects whose runtime class is not
 * public (ArrayList$Itr, HashMap$KeySet, Collections$UnmodifiableRandomAccessList).
 * The unmodified 6.2.1 server only passes these with the JVM option
 * --add-opens java.base/java.util=ALL-UNNAMED.
 */

error_reporting(E_ALL);
ini_set('display_errors', '1');

final class TestFailure extends Exception
{
}

final class TestSkipped extends Exception
{
}

final class Runner
{
    /** @var list<string> PHP errors raised since the last reset */
    public static array $errors = [];

    /** @var list<array{0: string, 1: string}> diagnostics sent to the client's logger */
    public static array $logs = [];

    /** @var array<string, array{fn: callable, modes: list<string>}> */
    public static array $tests = [];

    public static string $mode = 'proxy';

    /** Set when a test leaves an unchecked exception on the server (proxy mode). */
    public static bool $expectUncheckedAtEnd = false;

    public static function log(string $level, string $message): void
    {
        self::$logs[] = [$level, $message];
    }

    /**
     * Returns and clears the logged diagnostics.
     *
     * @return list<array{0: string, 1: string}>
     */
    public static function takeLogs(): array
    {
        $logs = self::$logs;
        self::$logs = [];
        return $logs;
    }
}

set_error_handler(static function (int $errno, string $message, string $file, int $line): bool {
    if (!(error_reporting() & $errno)) {
        return false;
    }
    $types = [
        E_WARNING => 'Warning', E_NOTICE => 'Notice', E_DEPRECATED => 'Deprecated',
        E_USER_WARNING => 'User warning', E_USER_NOTICE => 'User notice', E_USER_DEPRECATED => 'User deprecated',
    ];
    Runner::$errors[] = ($types[$errno] ?? "Error {$errno}") . ": {$message} in " . basename($file) . ":{$line}";
    return true;
});

require __DIR__ . '/../JavaBridge.php';

/**
 * Registers a test. $modes limits it to 'proxy' or 'values'.
 */
function test(string $name, callable $fn, string ...$modes): void
{
    Runner::$tests[$name] = ['fn' => $fn, 'modes' => $modes ?: ['proxy', 'values']];
}

function fail(string $message): never
{
    throw new TestFailure($message);
}

function skip(string $reason): never
{
    throw new TestSkipped($reason);
}

function describe($value): string
{
    if ($value instanceof java_JavaType) {
        return get_class($value) . '(' . java_truncate((string) $value) . ')';
    }
    $text = var_export($value, true);
    return strlen($text) > 200 ? substr($text, 0, 200) . '...' : $text;
}

function check(bool $condition, string $message): void
{
    if (!$condition) {
        fail($message);
    }
}

function same($expected, $actual, string $label = 'value'): void
{
    if ($expected !== $actual) {
        fail("{$label}: expected " . describe($expected) . ', got ' . describe($actual));
    }
}

function contains(string $needle, string $haystack, string $label = 'string'): void
{
    if (!str_contains($haystack, $needle)) {
        fail("{$label} does not contain '{$needle}': " . java_truncate($haystack));
    }
}

/**
 * Runs $fn and returns the exception it throws, which must be an instance of $class.
 */
function throws(string $class, callable $fn): Throwable
{
    try {
        $fn();
    } catch (Throwable $e) {
        if (!$e instanceof $class) {
            fail("expected {$class}, got " . get_class($e) . ': ' . strtok((string) $e, "\n"));
        }
        return $e;
    }
    fail("expected {$class}, nothing was thrown");
}

/**
 * Returns the logged diagnostics; there must be exactly $count of them at $level.
 *
 * @return list<string>
 */
function expectLogs(string $level, int $count): array
{
    $logs = Runner::takeLogs();
    check(count($logs) === $count, "expected {$count} '{$level}' diagnostics, got " . count($logs) . ': ' . describe($logs));
    foreach ($logs as [$logLevel, $message]) {
        same($level, $logLevel, 'diagnostic level');
    }
    return array_column($logs, 1);
}

function isProxyMode(): bool
{
    return Runner::$mode === 'proxy';
}

/**
 * Builds a java.util.ArrayList from PHP values, one add() call per element.
 */
function javaList(array $values): Java
{
    $list = new Java('java.util.ArrayList');
    foreach ($values as $value) {
        $list->add($value);
    }
    return $list;
}

/**
 * Builds a java.util.LinkedHashMap (keeps insertion order) from a PHP array.
 */
function javaMap(array $entries): Java
{
    $map = new Java('java.util.LinkedHashMap');
    foreach ($entries as $key => $value) {
        $map->put($key, $value);
    }
    return $map;
}

/**
 * Iterates with foreach and returns [key, java_values(value)] pairs.
 *
 * @return list<array{0: mixed, 1: mixed}>
 */
function iterate($traversable): array
{
    $pairs = [];
    foreach ($traversable as $key => $value) {
        $pairs[] = [$key, java_values($value)];
    }
    return $pairs;
}

/**
 * Returns a local TCP port with no listener.
 */
function unusedPort(): int
{
    $server = stream_socket_server('tcp://127.0.0.1:0');
    $name = stream_socket_get_name($server, false);
    fclose($server);
    return (int) substr($name, strrpos($name, ':') + 1);
}

// ---------------------------------------------------------------------------
// Tests that must run before the connection is established.
// ---------------------------------------------------------------------------

test('config/bad-port-throws-connect-exception', static function (): void {
    $port = unusedPort();
    java_configure(['port' => $port, 'connect_timeout' => 3]);
    $e = throws(java_ConnectException::class, static fn () => new Java('java.lang.Object'));
    check($e instanceof java_IOException && $e instanceof Exception, 'java_ConnectException hierarchy');
    contains("Could not connect to the Java server at 127.0.0.1:{$port}", $e->getMessage(), 'message');
    check(java_Client::$instance === null, 'no client after a failed connect');
});

test('config/invalid-options-are-rejected', static function (): void {
    $invalid = [
        ['port' => 0], ['port' => 70000], ['port' => 'abc'], ['host' => ''], ['host' => 42],
        ['persistent' => 'yes'], ['prefer_values' => 1], ['connect_timeout' => 0],
        ['logger' => 'no_such_function_xyz'], ['no_such_option' => true],
    ];
    foreach ($invalid as $options) {
        $e = throws(InvalidArgumentException::class, static fn () => java_configure($options));
        contains((string) array_key_first($options), $e->getMessage(), 'message');
    }
    java_configure(['port' => '9267', 'connect_timeout' => 1.5]);
    same(9267, java_Client::$options['port'], 'numeric string port');
    same(1.5, java_Client::$options['connect_timeout'], 'connect_timeout');
});

// ---------------------------------------------------------------------------
// Objects, calls, classes and fields.
// ---------------------------------------------------------------------------

test('core/create-object', static function (): void {
    $object = new Java('java.lang.Object');
    check($object instanceof Java && $object instanceof java_JavaType, 'Java instance');
    check(str_starts_with((string) $object, 'java.lang.Object@'), 'toString: ' . $object);
    $list = new Java(['java.util.ArrayList', 5]);
    same(0, java_values($list->size()), 'array form of new Java()');
});

test('core/overloaded-constructors', static function (): void {
    $long = new Java('java.lang.Long', 5000000000);
    same('5000000000', (string) $long, 'Long(5000000000)');
    $negative = new Java('java.lang.Long', -5000000000);
    same('-5000000000', (string) $negative, 'Long(-5000000000)');
    $fromString = new Java('java.lang.StringBuilder', 'abc');
    same('abc', (string) $fromString, 'StringBuilder(String)');
    same(3, java_values($fromString->length()), 'StringBuilder(String) length');
    $withCapacity = new Java('java.lang.StringBuilder', 100);
    same('', (string) $withCapacity, 'StringBuilder(int)');
    check(java_values($withCapacity->capacity()) >= 100, 'StringBuilder(int) capacity');
    $big = new Java('java.math.BigInteger', 'ff', 16);
    same(255, java_values($big->intValue()), 'BigInteger(String, int)');
    $huge = new Java('java.math.BigInteger', '123456789012345678901234567890');
    same('123456789012345678901234567890', (string) $huge->multiply(java('java.math.BigInteger')->ONE), 'BigInteger(String)');
});

test('core/instance-calls', static function (): void {
    $s = new Java('java.lang.String', 'Hello');
    same('HELLO', java_values($s->toUpperCase()), 'toUpperCase');
    same('el', java_values($s->substring(1, 3)), 'substring');
    same(2, java_values($s->indexOf('l')), 'indexOf');
    same(true, java_values($s->startsWith('He')), 'startsWith');
    same(false, java_values($s->isEmpty()), 'isEmpty');
    $list = javaList(['a', 'b']);
    $other = new Java('java.lang.StringBuilder', 'x');
    $list->add($other);
    same(3, java_values($list->size()), 'size');
    same(true, java_values($list->contains($other)), 'contains(Java object)');
    same(2, java_values($list->indexOf($other)), 'indexOf(Java object)');
});

test('core/chained-calls', static function (): void {
    $sb = new Java('java.lang.StringBuilder');
    same('eurt5.1a', (string) $sb->append('a')->append(1.5)->append(true)->reverse(), 'chain');
    same('eurt5.1a', java_values($sb->toString()), 'receiver after chain');
    same('2.5', (string) java('java.lang.Double')->valueOf(2.5)->toString(), 'static then instance call');
});

test('core/java-time', static function (): void {
    $date = java('java.time.LocalDate')->of(2024, 2, 28);
    same('2024-03-01', (string) $date->plusDays(2), 'LocalDate.plusDays');
    same(true, java_values($date->isLeapYear()), 'isLeapYear');
    same('PT1H30M', (string) java('java.time.Duration')->ofMinutes(90), 'Duration.toString');
    same(5400, java_values(java('java.time.Duration')->ofMinutes(90)->getSeconds()), 'Duration.getSeconds (a long)');
});

test('core/static-calls', static function (): void {
    $math = java('java.lang.Math');
    check(java_values($math->max(3, 7)) == 7, 'Math.max(3, 7)');
    same(2.5, java_values($math->abs(-2.5)), 'Math.abs(-2.5)');
    same(sqrt(2.0), java_values($math->sqrt(2.0)), 'Math.sqrt(2.0)');
    same(123, java_values(java('java.lang.Integer')->parseInt('123')), 'Integer.parseInt');
    same(-42, java_values(java('java.lang.Integer')->parseInt('-42')), 'Integer.parseInt negative');
    same('ff', java_values(java('java.lang.Integer')->toHexString(255)), 'Integer.toHexString');
    same('true', java_values(java('java.lang.String')->valueOf(true)), 'String.valueOf(true)');
    same('false', java_values(java('java.lang.Boolean')->toString(false)), 'Boolean.toString(false)');
});

test('core/static-fields', static function (): void {
    same(2147483647, java_values(java('java.lang.Integer')->MAX_VALUE), 'Integer.MAX_VALUE');
    same(-2147483648, java_values(java('java.lang.Integer')->MIN_VALUE), 'Integer.MIN_VALUE');
    same(M_PI, java_values(java('java.lang.Math')->PI), 'Math.PI');
    same(true, java_values(java('java.lang.Boolean')->TRUE), 'Boolean.TRUE');
    same('UTF-8', java_values(java('java.nio.charset.StandardCharsets')->UTF_8->name()), 'object-valued field');
});

/**
 * Creates a java.awt.Point, or skips if java.desktop is not available.
 */
function point(int $x, int $y): Java
{
    try {
        return new Java('java.awt.Point', $x, $y);
    } catch (JavaException $e) {
        skip('java.awt.Point is not available: ' . java_truncate((string) $e));
    }
}

test('core/public-field-get', static function (): void {
    $p = point(3, 4);
    same(3, java_values($p->x), 'Point.x');
    same(4, java_values($p->y), 'Point.y');
});

test('core/public-field-set', static function (): void {
    $p = point(3, 4);
    $p->x = 10;
    $p->y = -2;
    same(10.0, java_values($p->getX()), 'getX() after set');
    same(-2, java_values($p->y), 'Point.y after set');
});

test('core/javaclass-cache', static function (): void {
    same(java('java.lang.Math'), java('java.lang.Math'), 'java() cache');
    same(java('java.lang.Math'), Java('java.lang.Math'), 'Java() is java()');
    check(java('java.lang.Math') !== java('java.lang.StrictMath'), 'distinct classes');
    check(java('java.lang.Math') instanceof JavaClass, 'JavaClass instance');
    $fresh = new JavaClass('java.lang.Math');
    check($fresh !== java('java.lang.Math'), 'new JavaClass() is not cached');
    same(9.0, java_values($fresh->pow(3.0, 2.0)), 'static call through new JavaClass()');
    same('java.lang.Integer', java_values(java('java.lang.Integer')->valueOf(1)->getClass()->getName()), 'getClass()');
});

test('core/to-string', static function (): void {
    $i = java('java.lang.Integer')->valueOf(42);
    same('42', (string) $i, 'string cast');
    same('n=42', "n={$i}", 'interpolation');
    same('[a, b]', (string) javaList(['a', 'b']), 'List.toString');
});

test('core/void-and-null-results', static function (): void {
    $list = javaList(['a']);
    same(null, $list->clear(), 'void method');
    same(0, java_values($list->size()), 'size after clear');
    $map = new Java('java.util.HashMap');
    check(java_is_null($map->get('missing')), 'missing key is null');
});

test('core/php-values-to-java', static function (): void {
    $list = new Java('java.util.ArrayList');
    $list->add(1);
    $list->add(-7);
    $list->add(2.5);
    $list->add(true);
    $list->add(false);
    $list->add(null);
    $list->add('text');
    same([1, -7, 2.5, true, false, null, 'text'], java_values($list), 'values');
    $map = new Java('java.util.HashMap');
    $map->put('k', 'v');
    same(['k' => 'v'], java_values($map), 'map');
});

test('core/serialize-is-rejected', static function (): void {
    $list = new Java('java.util.ArrayList');
    throws(java_IllegalStateException::class, static fn () => serialize($list));
});

// ---------------------------------------------------------------------------
// java_values() conversions.
// ---------------------------------------------------------------------------

test('values/scalars', static function (): void {
    same(42, java_values(java('java.lang.Integer')->valueOf(42)), 'Integer');
    same(-5, java_values(java('java.lang.Short')->valueOf('-5')), 'Short');
    same(true, java_values(java('java.lang.Boolean')->valueOf(true)), 'Boolean true');
    same(false, java_values(new Java('java.lang.Boolean', false)), 'Boolean false');
    same(1.5, java_values(java('java.lang.Double')->valueOf(1.5)), 'Double');
    same(0.1, java_values(java('java.lang.Double')->valueOf('0.1')), 'Double 0.1');
    same('héllo', java_values(new Java('java.lang.String', 'héllo')), 'String');
    same(null, java_values(null), 'null');
    same(17, java_values(17), 'PHP values pass through');
    same(['a' => 1], java_values(['a' => 1]), 'PHP arrays pass through');
});

test('values/long-is-exact', static function (): void {
    same(9007199254740993, java_values(new Java('java.lang.Long', 9007199254740993)), 'Long beyond 2^53');
    same(PHP_INT_MAX, java_values(java('java.lang.Long')->MAX_VALUE), 'Long.MAX_VALUE');
    same(PHP_INT_MIN, java_values(java('java.lang.Long')->MIN_VALUE), 'Long.MIN_VALUE');
    same(5000000000, java_values(java('java.lang.Long')->parseLong('5000000000')), 'long result');
    same(5, java_values(java('java.lang.Long')->valueOf(5)), 'small Long');
    same([PHP_INT_MAX, -1], java_values(javaList([PHP_INT_MAX, -1])), 'longs in a list');
});

test('values/nan-and-infinity', static function (): void {
    $double = java('java.lang.Double');
    same(true, java_values($double->isNaN(NAN)), 'NAN reaches Java');
    same(true, java_values($double->isInfinite(-INF)), '-INF reaches Java');
    check(is_nan(java_values($double->NaN)), 'Double.NaN comes back as NAN');
    same(INF, java_values($double->POSITIVE_INFINITY), 'POSITIVE_INFINITY');
    $list = javaList([1.5, NAN, -INF]);
    $back = java_values($list);
    check($back[0] === 1.5 && is_nan($back[1]) && $back[2] === -INF, 'in a list: ' . describe($back));
});

test('values/map-keys-and-order', static function (): void {
    $map = new Java('java.util.LinkedHashMap', ['b' => 1, 'a' => 2, 7 => 'seven', '08' => 'string key']);
    same(['b', 'a', 7, '08'], array_keys(java_values($map)), 'PHP order and key types survive');
    same('seven', java_values($map->get(7)), 'integer key');
    same('string key', java_values($map->get('08')), 'numeric-looking string key');
});

test('values/php-object-argument-becomes-map', static function (): void {
    $point = new stdClass();
    $point->x = 1;
    $point->y = 'two';
    $list = new Java('java.util.ArrayList');
    $list->add($point);
    same([['x' => 1, 'y' => 'two']], java_values($list), 'public properties as a Map');
});

test('values/byte-array-becomes-string', static function (): void {
    $bytes = (new Java('java.lang.String', 'a,b'))->getBytes('UTF-8');
    same('a,b', java_values($bytes), 'byte[]');
});

test('values/arrays-and-collections', static function (): void {
    same(['a', 'b', 'c'], java_values((new Java('java.lang.String', 'a,b,c'))->split(',')), 'String[]');
    $ints = java('java.lang.reflect.Array')->newInstance(java('java.lang.Integer')->TYPE, 3);
    same([0, 0, 0], java_values($ints), 'int[]');
    same(['x', 1, true], java_values(javaList(['x', 1, true])), 'List');
    same(['b' => 2, 'a' => 1], java_values(javaMap(['b' => 2, 'a' => 1])), 'Map');
    $nested = javaList([javaList([1, 2]), javaMap(['k' => javaList(['v'])]), 'end']);
    same([[1, 2], ['k' => ['v']], 'end'], java_values($nested), 'nested');
    same([], java_values(new Java('java.util.ArrayList')), 'empty List');
});

test('values/php-arrays-to-java', static function (): void {
    $list = new Java('java.util.ArrayList', [1, 'two', 3.5, false, null]);
    same(5, java_values($list->size()), 'ArrayList(Collection) size');
    same([1, 'two', 3.5, false, null], java_values($list), 'ArrayList(Collection)');
    $map = new Java('java.util.HashMap', ['a' => 1, 'b' => 'x']);
    same(1, java_values($map->get('a')), 'HashMap(Map) get');
    same('x', java_values($map->get('b')), 'HashMap(Map) get');
    $nested = new Java('java.util.ArrayList', [[1, 2], ['k' => 'v']]);
    same([[1, 2], ['k' => 'v']], java_values($nested), 'nested PHP arrays');
    $list->addAll(['x', 'y']);
    same(7, java_values($list->size()), 'addAll(PHP array)');
    same(false, java_values((new Java('java.util.ArrayList'))->addAll([])), 'addAll(empty PHP array)');
});

test('values/results-are-proxies', static function (): void {
    $int = java('java.lang.Integer')->parseInt('5');
    check($int instanceof java_JavaType, 'int result is a proxy: ' . describe($int));
    same(5, java_values($int), 'java_values(int result)');
    $bool = (new Java('java.lang.String', 'abc'))->isEmpty();
    check($bool instanceof java_JavaType, 'boolean result is a proxy');
    same(false, java_values($bool), 'java_values(boolean result)');
    $null = (new Java('java.util.HashMap'))->get('missing');
    check($null instanceof java_JavaType, 'null result is a proxy');
    check(java_is_null($null), 'java_is_null(null result)');
}, 'proxy');

test('values/results-are-values', static function (): void {
    same(5, java('java.lang.Integer')->parseInt('5'), 'int result');
    same(false, (new Java('java.lang.String', 'abc'))->isEmpty(), 'boolean result');
    same(2.0, java('java.lang.Math')->sqrt(4.0), 'double result');
    same(null, (new Java('java.util.HashMap'))->get('missing'), 'null result');
}, 'values');

// ---------------------------------------------------------------------------
// Null, boolean and instanceof helpers.
// ---------------------------------------------------------------------------

test('logic/java-is-null-true-false', static function (): void {
    check(java_is_null(null), 'java_is_null(null)');
    check(java_is_null((new Java('java.util.HashMap'))->get('x')), 'java_is_null(Java null)');
    check(!java_is_null(new Java('java.lang.Object')), '!java_is_null(object)');
    check(java_is_true(java('java.lang.Boolean')->TRUE), 'java_is_true(TRUE)');
    check(java_is_false(java('java.lang.Boolean')->FALSE), 'java_is_false(FALSE)');
    check(!java_is_true(java('java.lang.Boolean')->FALSE), '!java_is_true(FALSE)');
    check(java_is_false(new Java('java.lang.String', '')), 'java_is_false("")');
    check(java_is_true(1) && java_is_false(0), 'PHP values');
    check(java_isnull(null) && java_istrue(true) && java_isfalse(false), 'legacy aliases');
    same([1], java_get_values(javaList([1])), 'java_get_values alias');
});

test('logic/java-instanceof', static function (): void {
    $list = new Java('java.util.ArrayList');
    same(true, java_instanceof($list, java('java.util.List')), 'ArrayList instanceof List');
    same(true, java_instanceof($list, java('java.util.ArrayList')), 'ArrayList instanceof ArrayList');
    same(false, java_instanceof($list, java('java.util.Map')), 'ArrayList instanceof Map');
    throws(java_IllegalArgumentException::class, static fn () => java_instanceof('x', java('java.util.List')));
    throws(java_IllegalArgumentException::class, static fn () => java_instanceof($list, 'java.util.List'));
});

// ---------------------------------------------------------------------------
// foreach.
// ---------------------------------------------------------------------------

test('iterate/list-with-falsy-elements', static function (): void {
    $list = javaList(['x', true, false, null, 0, '', 0.0, 'y']);
    same([[0, 'x'], [1, true], [2, false], [3, null], [4, 0], [5, ''], [6, 0.0], [7, 'y']], iterate($list), 'elements');
    same([[0, false], [1, 'z']], iterate(javaList([false, 'z'])), 'false first');
    same([[0, null], [1, 'z']], iterate(javaList([null, 'z'])), 'null first');
});

test('iterate/map-keys-and-values', static function (): void {
    $map = javaMap(['a' => false, 'b' => null, 'c' => 0, 'd' => '', 'e' => 'x']);
    same([['a', false], ['b', null], ['c', 0], ['d', ''], ['e', 'x']], iterate($map), 'string keys');
    $numeric = new Java('java.util.TreeMap');
    $numeric->put(java('java.lang.Integer')->valueOf(7), 'seven');
    $numeric->put(java('java.lang.Integer')->valueOf(0), false);
    same([[0, false], [7, 'seven']], iterate($numeric), 'Integer keys');
});

test('iterate/set', static function (): void {
    $map = new Java('java.util.HashMap');
    foreach (['a', 'b', 'c'] as $key) {
        $map->put($key, 1);
    }
    $keys = array_column(iterate($map->keySet()), 1);
    sort($keys);
    same(['a', 'b', 'c'], $keys, 'HashMap.keySet()');
    $set = new Java('java.util.TreeSet');
    $set->add(false);
    $set->add(true);
    same([[0, false], [1, true]], iterate($set), 'TreeSet of booleans');
});

test('iterate/arrays', static function (): void {
    same([[0, 'a'], [1, ''], [2, 'c']], iterate((new Java('java.lang.String', 'a,,c'))->split(',', -1)), 'String[]');
    $booleans = java('java.lang.reflect.Array')->newInstance(java('java.lang.Boolean')->TYPE, 3);
    same([[0, false], [1, false], [2, false]], iterate($booleans), 'boolean[]');
    $ints = java('java.lang.reflect.Array')->newInstance(java('java.lang.Integer')->TYPE, 2);
    same([[0, 0], [1, 0]], iterate($ints), 'int[]');
});

test('iterate/empty-and-repeated', static function (): void {
    same([], iterate(new Java('java.util.ArrayList')), 'empty list');
    $list = javaList([1, 2]);
    same(iterate($list), iterate($list), 'second foreach');
    $count = 0;
    foreach ($list as $outer) {
        foreach ($list as $inner) {
            $count++;
        }
    }
    same(4, $count, 'nested foreach');
});

// ---------------------------------------------------------------------------
// ArrayAccess.
// ---------------------------------------------------------------------------

test('arrayaccess/list', static function (): void {
    $list = javaList(['a', 'b']);
    same('a', java_values($list[0]), '$list[0]');
    same(true, isset($list[1]), 'isset($list[1])');
    same(false, isset($list[2]), 'isset($list[2])');
    $list[1] = 'B';
    $list[] = 'c';
    same(['a', 'B', 'c'], java_values($list), 'after set and append');
    unset($list[0]);
    same(['B', 'c'], java_values($list), 'after unset');
});

test('arrayaccess/map', static function (): void {
    $map = new Java('java.util.HashMap');
    $map['k'] = 'v';
    $map['n'] = null;
    same('v', java_values($map['k']), '$map[k]');
    same(true, isset($map['k']), 'isset($map[k])');
    same(true, isset($map['n']), 'isset($map[n]) with a null value');
    same(false, isset($map['missing']), 'isset($map[missing])');
    unset($map['k']);
    same(false, java_values($map->containsKey('k')), 'after unset');
});

test('arrayaccess/array', static function (): void {
    $array = (new Java('java.lang.String', 'a,b'))->split(',');
    same('b', java_values($array[1]), '$array[1]');
    same(true, isset($array[1]), 'isset($array[1])');
    same(false, isset($array[2]), 'isset($array[2])');
    $array[0] = 'z';
    same(['z', 'b'], java_values($array), 'after set');
});

// ---------------------------------------------------------------------------
// Objects whose runtime class is not public.
// ---------------------------------------------------------------------------

test('nonpublic/list-iterator', static function (): void {
    $it = javaList(['a', false, 'c'])->iterator();
    $seen = [];
    while (java_values($it->hasNext())) {
        $seen[] = java_values($it->next());
    }
    same(['a', false, 'c'], $seen, 'ArrayList$Itr');
});

test('nonpublic/keyset-to-array', static function (): void {
    $map = new Java('java.util.HashMap');
    $map->put('b', 1);
    $map->put('a', 2);
    same(2, java_values($map->keySet()->size()), 'HashMap$KeySet.size()');
    $keys = java_values($map->keySet()->toArray());
    sort($keys);
    same(['a', 'b'], $keys, 'HashMap$KeySet.toArray()');
});

test('nonpublic/unmodifiable-list', static function (): void {
    $list = java('java.util.Collections')->unmodifiableList(javaList(['x', 'y']));
    same(2, java_values($list->size()), 'size()');
    same('y', java_values($list->get(1)), 'get(1)');
    same(true, java_values($list->contains('x')), 'contains()');
    same(['x', 'y'], java_values($list), 'java_values');
});

// ---------------------------------------------------------------------------
// Binary data.
// ---------------------------------------------------------------------------

test('binary/base64-all-byte-values', static function (): void {
    $bytes = implode('', array_map('chr', range(0, 255)));
    $b64 = base64_encode($bytes);
    $decoded = java('java.util.Base64')->getDecoder()->decode($b64);
    same(256, java_values(java('java.lang.reflect.Array')->getLength($decoded)), 'byte[] length');
    same($bytes, java_values($decoded), 'byte[] to PHP string');
    same($b64, java_values(java('java.util.Base64')->getEncoder()->encodeToString($decoded)), 'encodeToString');
});

test('binary/sha256', static function (): void {
    $bytes = implode('', array_map('chr', range(0, 255)));
    $input = java('java.util.Base64')->getDecoder()->decode(base64_encode($bytes));
    $digest = java('java.security.MessageDigest')->getInstance('SHA-256');
    same(hash('sha256', $bytes), bin2hex(java_values($digest->digest($input))), 'SHA-256 of all byte values');
    $text = (new Java('java.lang.String', 'Grüße 😀'))->getBytes('UTF-8');
    same(hash('sha256', 'Grüße 😀'), bin2hex(java_values($digest->digest($text))), 'SHA-256 of UTF-8 text');
});

test('binary/php-binary-strings-reach-java', static function (): void {
    $raw = "\x00\xff\xfe\x80";
    $s = new Java('java.lang.String', $raw, 'ISO-8859-1');
    same(4, java_values($s->length()), 'a byte[] parameter gets the exact bytes');
    same($raw, java_values($s->getBytes('ISO-8859-1')), 'and they come back unchanged');
    $all = implode('', array_map('chr', range(0, 255)));
    $digest = java('java.security.MessageDigest')->getInstance('SHA-256');
    same(hash('sha256', $all), bin2hex(java_values($digest->digest($all))), 'all 256 byte values as byte[]');
});

// ---------------------------------------------------------------------------
// Large payloads.
// ---------------------------------------------------------------------------

test('large/string-5mb', static function (): void {
    $text = str_repeat('0123456789abcdef', 327680);
    $s = new Java('java.lang.String', $text);
    same(5242880, java_values($s->length()), 'length');
    same($text, java_values($s), 'round trip');
    same(strtoupper($text), java_values($s->toUpperCase()), 'toUpperCase');
});

test('large/string-12mb', static function (): void {
    $text = str_repeat("0123456789abcdé\n", 786432);
    $s = new Java('java.lang.String', $text);
    same(strlen($text), java_values(java('java.lang.reflect.Array')->getLength($s->getBytes('UTF-8'))), 'bytes in Java');
    same($text, java_values($s), 'round trip, beyond the 1.x limit of about 7.5 MB');
});

test('large/binary-4mb-base64', static function (): void {
    $bytes = random_bytes(4 * 1024 * 1024);
    $b64 = base64_encode($bytes);
    $decoded = java('java.util.Base64')->getDecoder()->decode($b64);
    same(strlen($bytes), java_values(java('java.lang.reflect.Array')->getLength($decoded)), 'length');
    same($b64, java_values(java('java.util.Base64')->getEncoder()->encodeToString($decoded)), 'base64 round trip');
    same($bytes, java_values($decoded), 'byte[] to PHP string');
});

// ---------------------------------------------------------------------------
// UTF-8.
// ---------------------------------------------------------------------------

test('utf8/multibyte', static function (): void {
    $text = 'Grüße, 日本語, Ελληνικά';
    $s = new Java('java.lang.String', $text);
    same(iconv_strlen($text, 'UTF-8'), java_values($s->length()), 'length');
    same($text, java_values($s), 'round trip');
    same(7, java_values($s->indexOf('日本語')), 'indexOf');
    same('日本語', java_values($s->substring(7, 10)), 'substring');
    same('GRÜSSE', java_values($s->substring(0, 5)->toUpperCase(java('java.util.Locale')->ROOT)), 'toUpperCase');
});

test('utf8/four-byte', static function (): void {
    $text = "a😀𝄞";
    $s = new Java('java.lang.String', $text);
    same(5, java_values($s->length()), 'UTF-16 length');
    same(3, java_values($s->codePointCount(0, 5)), 'code points');
    same(0x1F600, java_values($s->codePointAt(1)), 'codePointAt');
    same($text, java_values($s), 'round trip');
    same('😀', java_values($s->substring(1, 3)), 'surrogate pair substring');
});

test('utf8/nul-characters', static function (): void {
    $text = "a\0b\0";
    $s = new Java('java.lang.String', $text);
    same(4, java_values($s->length()), 'length');
    same(0, java_values($s->codePointAt(1)), 'NUL code point');
    same($text, java_values($s), 'round trip');
    $map = javaMap(["k\0" => "v\0"]);
    same(["k\0" => "v\0"], java_values($map), 'NUL in map keys and values');
});

test('utf8/invalid-becomes-replacement-characters', static function (): void {
    same("abc\u{FFFD}(", java_values(new Java('java.lang.String', "abc\xC3\x28")), 'invalid UTF-8 as a Java String');
    same(1, java_values((new Java('java.lang.String', "\xff"))->length()), 'one replacement character');
});

// ---------------------------------------------------------------------------
// Exceptions.
// ---------------------------------------------------------------------------

/**
 * Returns the class names of an exception and its causes.
 *
 * @return list<string>
 */
function causeChain($exception): array
{
    $names = [];
    for ($e = $exception; !java_is_null($e) && count($names) < 10; $e = $e->getCause()) {
        $names[] = java_values($e->getClass()->getName());
    }
    return $names;
}

test('exceptions/java-exception-is-catchable', static function (): void {
    $e = throws(JavaException::class, static fn () => java('java.lang.Integer')->parseInt('x'));
    check($e instanceof java_exception && $e instanceof java_JavaType && $e instanceof Exception, 'class hierarchy');
    check(in_array('java.lang.NumberFormatException', causeChain($e), true), 'cause chain: ' . implode(' <- ', causeChain($e)));
    contains('NumberFormatException', (string) $e, '(string) $e');
    contains('NumberFormatException', java_values($e->toString()), '$e->toString()');
    same(5, java_values(java('java.lang.Integer')->parseInt('5')), 'connection usable afterwards');
});

test('exceptions/get-cause', static function (): void {
    $cause = new Java('java.lang.IllegalStateException', 'inner');
    $outer = new Java('java.lang.RuntimeException', 'outer', $cause);
    $got = $outer->getCause();
    check($got instanceof JavaException, 'getCause() returns a JavaException: ' . describe($got));
    same('inner', java_values($got->getLocalizedMessage()), 'cause message');
    check(java_is_null($got->getCause()), 'end of chain');
});

test('exceptions/missing-class', static function (): void {
    $e = throws(JavaException::class, static fn () => new Java('no.such.Klass'));
    contains('ClassNotFoundException', (string) $e, '(string) $e');
    $e = throws(JavaException::class, static fn () => new JavaClass('no.such.Klass'));
    contains('no.such.Klass', (string) $e, '(string) $e');
});

test('exceptions/missing-method', static function (): void {
    $e = throws(JavaException::class, static fn () => (new Java('java.util.ArrayList'))->noSuchMethod(1));
    contains('noSuchMethod', (string) $e, '(string) $e');
    $e = throws(JavaException::class, static fn () => java('java.lang.Math')->noSuchStatic());
    contains('noSuchStatic', (string) $e, '(string) $e');
});

test('exceptions/created-in-php', static function (): void {
    $e = new JavaException('java.lang.IllegalStateException', 'boom');
    same('boom', $e->getMessage(), 'getMessage()');
    same('java.lang.IllegalStateException', java_values($e->getClass()->getName()), 'class');
    same('boom', java_values($e->getLocalizedMessage()), 'Java message');
});

test('exceptions/unchecked-is-thrown', static function (): void {
    $e = throws(JavaException::class, static fn () => java('java.util.Objects')->checkIndex(5, 1));
    contains('IndexOutOfBoundsException', (string) $e, '(string) $e');
}, 'values');

test('exceptions/unchecked-is-logged', static function (): void {
    $result = java('java.util.Objects')->checkIndex(5, 1);
    $messages = expectLogs('warning', 1);
    contains('Unchecked exception detected', $messages[0], 'diagnostic');
    contains('IndexOutOfBoundsException', $messages[0], 'diagnostic');
    check($result instanceof JavaException, 'the exception is returned');
    Runner::$expectUncheckedAtEnd = true;
}, 'proxy');

test('exceptions/resource-argument-is-logged', static function (): void {
    $list = new Java('java.util.ArrayList');
    $handle = fopen('php://memory', 'r');
    $list->add($handle);
    fclose($handle);
    $messages = expectLogs('warning', 1);
    contains('cannot be passed to Java, using NULL instead', $messages[0], 'diagnostic');
    same([null], java_values($list), 'NULL was sent');
});

// ---------------------------------------------------------------------------
// Deferred (cached) requests and many objects.
// ---------------------------------------------------------------------------

test('cache/repeated-calls', static function (): void {
    $list = new Java('java.util.ArrayList');
    $sb = new Java('java.lang.StringBuilder');
    $expected = '';
    for ($i = 0; $i < 100; $i++) {
        $item = new Java('java.lang.StringBuilder', "item{$i}");
        $item->append('!');
        $list->add($item->toString());
        $sb->append($i);
        $sb->append(',');
        $expected .= "{$i},";
    }
    same(100, java_values($list->size()), 'list size');
    same('item99!', java_values($list->get(99)), 'last item');
    same($expected, java_values($sb->toString()), 'StringBuilder');
});

test('cache/deferred-results-resolve', static function (): void {
    $sb = new Java('java.lang.StringBuilder');
    $sb->append('warm-up');
    $sb->setLength(0);
    $first = $sb->append('a');
    $second = $sb->append('b');
    same('ab', (string) $first, 'first deferred result');
    same('ab', (string) $second, 'second deferred result');
    $list = new Java('java.util.ArrayList');
    $list->add('warm-up');
    $list->clear();
    $list->add('x');
    $list->add('y');
    same(2, java_values($list->size()), 'deferred void calls ran in order');
});

test('cache/many-objects', static function (): void {
    $integer = java('java.lang.Integer');
    $objects = [];
    for ($i = 0; $i < 2000; $i++) {
        $objects[] = $integer->valueOf($i);
    }
    $sum = 0;
    foreach ($objects as $object) {
        $sum += java_values($object);
    }
    same(1999000, $sum, 'sum of 2000 Integer objects');
    $objects = [];
    $list = new Java('java.util.ArrayList', range(1, 10000));
    same(10000, count(java_values($list)), 'list of 10000 elements');
    same(50005000, array_sum(java_values($list)), 'sum of 10000 elements');
});

// ---------------------------------------------------------------------------
// Tests that run after all others.
// ---------------------------------------------------------------------------

test('config/configure-after-connect-throws', static function (): void {
    $e = throws(java_IllegalStateException::class, static fn () => java_configure(['port' => 1]));
    contains('before the first Java call', $e->getMessage(), 'message');
});

test('lifecycle/end-of-request', static function (): void {
    java_shutdown();
    if (Runner::$expectUncheckedAtEnd) {
        $messages = expectLogs('error', 1);
        contains('unchecked exception occurred', $messages[0], 'diagnostic');
    }
    $e = throws(java_IllegalStateException::class, static fn () => new Java('java.lang.Object'));
    contains('closed', $e->getMessage(), 'message');
    java_shutdown();
});

// ---------------------------------------------------------------------------
// Runner.
// ---------------------------------------------------------------------------

/**
 * Parses the command line.
 *
 * @return array{address: string, mode: string, group: list<string>, exclude: list<string>, list: bool}
 */
function parseArguments(array $argv): array
{
    $options = [
        'address' => getenv('JAVA_BRIDGE_ADDRESS') ?: '127.0.0.1:9267',
        'mode' => 'both',
        'group' => [],
        'exclude' => [],
        'list' => false,
    ];
    foreach (array_slice($argv, 1) as $arg) {
        if (preg_match('/^--(mode|group|exclude)=(.*)$/', $arg, $match)) {
            $options[$match[1]] = $match[1] === 'mode' ? $match[2] : array_filter(explode(',', $match[2]));
        } elseif ($arg === '--list') {
            $options['list'] = true;
        } elseif (!str_starts_with($arg, '--')) {
            $options['address'] = $arg;
        } else {
            fwrite(STDERR, "Unknown option {$arg}\n");
            exit(2);
        }
    }
    if (!in_array($options['mode'], ['both', 'proxy', 'values'], true)) {
        fwrite(STDERR, "--mode must be both, proxy or values\n");
        exit(2);
    }
    if (!preg_match('/^(.+):(\d+)$/', $options['address'])) {
        fwrite(STDERR, "The server address must be host:port, got {$options['address']}\n");
        exit(2);
    }
    return $options;
}

/**
 * Runs the selected tests in one mode and returns [passed, failed, skipped].
 *
 * @return array{0: int, 1: int, 2: int}
 */
function runMode(string $mode, array $options): array
{
    Runner::$mode = $mode;
    preg_match('/^(.+):(\d+)$/', $options['address'], $address);
    [, $host, $port] = $address;
    $counts = [0, 0, 0];
    $connected = false;
    $report = static function (string $status, string $name, string $detail, string $time = '') use ($mode): void {
        $detail = java_truncate(preg_replace('/\s*\R\s*/', ' ', $detail));
        echo $status, " [{$mode}] {$name}", $detail === '' ? '' : ": {$detail}", $time === '' ? '' : " {$time}", "\n";
    };
    if (Runner::$errors) {
        $report('FAIL', 'load-client', implode('; ', Runner::$errors));
        $counts[1]++;
        Runner::$errors = [];
    }
    foreach (Runner::$tests as $name => $test) {
        $group = strstr($name, '/', true);
        $selected = in_array($mode, $test['modes'], true)
            && (!$options['group'] || in_array($group, $options['group'], true))
            && !in_array($group, $options['exclude'], true);
        $preConnect = in_array($name, ['config/bad-port-throws-connect-exception', 'config/invalid-options-are-rejected'], true);
        if (!$selected) {
            continue;
        }
        if (!$preConnect && !$connected) {
            java_configure([
                'host' => $host,
                'port' => $port,
                'prefer_values' => $mode === 'values',
                'persistent' => $mode === 'proxy',
                'connect_timeout' => 20,
                'logger' => [Runner::class, 'log'],
            ]);
            $connected = true;
        }
        Runner::$errors = [];
        Runner::$logs = [];
        $start = microtime(true);
        $status = 'PASS';
        $detail = '';
        try {
            ($test['fn'])();
        } catch (TestSkipped $e) {
            $status = 'SKIP';
            $detail = $e->getMessage();
        } catch (TestFailure $e) {
            $status = 'FAIL';
            $detail = $e->getMessage();
        } catch (Throwable $e) {
            $status = 'FAIL';
            $detail = 'unexpected ' . get_class($e) . ': ' . strtok((string) $e, "\n");
        }
        gc_collect_cycles();
        $problems = array_merge(Runner::$errors, array_map(static fn ($log) => "logged {$log[0]}: {$log[1]}", Runner::$logs));
        if ($problems && $status !== 'FAIL') {
            $status = 'FAIL';
            $detail = implode('; ', $problems);
        } elseif ($problems) {
            $detail .= ' (also: ' . implode('; ', $problems) . ')';
        }
        $elapsed = microtime(true) - $start;
        $report($status, $name, $detail, $status !== 'SKIP' && $elapsed >= 0.1 ? sprintf('(%.2fs)', $elapsed) : '');
        $counts[['PASS' => 0, 'FAIL' => 1, 'SKIP' => 2][$status]]++;
    }
    return $counts;
}

/**
 * Runs this script again in a child process for one mode and returns its counts.
 *
 * @return array{0: int, 1: int, 2: int}
 */
function runChild(string $mode, array $options): array
{
    $command = [PHP_BINARY];
    $ini = php_ini_loaded_file();
    array_push($command, ...($ini === false ? ['-n'] : ['-c', $ini]));
    array_push($command, __FILE__, $options['address'], "--mode={$mode}");
    if ($options['group']) {
        $command[] = '--group=' . implode(',', $options['group']);
    }
    if ($options['exclude']) {
        $command[] = '--exclude=' . implode(',', $options['exclude']);
    }
    $process = proc_open($command, [1 => ['pipe', 'w'], 2 => ['redirect', 1]], $pipes);
    $counts = [0, 1, 0];
    while (($line = fgets($pipes[1])) !== false) {
        if (preg_match('/^#COUNTS (\d+) (\d+) (\d+)$/', trim($line), $match)) {
            $counts = [(int) $match[1], (int) $match[2], (int) $match[3]];
        } else {
            echo $line;
        }
    }
    fclose($pipes[1]);
    if (proc_close($process) !== 0 && $counts[1] === 0) {
        $counts[1] = 1;
        echo "FAIL [{$mode}] child process exited with an error\n";
    }
    return $counts;
}

$options = parseArguments($argv);
if ($options['list']) {
    foreach (Runner::$tests as $name => $test) {
        echo $name, count($test['modes']) === 1 ? " ({$test['modes'][0]} only)" : '', "\n";
    }
    exit(0);
}

if (getenv('JAVA_BRIDGE_TEST_CHILD') === '1') {
    $totals = runMode($options['mode'], $options);
    echo "#COUNTS {$totals[0]} {$totals[1]} {$totals[2]}\n";
    exit($totals[1] > 0 ? 1 : 0);
}

echo "PHP/Java Bridge client tests against {$options['address']} (PHP " . PHP_VERSION . ")\n";
if ($options['mode'] === 'both') {
    $proxy = runMode('proxy', $options);
    putenv('JAVA_BRIDGE_TEST_CHILD=1');
    $values = runChild('values', $options);
    $totals = [$proxy[0] + $values[0], $proxy[1] + $values[1], $proxy[2] + $values[2]];
} else {
    $totals = runMode($options['mode'], $options);
}

echo "\n{$totals[0]} passed, {$totals[1]} failed, {$totals[2]} skipped\n";
exit($totals[1] > 0 ? 1 : 0);
