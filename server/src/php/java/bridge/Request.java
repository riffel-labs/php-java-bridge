/*-*- mode: Java; tab-width:8 -*-*/

package php.java.bridge;

/*
 * Copyright (C) 2003-2007 Jost Boekemeier
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER(S) OR AUTHOR(S) BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */


import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigInteger;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import php.java.bridge.spi.JsonCodec;

/**
 * Reads the requests of one connection and dispatches them to the bridge. Each message is a JSON object
 * prefixed with its length; see docs/protocol.md.
 * @author jostb
 *
 */
public final class Request {

    /** The protocol version this server speaks. */
    static final int PROTOCOL_VERSION = 2;

    /** Largest message accepted before the client is ready (hello and auth are small). */
    static final int PRE_AUTH_LIMIT = 64 * 1024;

    /** Largest message accepted afterwards. */
    static final int MESSAGE_LIMIT = Integer.MAX_VALUE - 8;

    /** Prefix of the data a client signs to prove its identity (see docs/protocol.md). */
    static final byte[] PROOF_PREFIX = "php-java-bridge-auth-v1\0".getBytes(StandardCharsets.US_ASCII);

    private static final SecureRandom RANDOM = new SecureRandom();

    protected static final IntegerComparator PHP_ARRAY_KEY_COMPARATOR = new IntegerComparator();
    // Only used when the async. protocol is enabled.
    protected static final class PhpNull {
	public String toString () {
	    return ""; //evaluates to false
	}
    }
    protected static final PhpNull PHPNULL = new PhpNull();
    protected Object getGlobalRef(int i) {
	Object ref = bridge.globalRef.get(i);
	if(ref == PHPNULL) return null;
	return ref;
    }

    static final Object[] ZERO_ARGS = new Object[0];

    /** A protocol violation: the client is told why, then the connection is closed. */
    static final class ProtocolException extends IOException {
	private static final long serialVersionUID = 1L;
	ProtocolException(String message) {
	    super(message);
	}
	ProtocolException(String message, Throwable cause) {
	    super(message, cause);
	}
    }

    private final JavaBridge bridge;
    private final Connection connection;
    private final JsonCodec json;
    private final DataInputStream in;
    private byte[] buffer = new byte[8192];

    private boolean helloDone;
    private byte[] challenge;      // set while the client still has to prove its identity
    private boolean ready;         // hello done and the client accepted

    /**
     * The response handle, one for each connection.
     */
    Response response = null;

    /**
     * Creates the request reader of a connection.
     * @param bridge The bridge instance.
     * @param connection The connection: socket and security provider.
     */
    Request(JavaBridge bridge, Connection connection) {
	this.bridge = bridge;
	this.connection = connection;
	this.json = InternalLibraries.get().json();
	this.in = new DataInputStream(bridge.in);
    }

    /**
     * Handle requests until the client closes the connection or a protocol error occurs.
     * @throws IOException if the connection fails
     */
    public void handleRequests() throws IOException {
	if (response == null) response = bridge.createResponse();
	try {
	    for (Map<String, Object> msg = read(); msg != null; msg = read()) {
		if (!dispatch(msg)) return;
	    }
	} catch (ProtocolException e) {
	    bridge.logError("Protocol error, closing the connection: " + e.getMessage());
	    try {
		response.writeError(e.getMessage());
	    } catch (IOException ignored) {
		/* the client is gone */
	    }
	}
    }

    // ---------------------------------------------------------------- reading

    /** @return the next message, or null when the client has closed the connection */
    private Map<String, Object> read() throws IOException {
	int length;
	try {
	    length = in.readInt();
	} catch (EOFException e) {
	    return null;
	} catch (SocketException | InterruptedIOException e) {
	    // the connection was closed, for example by BridgeServer.stop()
	    if (Util.logLevel > 5) Util.printStackTrace(e);
	    return null;
	}
	int limit = ready ? MESSAGE_LIMIT : PRE_AUTH_LIMIT;
	if (length < 0 || length > limit) throw new ProtocolException("message too large: " + Integer.toUnsignedString(length) + " bytes");
	byte[] b = length <= buffer.length ? buffer : new byte[length];
	try {
	    in.readFully(b, 0, length);
	} catch (EOFException e) {
	    throw new ProtocolException("connection closed in the middle of a message");
	}
	if (bridge.logLevel > 3) {
	    int n = Math.min(length, 1000);
	    bridge.logDebug("--> " + new String(b, 0, n, StandardCharsets.UTF_8) + (length > n ? "..." : ""));
	}
	Object message;
	try {
	    message = json.parse(b, 0, length);
	} catch (IOException e) {
	    throw new ProtocolException(e.getMessage(), e);
	}
	if (!(message instanceof Map)) throw new ProtocolException("a message must be a JSON object");
	@SuppressWarnings("unchecked")
	Map<String, Object> m = (Map<String, Object>) message;
	return m;
    }

    // ---------------------------------------------------------------- dispatch

    /** @return false if the connection ends */
    private boolean dispatch(Map<String, Object> msg) throws IOException {
	String op = string(msg, "op");
	if (!helloDone) {
	    if (!"hello".equals(op)) throw new ProtocolException("the first message must be \"hello\", got \"" + op + "\"");
	    hello(msg);
	    return true;
	}
	if (challenge != null) {
	    if (!"auth".equals(op)) throw new ProtocolException("the server asked for a client certificate; expected \"auth\", got \"" + op + "\"");
	    auth(msg);
	    return true;
	}
	switch (op) {
	case "begin":
	    begin(msg);
	    return true;
	case "new":
	    async(msg);
	    bridge.CreateObject(string(msg, "class"), true, args(msg), response);
	    break;
	case "class":
	    async(msg);
	    bridge.CreateObject(string(msg, "class"), false, args(msg), response);
	    break;
	case "call":
	    async(msg);
	    bridge.Invoke(target(msg), string(msg, "name"), args(msg), response);
	    break;
	case "prop":
	    async(msg);
	    bridge.GetSetProp(target(msg), string(msg, "name"), args(msg), response);
	    break;
	case "unref": {
	    int id = id(msg, "obj");
	    if (Util.logLevel > 4) Util.logDebug("unref: " + id);
	    bridge.globalRef.remove(id);
	    return true; // no reply
	}
	case "end":
	    if (bool(msg, "keep")) { // the PHP request ends; the connection is kept for the next one
		bridge.recycle();
		response.setFinish(true);
		response.flush();
		return true;
	    }
	    response.setFinish(false);
	    response.flush();
	    return false;
	case "hello":
	    throw new ProtocolException("\"hello\" was already sent on this connection");
	case "auth":
	    throw new ProtocolException("\"auth\" was not asked for");
	default:
	    throw new ProtocolException("unknown op \"" + op + "\"");
	}
	response.flush();
	return true;
    }

    private void hello(Map<String, Object> msg) throws IOException {
	Object v = msg.get("v");
	if (!(v instanceof Long) || ((Long) v).longValue() != PROTOCOL_VERSION) {
	    throw new ProtocolException("protocol version " + v + " is not supported: this server (" + Util.VERSION
					+ ") speaks version " + PROTOCOL_VERSION + "; use the PHP client of the same release");
	}
	helloDone = true;
	if (connection.security.requiresProof(connection.socket)) {
	    challenge = new byte[32];
	    RANDOM.nextBytes(challenge);
	    response.writeHello(challenge);
	} else {
	    accept();
	    response.writeHello(null);
	}
    }

    private void auth(Map<String, Object> msg) throws IOException {
	String cert = string(msg, "cert");
	byte[] signature;
	try {
	    signature = Base64.getDecoder().decode(string(msg, "sig"));
	} catch (IllegalArgumentException e) {
	    throw new ProtocolException("auth: \"sig\" is not base64");
	}
	try {
	    connection.security.verifyProof(connection.socket, challenge, cert, signature);
	} catch (GeneralSecurityException e) {
	    throw new ProtocolException("client certificate rejected: " + e.getMessage());
	}
	challenge = null;
	accept();
	response.writeAuthOk();
    }

    /** The client may now send requests. */
    private void accept() throws IOException {
	ready = true;
	connection.socket.setSoTimeout(0); // a PHP process may keep its connection idle between requests
    }

    /** "begin": the options of the next PHP request. No reply. */
    private void begin(Map<String, Object> msg) throws IOException {
	Object values = msg.get("values");
	boolean preferValues = Boolean.TRUE.equals(values);
	if (preferValues != bridge.options.preferValues()) {
	    bridge.options.setPreferValues(preferValues);
	    response.optionsChanged();
	}
	Object log = msg.get("log");
	if (log instanceof Long) {
	    int level = (int) Math.max(0, Math.min(6, ((Long) log).longValue()));
	    if (bridge.logLevel > 3 && bridge.logLevel != level) bridge.logDebug("Client changed its request log level to: " + level);
	    bridge.logLevel = level;
	} else {
	    bridge.logLevel = Util.logLevel;
	}
    }

    /** "async": "ref" stores the result under the next object id without a reply; "void" drops it. */
    private void async(Map<String, Object> msg) throws IOException {
	Object a = msg.get("async");
	if (a == null) return;
	if ("ref".equals(a)) response.setAsyncWriter();
	else if ("void".equals(a)) response.setAsyncVoidWriter();
	else throw new ProtocolException("\"async\" must be \"ref\" or \"void\", got " + a);
    }

    private Object target(Map<String, Object> msg) throws IOException {
	int id = id(msg, "obj");
	return id == 0 ? bridge : ref(id);
    }

    private Object ref(int id) throws IOException {
	try {
	    return getGlobalRef(id);
	} catch (NullPointerException e) {
	    throw new ProtocolException(e.getMessage());
	}
    }

    // ---------------------------------------------------------------- arguments

    private Object[] args(Map<String, Object> msg) throws IOException {
	Object a = msg.get("args");
	if (a == null) return ZERO_ARGS;
	if (!(a instanceof List)) throw new ProtocolException("\"args\" must be an array");
	boolean tagged = Boolean.TRUE.equals(msg.get("tagged"));
	List<?> list = (List<?>) a;
	Object[] args = new Object[list.size()];
	for (int i = 0; i < args.length; i++) args[i] = argument(list.get(i), tagged);
	return args;
    }

    /** A top-level argument: strings and integers keep their PHP nature until the parameter type is known. */
    private Object argument(Object v, boolean tagged) throws IOException {
	if (v instanceof String) return new PhpStringValue((String) v);
	if (v instanceof Long) return new PhpExactNumber(((Long) v).longValue());
	if (v instanceof Map) {
	    Map<?, ?> m = (Map<?, ?>) v;
	    return tagged ? tag(m, true) : array(m, false);
	}
	if (v instanceof List) return array((List<?>) v, tagged);
	return scalar(v);
    }

    /** An element of a PHP array: plain Java values, since the array may be passed to Java as it is. */
    private Object element(Object v, boolean tagged) throws IOException {
	if (v instanceof String) return v;
	if (v instanceof Long) {
	    long l = ((Long) v).longValue();
	    return l == (int) l ? (Object) Integer.valueOf((int) l) : (Object) Long.valueOf(l);
	}
	if (v instanceof Map) {
	    Map<?, ?> m = (Map<?, ?>) v;
	    return tagged ? tag(m, false) : array(m, false);
	}
	if (v instanceof List) return array((List<?>) v, tagged);
	return scalar(v);
    }

    private static Object scalar(Object v) {
	if (v instanceof BigInteger) return Double.valueOf(((BigInteger) v).doubleValue());
	return v; // Double, Boolean or null
    }

    private PhpArray array(List<?> list, boolean tagged) throws IOException {
	PhpArray a = new PhpArray();
	for (int i = 0, n = list.size(); i < n; i++) a.put(Integer.valueOf(i), element(list.get(i), tagged));
	return a;
    }

    private PhpArray array(Map<?, ?> map, boolean tagged) throws IOException {
	PhpArray a = new PhpArray();
	for (Map.Entry<?, ?> e : map.entrySet()) a.put(key((String) e.getKey()), element(e.getValue(), tagged));
	return a;
    }

    /** PHP stores a decimal integer key like "12" as the integer 12; JSON object keys are always strings. */
    static Object key(String k) {
	int n = k.length();
	if (n == 0 || n > 20) return k;
	int i = k.charAt(0) == '-' ? 1 : 0;
	if (i == n) return k;
	if (k.charAt(i) == '0' && n > i + 1) return k;       // leading zero: a string key in PHP
	if (i == 1 && k.charAt(1) == '0') return k;          // "-0" is a string key too
	for (int j = i; j < n; j++) {
	    char c = k.charAt(j);
	    if (c < '0' || c > '9') return k;
	}
	try {
	    long l = Long.parseLong(k);
	    return l == (int) l ? (Object) Integer.valueOf((int) l) : (Object) Long.valueOf(l);
	} catch (NumberFormatException e) {
	    return k; // beyond a PHP integer: PHP keeps it as a string
	}
    }

    /** A tag of the tagged form: {"obj": id}, {"map": {...}}, {"bytes": base64} or {"float": "NaN"|"INF"|"-INF"}. */
    private Object tag(Map<?, ?> m, boolean topLevel) throws IOException {
	if (m.size() == 1) {
	    Object v;
	    if ((v = m.get("obj")) != null) {
		if (!(v instanceof Long)) throw new ProtocolException("\"obj\" must be an object id");
		long id = ((Long) v).longValue();
		return id == 0 ? null : ref((int) id);
	    }
	    if ((v = m.get("map")) != null) {
		if (!(v instanceof Map)) throw new ProtocolException("\"map\" must be a JSON object");
		return array((Map<?, ?>) v, true);
	    }
	    if ((v = m.get("bytes")) != null) {
		byte[] b;
		try {
		    b = Base64.getDecoder().decode((String) v);
		} catch (IllegalArgumentException | ClassCastException e) {
		    throw new ProtocolException("\"bytes\" must be base64");
		}
		// a binary string keeps its bytes as an argument; inside an array it becomes a Java String
		return topLevel ? new PhpStringValue(b) : new String(b, StandardCharsets.UTF_8);
	    }
	    if ((v = m.get("float")) != null) {
		if ("NaN".equals(v)) return Double.valueOf(Double.NaN);
		if ("INF".equals(v)) return Double.valueOf(Double.POSITIVE_INFINITY);
		if ("-INF".equals(v)) return Double.valueOf(Double.NEGATIVE_INFINITY);
	    }
	}
	throw new ProtocolException("unknown tag " + m.keySet());
    }

    // ---------------------------------------------------------------- fields

    private static String string(Map<String, Object> msg, String name) throws ProtocolException {
	Object v = msg.get(name);
	if (!(v instanceof String)) throw new ProtocolException("\"" + name + "\" must be a string");
	return (String) v;
    }

    private static boolean bool(Map<String, Object> msg, String name) throws ProtocolException {
	Object v = msg.get(name);
	if (!(v instanceof Boolean)) throw new ProtocolException("\"" + name + "\" must be true or false");
	return ((Boolean) v).booleanValue();
    }

    private static int id(Map<String, Object> msg, String name) throws ProtocolException {
	Object v = msg.get(name);
	if (!(v instanceof Long) || ((Long) v).longValue() < 0 || ((Long) v).longValue() > Integer.MAX_VALUE) {
	    throw new ProtocolException("\"" + name + "\" must be an object id");
	}
	return (int) ((Long) v).longValue();
    }

    /** re-initialize for requests */
    public void recycle() {
        if (response != null) response.recycle();
    }
}
