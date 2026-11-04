/*
 * Copyright (C) 2026 Jason Riffel
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

package php.java.bridge;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import php.java.bridge.spi.TlsSupport;
import php.java.bridge.spi.TlsSupport.KeyMaterial;

/**
 * Self-contained server tests (no test framework). Each test starts a real server in this JVM on a free port and
 * talks to it with the wire protocol over a socket, the way the PHP client does.
 *
 * Run: java -Dphp.java.bridge.internal=dist/internal -cp build/classes:build/test-classes php.java.bridge.ServerTests
 * Exits with status 1 if any test fails.
 */
public final class ServerTests {

    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        Util.setDefaultLogger(new QuietLogger());

        run("start and stop on a free port", ServerTests::startStop);
        run("binding a port in use throws instead of exiting", ServerTests::bindFailureThrows);
        run("hello: version check, and it must come first", ServerTests::hello);
        run("create an object and call methods", ServerTests::createAndInvoke);
        run("static method through a class reference", ServerTests::staticCall);
        run("exception returned to PHP reaches the listener, not the error count", ServerTests::exceptionListener);
        run("missing class and missing method", ServerTests::missingClassAndMethod);
        run("non-public JDK classes without --add-opens", ServerTests::nonPublicJdkClasses);
        run("public fields", ServerTests::publicFields);
        run("setting a primitive field when values are preferred", ServerTests::primitiveFieldSet);
        run("PHP array argument becomes a Java map", ServerTests::compositeArgument);
        run("values come back plain unless they need tags", ServerTests::plainAndTaggedValues);
        run("tagged arguments: references, binary strings, NaN, maps", ServerTests::taggedArguments);
        run("integer-like map keys become integers, as in PHP", ServerTests::mapKeys);
        run("deferred calls store their result under the next id", ServerTests::asyncCalls);
        run("released object can no longer be used", ServerTests::unref);
        run("protocol errors are reported, then the connection closes", ServerTests::protocolErrors);
        run("connection recycling between PHP requests", ServerTests::recycle);
        run("internal libraries are invisible to PHP", ServerTests::internalLibrariesIsolated);
        run("stats count connections and busy threads", ServerTests::stats);
        run("thread limit makes new connections wait", ServerTests::threadLimit);
        run("stop closes open connections", ServerTests::stopClosesConnections);
        run("tls-anonymous: encrypted, no client identity; a TCP port check is not an error", ServerTests::tlsAnonymous);
        run("tls-certificates: client certificate in the TLS handshake", ServerTests::tlsClientCertificate);
        run("tls-certificates: client proves its identity with a signed challenge", ServerTests::tlsProof);
        run("tls-certificates: rejected proofs", ServerTests::tlsRejectedProofs);
        run("tls-certificates: allowed client names, common names and URIs", ServerTests::tlsAllowedClients);
        run("tls-certificates: credentials can change while running", ServerTests::tlsRotation);
        run("TLS credentials from PEM and files", ServerTests::credentials);
        run("command line parsing", ServerTests::commandLine);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    interface Test { void run() throws Exception; }

    private static void run(String name, Test t) {
        try {
            t.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("FAIL " + name + ": " + e);
            if (!(e instanceof AssertionError)) e.printStackTrace(System.out);
        }
    }

    /** A server on a free port without TLS, for the protocol tests. */
    private static BridgeServer.Builder plain() {
        return BridgeServer.builder().port(0).security(BridgeSecurity.plain());
    }

    // ---------------------------------------------------------------- basics

    private static void startStop() throws Exception {
        BridgeServer s = plain().threads(3).start();
        try {
            check(s.getPort() > 0, "port assigned: " + s.getPort());
            check(s.getAddress().isLoopbackAddress(), "listens on loopback by default");
            BridgeServer.Stats st = s.stats();
            check(st.maxThreads() == 3 && st.busyThreads() == 0 && st.connectionsAccepted() == 0, "initial stats " + st);
            try {
                s.start();
                throw new AssertionError("second start should fail");
            } catch (IllegalStateException expected) { /* ok */ }
        } finally {
            s.stop();
        }
        s.stop(); // second stop does nothing
        check(!canConnect(s.getPort()), "port closed after stop");
        check(BridgeServer.builder().build().getSecurity().name().equals("tls-anonymous"), "default security");
    }

    private static void bindFailureThrows() throws Exception {
        try (BridgeServer a = plain().start()) {
            try {
                BridgeServer.builder().port(a.getPort()).security(BridgeSecurity.plain()).start();
                throw new AssertionError("expected an IOException");
            } catch (IOException expected) { /* ok, and the JVM is still running */ }
        }
    }

    private static void hello() throws Exception {
        try (BridgeServer s = plain().start()) {
            try (Conn c = new Conn(s.getPort(), false)) {
                Map<String, Object> r = c.call(j("{'op':'hello','v':2}"));
                check("php-java-bridge".equals(r.get("hello")) && Long.valueOf(2).equals(r.get("v")), "hello reply " + r);
                check(Util.VERSION.equals(r.get("server")) && !r.containsKey("challenge"), "no challenge without certificates " + r);
            }
            try (Conn c = new Conn(s.getPort(), false)) {
                Map<String, Object> r = c.call(j("{'op':'hello','v':1}"));
                check(String.valueOf(r.get("error")).contains("version 1 is not supported"), "old version refused " + r);
                check(c.closed(), "closed after the error");
            }
            try (Conn c = new Conn(s.getPort(), false)) {
                Map<String, Object> r = c.call(j("{'op':'new','class':'java.lang.Object'}"));
                check(String.valueOf(r.get("error")).contains("first message must be \"hello\""), "hello first " + r);
            }
        }
    }

    private static void createAndInvoke() throws Exception {
        try (BridgeServer s = plain().start(); Conn c = new Conn(s.getPort())) {
            c.begin(true);
            long sb = objectId(c.call(j("{'op':'new','class':'java.lang.StringBuilder','args':['abc']}")));
            c.call(j("{'op':'call','obj':" + sb + ",'name':'append','args':['déf ✓ \\'q\\'']}"));
            // String results are object references even when values are preferred; the client fetches the value
            // with getValues on the bridge object (id 0), which is what java_values() does
            Object text = c.value(objectId(c.call(j("{'op':'call','obj':" + sb + ",'name':'toString'}"))));
            check("abcdéf ✓ 'q'".equals(text), "toString: " + text);
            Map<String, Object> len = c.call(j("{'op':'call','obj':" + sb + ",'name':'length'}"));
            check(Long.valueOf(12).equals(len.get("value")) && !len.containsKey("tagged"), "length is a plain value: " + len);
        }
    }

    private static void staticCall() throws Exception {
        try (BridgeServer s = plain().start(); Conn c = new Conn(s.getPort())) {
            c.begin(true);
            long integer = objectId(c.call(j("{'op':'class','class':'java.lang.Integer'}")));
            Object bits = c.value(objectId(c.call(j("{'op':'call','obj':" + integer + ",'name':'toBinaryString','args':[5]}"))));
            check("101".equals(bits), "Integer.toBinaryString(5): " + bits);
            long math = objectId(c.call(j("{'op':'class','class':'java.lang.Math'}")));
            Map<String, Object> abs = c.call(j("{'op':'call','obj':" + math + ",'name':'absExact','args':[-9007199254740993]}"));
            // a long result is exact now, even beyond 2^53
            check(Long.valueOf(9007199254740993L).equals(abs.get("value")), "Math.absExact(-(2^53+1)): " + abs);
        }
    }

    private static void exceptionListener() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        try (BridgeServer s = plain()
                 .exceptionListener((cause, message) -> seen.add(cause.getClass().getName() + "|" + message))
                 .start();
             Conn c = new Conn(s.getPort())) {
            long integer = objectId(c.call(j("{'op':'class','class':'java.lang.Integer'}")));
            Map<String, Object> r = c.call(j("{'op':'call','obj':" + integer + ",'name':'parseInt','args':['not <a> number']}"));
            check(r.get("exception") instanceof Long && Boolean.TRUE.equals(r.get("throws")), "exception reply: " + r);
            check(seen.size() == 1, "listener called once: " + seen);
            String event = seen.get(0);
            check(event.startsWith("java.lang.NumberFormatException|Invoke failed: [[c:Integer]]->parseInt"), event);
            check(event.contains(" VM: "), "message names the VM: " + event);
            check(s.stats().errors() == 0, "returned exceptions are not errors: " + s.stats());
        }
    }

    private static void missingClassAndMethod() throws Exception {
        List<Throwable> seen = new CopyOnWriteArrayList<>();
        try (BridgeServer s = plain().exceptionListener((cause, m) -> seen.add(cause)).start();
             Conn c = new Conn(s.getPort())) {
            check(c.call(j("{'op':'new','class':'does.not.Exist'}")).containsKey("exception"), "missing class");
            long sb = objectId(c.call(j("{'op':'new','class':'java.lang.StringBuilder'}")));
            check(c.call(j("{'op':'call','obj':" + sb + ",'name':'noSuchMethod'}")).containsKey("exception"), "missing method");
            check(seen.size() == 2 && seen.get(0) instanceof ClassNotFoundException
                  && seen.get(1) instanceof NoSuchMethodException, "causes: " + seen);
        }
    }

    private static void nonPublicJdkClasses() throws Exception {
        try (BridgeServer s = plain().start(); Conn c = new Conn(s.getPort())) {
            c.begin(true);
            long map = objectId(c.call(j("{'op':'new','class':'java.util.HashMap'}")));
            c.call(j("{'op':'call','obj':" + map + ",'name':'put','args':['k','v']}"));
            long keys = objectId(c.call(j("{'op':'call','obj':" + map + ",'name':'keySet'}"))); // HashMap$KeySet
            Map<String, Object> arr = c.call(j("{'op':'call','obj':" + keys + ",'name':'toArray'}"));
            check(!arr.containsKey("exception"), "KeySet.toArray: " + arr);

            long list = objectId(c.call(j("{'op':'new','class':'java.util.ArrayList'}")));
            c.call(j("{'op':'call','obj':" + list + ",'name':'add','args':['x']}"));
            long it = objectId(c.call(j("{'op':'call','obj':" + list + ",'name':'iterator'}"))); // ArrayList$Itr
            Map<String, Object> has = c.call(j("{'op':'call','obj':" + it + ",'name':'hasNext'}"));
            check(Boolean.TRUE.equals(has.get("value")), "Itr.hasNext: " + has);
            check("x".equals(c.value(objectId(c.call(j("{'op':'call','obj':" + it + ",'name':'next'}"))))), "Itr.next");

            long coll = objectId(c.call(j("{'op':'class','class':'java.util.Collections'}")));
            long ro = objectId(c.call(j("{'op':'call','obj':" + coll + ",'name':'unmodifiableList','args':[{'obj':" + list
                                        + "}],'tagged':true}")));
            Map<String, Object> size = c.call(j("{'op':'call','obj':" + ro + ",'name':'size'}"));
            check(Long.valueOf(1).equals(size.get("value")), "unmodifiableList.size: " + size);
        }
    }

    private static void publicFields() throws Exception {
        try (BridgeServer s = plain().start(); Conn c = new Conn(s.getPort())) {
            c.begin(true);
            long integer = objectId(c.call(j("{'op':'class','class':'java.lang.Integer'}")));
            Map<String, Object> max = c.call(j("{'op':'prop','obj':" + integer + ",'name':'MAX_VALUE'}"));
            check(Long.valueOf(Integer.MAX_VALUE).equals(max.get("value")), "Integer.MAX_VALUE: " + max);
        }
    }

    private static void primitiveFieldSet() throws Exception {
        // 6.2.1 failed here: it wrote the (null) result of the set as an int and answered NoSuchFieldException
        try (BridgeServer s = plain().start(); Conn c = new Conn(s.getPort())) {
            c.begin(true);
            long point = objectId(c.call(j("{'op':'new','class':'java.awt.Point'}")));
            Map<String, Object> set = c.call(j("{'op':'prop','obj':" + point + ",'name':'x','args':[5]}"));
            check(set.containsKey("value") && set.get("value") == null, "set answers null: " + set);
            Map<String, Object> get = c.call(j("{'op':'prop','obj':" + point + ",'name':'x'}"));
            check(Long.valueOf(5).equals(get.get("value")), "x is 5: " + get);
        }
    }

    private static void compositeArgument() throws Exception {
        try (BridgeServer s = plain().start(); Conn c = new Conn(s.getPort())) {
            c.begin(true);
            long map = objectId(c.call(j("{'op':'new','class':'java.util.HashMap','args':[{'a':1,'b':2}]}")));
            Map<String, Object> size = c.call(j("{'op':'call','obj':" + map + ",'name':'size'}"));
            check(Long.valueOf(2).equals(size.get("value")), "map size: " + size);
            Map<String, Object> v = c.call(j("{'op':'call','obj':0,'name':'getValues','args':[{'obj':" + map + "}],'tagged':true}"));
            check(v.get("value") instanceof Map && ((Map<?, ?>) v.get("value")).get("b").equals(2L), "getValues of a map: " + v);
        }
    }

    private static void plainAndTaggedValues() throws Exception {
        try (BridgeServer s = plain().start(); Conn c = new Conn(s.getPort())) {
            c.begin(true);
            long list = objectId(c.call(j("{'op':'new','class':'java.util.ArrayList','args':[[1,'two',3.5,true,null]]}")));
            Map<String, Object> v = c.values(list);
            check(List.of(1L, "two", 3.5, true).equals(((List<?>) v.get("value")).subList(0, 4)) && !v.containsKey("tagged"),
                  "scalars only: plain " + v);

            long date = objectId(c.call(j("{'op':'new','class':'java.util.Date'}")));
            c.call(j("{'op':'call','obj':" + list + ",'name':'add','args':[{'obj':" + date + "}],'tagged':true}"));
            v = c.values(list);
            check(Boolean.TRUE.equals(v.get("tagged")), "an object reference makes it tagged " + v);
            Map<?, ?> ref = (Map<?, ?>) ((List<?>) v.get("value")).get(5);
            check("java.util.Date".equals(ref.get("class")) && ref.get("obj") instanceof Long && !ref.containsKey("throws"),
                  "nested reference " + ref);

            long bytes = objectId(c.call(j("{'op':'call','obj':" + date + ",'name':'toString'}")));
            Object raw = c.call(j("{'op':'call','obj':" + bytes + ",'name':'getBytes','args':['UTF-16']}")).get("value");
            check(raw instanceof Map && ((Map<?, ?>) raw).containsKey("obj"), "byte[] result is a reference in values mode " + raw);
            Map<String, Object> bin = c.values(((Number) ((Map<?, ?>) raw).get("obj")).longValue());
            check(Boolean.TRUE.equals(bin.get("tagged")) && ((Map<?, ?>) bin.get("value")).containsKey("bytes"),
                  "UTF-16 bytes are not UTF-8: a bytes tag " + bin);

            long dbl = objectId(c.call(j("{'op':'class','class':'java.lang.Double'}")));
            Map<String, Object> nan = c.call(j("{'op':'prop','obj':" + dbl + ",'name':'NaN'}"));
            check(Map.of("float", "NaN").equals(nan.get("value")) && Boolean.TRUE.equals(nan.get("tagged")), "NaN " + nan);

            long hm = objectId(c.call(j("{'op':'new','class':'java.util.LinkedHashMap','args':[{'x':[1,2],'y':{'z':'w'}}]}")));
            v = c.values(hm);
            check(!v.containsKey("tagged") && v.get("value").equals(Map.of("x", Map.of("0", 1L, "1", 2L), "y", Map.of("z", "w"))),
                  "nested maps stay plain " + v);
            c.call(j("{'op':'call','obj':" + hm + ",'name':'put','args':['d',{'obj':" + date + "}],'tagged':true}"));
            v = c.values(hm);
            Map<?, ?> tagged = (Map<?, ?>) v.get("value");
            check(Boolean.TRUE.equals(v.get("tagged")) && tagged.containsKey("map")
                  && ((Map<?, ?>) ((Map<?, ?>) tagged.get("map")).get("y")).containsKey("map"), "maps wrapped when tagged " + v);
        }
    }

    private static void taggedArguments() throws Exception {
        try (BridgeServer s = plain().start(); Conn c = new Conn(s.getPort())) {
            c.begin(true);
            // a binary string keeps its exact bytes when the parameter is byte[]
            long str = objectId(c.call(j("{'op':'new','class':'java.lang.String','args':[{'bytes':'/w=='},'ISO-8859-1'],'tagged':true}")));
            Object latin = c.value(str);
            check("ÿ".equals(latin), "byte 0xFF as ISO-8859-1: " + latin);

            long dbl = objectId(c.call(j("{'op':'class','class':'java.lang.Double'}")));
            Map<String, Object> isNaN = c.call(j("{'op':'call','obj':" + dbl + ",'name':'isNaN','args':[{'float':'NaN'}],'tagged':true}"));
            check(Boolean.TRUE.equals(isNaN.get("value")), "NaN argument " + isNaN);
            Map<String, Object> inf = c.call(j("{'op':'call','obj':" + dbl + ",'name':'isInfinite','args':[{'float':'-INF'}],'tagged':true}"));
            check(Boolean.TRUE.equals(inf.get("value")), "-INF argument " + inf);

            long map = objectId(c.call(j("{'op':'new','class':'java.util.HashMap','args':[{'map':{'k':{'obj':" + dbl + "}}}],'tagged':true}")));
            Map<String, Object> got = c.call(j("{'op':'call','obj':" + map + ",'name':'get','args':['k']}"));
            check("java.lang.Class".equals(((Map<?, ?>) got.get("value")).get("class")), "a reference inside a map " + got);

            Map<String, Object> bad = c.call(j("{'op':'call','obj':" + map + ",'name':'get','args':[{'nope':1}],'tagged':true}"));
            check(String.valueOf(bad.get("error")).contains("unknown tag"), "unknown tag " + bad);
        }
    }

    private static void mapKeys() {
        check(Integer.valueOf(12).equals(Request.key("12")) && Integer.valueOf(-3).equals(Request.key("-3")), "decimal keys");
        check(Long.valueOf(5_000_000_000L).equals(Request.key("5000000000")), "beyond int: Long");
        check("012".equals(Request.key("012")) && "-0".equals(Request.key("-0")) && "1.5".equals(Request.key("1.5"))
              && "".equals(Request.key("")) && "9223372036854775808".equals(Request.key("9223372036854775808")),
              "other keys stay strings, as in PHP");
    }

    private static void asyncCalls() throws Exception {
        try (BridgeServer s = plain().start(); Conn c = new Conn(s.getPort())) {
            long sb = objectId(c.call(j("{'op':'new','class':'java.lang.StringBuilder'}")));
            c.send(j("{'op':'call','obj':" + sb + ",'name':'append','args':['a'],'async':'ref'}"));  // stored as sb+1
            c.send(j("{'op':'call','obj':" + (sb + 1) + ",'name':'append','args':['b'],'async':'void'}"));
            c.send(j("{'op':'new','class':'java.lang.Object','async':'void'}"));
            Map<String, Object> next = c.call(j("{'op':'new','class':'java.lang.Object'}"));
            check(objectId(next) == sb + 2, "the void calls stored nothing: " + next);
            check("ab".equals(c.value(objectId(c.call(j("{'op':'call','obj':" + sb + ",'name':'toString'}"))))), "both appends ran");
        }
    }

    private static void unref() throws Exception {
        try (BridgeServer s = plain().start(); Conn c = new Conn(s.getPort())) {
            long sb = objectId(c.call(j("{'op':'new','class':'java.lang.StringBuilder'}")));
            c.send(j("{'op':'unref','obj':" + sb + "}")); // no reply
            check(c.call(j("{'op':'new','class':'java.lang.Object'}")).containsKey("value"), "connection still usable");
            // Using a released handle is a client protocol error: the bridge says so and drops the connection.
            Map<String, Object> r = c.call(j("{'op':'call','obj':" + sb + ",'name':'toString'}"));
            check(String.valueOf(r.get("error")).contains("already been destroyed"), "error reply " + r);
            check(c.closed(), "connection closed");
            waitFor(() -> s.stats().errors() >= 1, "counted as a bridge error: " + s.stats());
        }
    }

    private static void protocolErrors() throws Exception {
        try (BridgeServer s = plain().start()) {
            for (String[] bad : new String[][] {
                    {"not json", "malformed JSON"},
                    {"[1,2]", "must be a JSON object"},
                    {"{'op':'fly'}", "unknown op"},
                    {"{'op':'call','obj':'x','name':'a'}", "must be an object id"},
                    {"{'op':'new','class':'java.lang.Object','async':'later'}", "\"async\" must be"}}) {
                try (Conn c = new Conn(s.getPort())) {
                    Map<String, Object> r = c.call(bad[0].startsWith("{") ? j(bad[0]) : bad[0]);
                    check(String.valueOf(r.get("error")).contains(bad[1]), bad[0] + " -> " + r);
                    check(c.closed(), "closed after " + bad[0]);
                }
            }
            try (Conn c = new Conn(s.getPort(), false)) {
                c.out.write(new byte[] {0x00, 0x10, 0x00, 0x00}); // 1 MB before hello
                c.out.flush();
                check(String.valueOf(c.read().get("error")).contains("message too large"), "pre-hello size limit");
            }
        }
    }

    private static void recycle() throws Exception {
        try (BridgeServer s = plain().start(); Conn c = new Conn(s.getPort())) {
            long first = objectId(c.call(j("{'op':'new','class':'java.lang.StringBuilder'}")));
            Map<String, Object> fin = c.call(j("{'op':'end','keep':true}"));
            check("A".equals(fin.get("end")), "keep-alive acknowledged: " + fin);
            c.begin(false); // the next PHP request on the same connection: no new hello
            long again = objectId(c.call(j("{'op':'new','class':'java.lang.StringBuilder'}")));
            check(again == first, "object ids start over after recycling: " + first + " vs " + again);
            check(s.stats().connectionsAccepted() == 1, "same connection reused");
            check("E".equals(c.call(j("{'op':'end','keep':false}")).get("end")) && c.closed(), "end without keep closes");
        }
    }

    private static void internalLibrariesIsolated() throws Exception {
        check(InternalLibraries.get().describe().startsWith("Jackson 3."), "loaded: " + InternalLibraries.get().describe());
        for (String name : new String[] {"tools.jackson.core.json.JsonFactory", "org.bouncycastle.cert.X509v3CertificateBuilder",
                                         "php.java.bridge.internal.InternalLibrariesImpl"}) {
            try {
                Util.classForName(name);
                throw new AssertionError("PHP's class lookup sees " + name);
            } catch (ClassNotFoundException expected) { /* ok */ }
        }
        try (BridgeServer s = plain().start(); Conn c = new Conn(s.getPort())) {
            Map<String, Object> r = c.call(j("{'op':'new','class':'tools.jackson.core.json.JsonFactory'}"));
            check(r.containsKey("exception"), "new Java('tools.jackson...') fails: " + r);
        }
    }

    private static void stats() throws Exception {
        try (BridgeServer s = plain().threads(4).start();
             Conn a = new Conn(s.getPort()); Conn b = new Conn(s.getPort())) {
            a.call(j("{'op':'new','class':'java.lang.Object'}"));
            b.call(j("{'op':'new','class':'java.lang.Object'}"));
            BridgeServer.Stats st = s.stats();
            check(st.busyThreads() == 2 && st.connectionsAccepted() == 2 && st.maxThreads() == 4, "two connected: " + st);
            a.close();
            b.close();
            waitFor(() -> s.stats().busyThreads() == 0, "threads released after disconnect: " + s.stats());
            check(s.stats().idleThreads() >= 1, "idle threads stay for reuse: " + s.stats());
        }
    }

    private static void threadLimit() throws Exception {
        try (BridgeServer s = plain().threads(1).start(); Conn a = new Conn(s.getPort())) {
            a.call(j("{'op':'new','class':'java.lang.Object'}"));
            try (Conn b = new Conn(s.getPort(), false)) {
                b.sock.setSoTimeout(700);
                b.send(j("{'op':'hello','v':2}"));
                try {
                    b.read();
                    throw new AssertionError("second connection should wait for a free thread");
                } catch (SocketTimeoutException expected) { /* ok */ }
                a.close();
                b.sock.setSoTimeout(5000);
                check(b.read().containsKey("hello"), "served once the first connection closed");
            }
        }
    }

    private static void stopClosesConnections() throws Exception {
        BridgeServer s = plain().start();
        try (Conn c = new Conn(s.getPort())) {
            c.call(j("{'op':'new','class':'java.lang.Object'}"));
            s.stop();
            c.sock.setSoTimeout(5000);
            check(c.closed(), "connection closed by stop");
        } finally {
            s.stop();
        }
    }

    // ---------------------------------------------------------------- security

    private static final TlsSupport TLS = InternalLibraries.get().tls();
    private static final Instant NOW = Instant.now();

    private static KeyMaterial cert(String cn, KeyMaterial issuer, TlsSupport.Usage usage) throws Exception {
        return TLS.createCertificate(cn, issuer, usage, usage == TlsSupport.Usage.SERVER ? List.of("localhost", "127.0.0.1") : List.of(),
                                     NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofDays(1)));
    }

    /** A demo PKI: root, server and client certificate. */
    private static final class Pki {
        final KeyMaterial root, server, client;
        Pki() throws Exception {
            root = cert("test root", null, TlsSupport.Usage.CA);
            server = cert("localhost", root, TlsSupport.Usage.SERVER);
            client = cert("php-client", root, TlsSupport.Usage.CLIENT);
        }
        TlsCredentials credentials() throws Exception {
            return TlsCredentials.of(server.privateKey(), server.chain(), List.of(root.certificate()));
        }
    }

    private static void tlsAnonymous() throws Exception {
        try (BridgeServer s = BridgeServer.builder().port(0).start()) {
            check(s.getSecurity().describe().contains("SHA-256"), "describe: " + s.getSecurity().describe());
            try (Conn c = Conn.tls(s.getPort(), clientContext(null, null))) {
                check(!c.hello.containsKey("challenge"), "no challenge " + c.hello);
                check(c.call(j("{'op':'new','class':'java.lang.Object'}")).containsKey("value"), "works over TLS");
                check(((SSLSocket) c.sock).getSession().getProtocol().startsWith("TLSv1."), "TLS");
            }
            long accepted = s.stats().connectionsAccepted();
            try (Socket probe = new Socket(InetAddress.getLoopbackAddress(), s.getPort())) {
                /* a TCP check of the port: connect, send nothing, close */
            }
            waitFor(() -> s.stats().connectionsAccepted() == accepted + 1 && s.stats().busyThreads() == 0, "probe served: " + s.stats());
            check(s.stats().errors() == 0, "a connection closed before the handshake is not an error: " + s.stats());
            try (Socket raw = new Socket(InetAddress.getLoopbackAddress(), s.getPort())) {
                raw.setSoTimeout(5000);
                Conn.frame(raw.getOutputStream(), j("{'op':'hello','v':2}"));
                int b;
                try {
                    b = raw.getInputStream().read();
                } catch (IOException reset) {
                    b = -1;
                }
                check(b == -1 || b == 0x15, "a plain client gets no hello (closed or a TLS alert): " + b);
            }
            waitFor(() -> s.stats().errors() >= 1, "refused connection is logged: " + s.stats());
        }
    }

    private static void tlsClientCertificate() throws Exception {
        Pki pki = new Pki();
        try (BridgeServer s = BridgeServer.builder().port(0).security(BridgeSecurity.tlsCertificates(pki.credentials())).start();
             Conn c = Conn.tls(s.getPort(), clientContext(pki.client, pki.root))) {
            check(!c.hello.containsKey("challenge"), "the TLS client certificate suffices " + c.hello);
            check(c.call(j("{'op':'new','class':'java.lang.Object'}")).containsKey("value"), "works");
        }
        Pki other = new Pki();
        try (BridgeServer s = BridgeServer.builder().port(0).security(BridgeSecurity.tlsCertificates(pki.credentials())).start()) {
            try (Conn c = Conn.tls(s.getPort(), clientContext(other.client, pki.root))) {
                throw new AssertionError("a certificate from another root got in: " + c.hello);
            } catch (IOException expected) { /* handshake refused */ }
        }
    }

    private static void tlsProof() throws Exception {
        Pki pki = new Pki();
        try (BridgeServer s = BridgeServer.builder().port(0).security(BridgeSecurity.tlsCertificates(pki.credentials())).start();
             Conn c = Conn.tls(s.getPort(), clientContext(null, pki.root))) {
            check(c.hello.get("challenge") instanceof String, "challenge without a TLS client certificate " + c.hello);
            Map<String, Object> early = c.call(j("{'op':'new','class':'java.lang.Object'}"));
            check(String.valueOf(early.get("error")).contains("expected \"auth\""), "no requests before auth " + early);
        }
        try (BridgeServer s = BridgeServer.builder().port(0).security(BridgeSecurity.tlsCertificates(pki.credentials())).start();
             Conn c = Conn.tls(s.getPort(), clientContext(null, pki.root))) {
            Map<String, Object> r = c.auth(Standalone.pem(pki.client.chain()), pki.client.privateKey());
            check("ok".equals(r.get("auth")), "proof accepted " + r);
            check(c.call(j("{'op':'new','class':'java.lang.Object'}")).containsKey("value"), "works after auth");
        }
    }

    private static void tlsRejectedProofs() throws Exception {
        Pki pki = new Pki();
        Pki other = new Pki();
        KeyMaterial expired = TLS.createCertificate("php-client", pki.root, TlsSupport.Usage.CLIENT, List.of(),
                                                    NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(2)));
        KeyMaterial server = pki.server; // right root, but a server certificate
        Object[][] cases = {
            {Standalone.pem(pki.client.chain()), other.client.privateKey(), "signature does not match"},
            {Standalone.pem(other.client.chain()), other.client.privateKey(), "not trusted"},
            {Standalone.pem(expired.chain()), expired.privateKey(), "not trusted"},
            {Standalone.pem(server.chain()), server.privateKey(), "not meant for TLS clients"},
            {"garbage", pki.client.privateKey(), "no certificate"},
        };
        try (BridgeServer s = BridgeServer.builder().port(0).security(BridgeSecurity.tlsCertificates(pki.credentials())).start()) {
            for (Object[] k : cases) {
                try (Conn c = Conn.tls(s.getPort(), clientContext(null, pki.root))) {
                    Map<String, Object> r = c.auth((String) k[0], (PrivateKey) k[1]);
                    check(String.valueOf(r.get("error")).contains((String) k[2]), k[2] + " -> " + r);
                    check(c.closed(), "closed after a rejected proof");
                }
            }
            // a proof for one connection does not work on another: it covers the challenge
            AtomicReference<String> sig = new AtomicReference<>();
            try (Conn c = Conn.tls(s.getPort(), clientContext(null, pki.root))) {
                sig.set(Base64.getEncoder().encodeToString(c.sign(pki.client.privateKey())));
            }
            try (Conn c = Conn.tls(s.getPort(), clientContext(null, pki.root))) {
                Map<String, Object> r = c.call("{\"op\":\"auth\",\"cert\":" + jsonString(Standalone.pem(pki.client.chain()))
                                               + ",\"sig\":\"" + sig.get() + "\"}");
                check(String.valueOf(r.get("error")).contains("signature does not match"), "replayed proof " + r);
            }
        }
    }

    private static void tlsAllowedClients() throws Exception {
        Pki pki = new Pki();
        KeyMaterial intruder = cert("someone-else", pki.root, TlsSupport.Usage.CLIENT);
        try (BridgeServer s = BridgeServer.builder().port(0)
                 .security(BridgeSecurity.tlsCertificates(pki.credentials(), "php-client")).start()) {
            try (Conn c = Conn.tls(s.getPort(), clientContext(null, pki.root))) {
                check("ok".equals(c.auth(Standalone.pem(pki.client.chain()), pki.client.privateKey()).get("auth")), "allowed name");
            }
            try (Conn c = Conn.tls(s.getPort(), clientContext(null, pki.root))) {
                Map<String, Object> r = c.auth(Standalone.pem(intruder.chain()), intruder.privateKey());
                check(String.valueOf(r.get("error")).contains("not one of the allowed clients"), "other name refused " + r);
            }
            try (Conn c = Conn.tls(s.getPort(), clientContext(intruder, pki.root))) {
                c.call(j("{'op':'new','class':'java.lang.Object'}"));
                throw new AssertionError("other name got in with a TLS client certificate");
            } catch (IOException expected) { /* refused after the handshake */ }
        }

        // A URI in the subject alternative names counts as a name: here an audience, not a host
        KeyMaterial audience = TLS.createCertificate("any-client", pki.root, TlsSupport.Usage.CLIENT,
                                                     List.of("urn:example:audience:bridge"), NOW.minus(Duration.ofHours(1)),
                                                     NOW.plus(Duration.ofDays(1)));
        check(TLS.subjectNames(audience.certificate()).equals(List.of("any-client", "urn:example:audience:bridge")),
              "names: " + TLS.subjectNames(audience.certificate()));
        try (BridgeServer s = BridgeServer.builder().port(0)
                 .security(BridgeSecurity.tlsCertificates(pki.credentials(), "urn:example:audience:bridge")).start()) {
            try (Conn c = Conn.tls(s.getPort(), clientContext(null, pki.root))) {
                check("ok".equals(c.auth(Standalone.pem(audience.chain()), audience.privateKey()).get("auth")), "allowed URI (proof)");
            }
            try (Conn c = Conn.tls(s.getPort(), clientContext(audience, pki.root))) {
                check(c.call(j("{'op':'new','class':'java.lang.Object'}")).containsKey("value"), "allowed URI (TLS client certificate)");
            }
            try (Conn c = Conn.tls(s.getPort(), clientContext(null, pki.root))) {
                Map<String, Object> r = c.auth(Standalone.pem(pki.client.chain()), pki.client.privateKey());
                check(String.valueOf(r.get("error")).contains("not one of the allowed clients"), "certificate without the URI refused " + r);
            }
        }
    }

    private static void tlsRotation() throws Exception {
        Pki pki = new Pki();
        KeyMaterial next = cert("localhost", pki.root, TlsSupport.Usage.SERVER);
        AtomicReference<TlsCredentials> current = new AtomicReference<>(pki.credentials());
        try (BridgeServer s = BridgeServer.builder().port(0).security(BridgeSecurity.tlsCertificates(current::get)).start()) {
            X509Certificate first, second;
            try (Conn c = Conn.tls(s.getPort(), clientContext(pki.client, pki.root))) {
                first = (X509Certificate) ((SSLSocket) c.sock).getSession().getPeerCertificates()[0];
            }
            current.set(TlsCredentials.of(next.privateKey(), next.chain(), List.of(pki.root.certificate())));
            try (Conn c = Conn.tls(s.getPort(), clientContext(pki.client, pki.root))) {
                second = (X509Certificate) ((SSLSocket) c.sock).getSession().getPeerCertificates()[0];
            }
            check(first.equals(pki.server.certificate()) && second.equals(next.certificate()), "new connections get the new certificate");
        }
    }

    private static void credentials() throws Exception {
        Path dir = Files.createTempDirectory("bridge-demo-certs");
        try {
            List<Path> files = Standalone.makeDemoCertificates(dir);
            check(files.size() == 5, "five files: " + files);
            TlsCredentials c = TlsCredentials.fromFiles(dir.resolve("server.pem"), dir.resolve("server-key.pem"), null,
                                                        dir.resolve("ca.pem"));
            check(TLS.subjectNames(c.chain().get(0)).equals(List.of("localhost", "localhost", "urn:php-java-bridge:demo:server")),
                  "server names: " + TLS.subjectNames(c.chain().get(0)));
            X509Certificate demoClient = TlsCredentials.certificates(Files.readString(dir.resolve("client.pem"))).get(0);
            check(TLS.subjectNames(demoClient).equals(List.of("php-client", "urn:php-java-bridge:demo:client")),
                  "client names: " + TLS.subjectNames(demoClient));
            String pem = Files.readString(dir.resolve("client.pem")) + Files.readString(dir.resolve("client-key.pem"));
            check(TLS.readPrivateKey(pem, null).getAlgorithm().equals("EC"), "key found after a certificate");
            try {
                TlsCredentials.fromFiles(dir.resolve("server.pem"), dir.resolve("client-key.pem"), null, dir.resolve("ca.pem"));
                throw new AssertionError("a mismatched key was accepted");
            } catch (java.security.GeneralSecurityException expected) {
                check(expected.getMessage().contains("does not belong"), expected.getMessage());
            }
            try {
                TlsCredentials.fromFiles(dir.resolve("server-key.pem"), dir.resolve("server-key.pem"), null, dir.resolve("ca.pem"));
                throw new AssertionError("a key file was accepted as a certificate");
            } catch (java.security.GeneralSecurityException expected) {
                check(expected.getMessage().contains("server-key.pem"), "names the file: " + expected.getMessage());
            }
        } finally {
            try (var list = Files.list(dir)) {
                for (Path p : list.toList()) Files.delete(p);
            }
            Files.delete(dir);
        }
    }

    private static void commandLine() throws Exception {
        Standalone.Options o = Standalone.parse(new String[0]);
        check(o.address.isLoopbackAddress() && o.port == 9267 && o.logLevel == 2 && o.logFile == null
              && "tls-anonymous".equals(o.security), "defaults");

        o = Standalone.parse(new String[] {"--listen", "127.0.0.1:9300", "--threads", "5", "--log-level", "3",
                                           "--log-file", "bridge.log", "--security", "plain"});
        check(o.port == 9300 && o.threads == 5 && o.logLevel == 3 && "bridge.log".equals(o.logFile)
              && "plain".equals(o.security), "options");

        o = Standalone.parse(new String[] {"--security", "tls-certificates", "--tls-cert", "s.pem", "--tls-key", "k.pem",
                                           "--tls-ca", "ca.pem", "--tls-allow-client", "a", "--tls-allow-client", "b"});
        check("s.pem".equals(o.tlsCert) && List.of("a", "b").equals(o.allowedClients), "tls options");

        o = Standalone.parse(new String[] {"--listen", "9301"});
        check(o.address.isLoopbackAddress() && o.port == 9301, "port only stays on loopback");

        o = Standalone.parse(new String[] {"--listen", "[::1]:9302"});
        check(o.address.isLoopbackAddress() && o.port == 9302, "IPv6 loopback");

        o = Standalone.parse(new String[] {"INET_LOCAL:9303", "4", "x.log"});
        check(o.address.isLoopbackAddress() && o.port == 9303 && o.logLevel == 4 && "x.log".equals(o.logFile), "upstream form");

        o = Standalone.parse(new String[] {"INET:9304"});
        check(o.address.isAnyLocalAddress() && o.port == 9304, "upstream INET listens on all interfaces");

        check(Standalone.parse(new String[] {"--version"}).version, "--version");
        check(Standalone.parse(new String[] {"--help"}).help, "--help");

        for (String[] bad : new String[][] {{"--bogus"}, {"--listen"}, {"--listen", "70000"}, {"--threads", "0"},
                                            {"--log-level", "9"}, {"--listen", "nope:x"}, {"--security", "none"},
                                            {"--security", "tls-certificates", "--tls-cert", "a"}, {"--tls-ca", "x"}}) {
            try {
                Standalone.parse(bad);
                throw new AssertionError("accepted " + String.join(" ", bad));
            } catch (IllegalArgumentException expected) { /* ok */ }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, String what) throws InterruptedException {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError(what);
            Thread.sleep(20);
        }
    }

    private static boolean canConnect(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** JSON with single quotes for readability: {'op':'new'} becomes {"op":"new"}; \' stays a quote. */
    private static String j(String s) {
        return s.replace("\\'", "\u0000").replace('\'', '"').replace("\u0000", "'");
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /** The object id of a reply whose value is an object reference. */
    private static long objectId(Map<String, Object> reply) {
        Object v = reply.get("value");
        if (!(v instanceof Map) || !(((Map<?, ?>) v).get("obj") instanceof Long)) throw new AssertionError("expected an object, got: " + reply);
        return (Long) ((Map<?, ?>) v).get("obj");
    }

    /** A TLS client context: optional client certificate, trusting the given root (or anything if null). */
    private static SSLContext clientContext(KeyMaterial clientKey, KeyMaterial root) throws Exception {
        KeyManager[] keys = null;
        if (clientKey != null) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("client", clientKey.privateKey(), "x".toCharArray(), clientKey.chain().toArray(new X509Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "x".toCharArray());
            keys = kmf.getKeyManagers();
        }
        TrustManager trust = new X509TrustManager() { // the tests check the server's identity elsewhere
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(keys, new TrustManager[] {trust}, null);
        return ctx;
    }

    /** A protocol connection, the way the PHP client talks. */
    private static final class Conn implements AutoCloseable {
        final Socket sock;
        final DataInputStream in;
        final OutputStream out;
        Map<String, Object> hello;

        Conn(int port) throws IOException {
            this(new Socket(InetAddress.getLoopbackAddress(), port), true);
        }

        Conn(int port, boolean hello) throws IOException {
            this(new Socket(InetAddress.getLoopbackAddress(), port), hello);
        }

        Conn(Socket sock, boolean hello) throws IOException {
            this.sock = sock;
            sock.setSoTimeout(10_000);
            in = new DataInputStream(sock.getInputStream());
            out = sock.getOutputStream();
            if (hello) this.hello = call(j("{'op':'hello','v':2}"));
        }

        static Conn tls(int port, SSLContext ctx) throws IOException {
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(InetAddress.getLoopbackAddress(), port);
            s.setSoTimeout(10_000);
            s.startHandshake();
            return new Conn(s, true);
        }

        static void frame(OutputStream out, String json) throws IOException {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            out.write(new byte[] {(byte) (b.length >>> 24), (byte) (b.length >>> 16), (byte) (b.length >>> 8), (byte) b.length});
            out.write(b);
            out.flush();
        }

        void send(String json) throws IOException {
            frame(out, json);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> read() throws IOException {
            int len = in.readInt();
            byte[] b = new byte[len];
            in.readFully(b);
            return (Map<String, Object>) InternalLibraries.get().json().parse(b, 0, len);
        }

        Map<String, Object> call(String json) throws IOException {
            send(json);
            return read();
        }

        void begin(boolean values) throws IOException {
            send(j("{'op':'begin','values':" + values + "}"));
        }

        /** getValues of an object, the way java_values() asks for it. */
        Map<String, Object> values(long id) throws IOException {
            return call(j("{'op':'call','obj':0,'name':'getValues','args':[{'obj':" + id + "}],'tagged':true}"));
        }

        Object value(long id) throws IOException {
            return values(id).get("value");
        }

        byte[] sign(PrivateKey key) throws Exception {
            byte[] challenge = Base64.getDecoder().decode((String) hello.get("challenge"));
            byte[] server = MessageDigest.getInstance("SHA-256")
                .digest(((SSLSocket) sock).getSession().getPeerCertificates()[0].getEncoded());
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(key);
            s.update("php-java-bridge-auth-v1\0".getBytes(StandardCharsets.US_ASCII));
            s.update(challenge);
            s.update(server);
            return s.sign();
        }

        Map<String, Object> auth(String certPem, PrivateKey key) throws Exception {
            return call("{\"op\":\"auth\",\"cert\":" + jsonString(certPem) + ",\"sig\":\""
                        + Base64.getEncoder().encodeToString(sign(key)) + "\"}");
        }

        /** True if the server has closed the connection. */
        boolean closed() {
            try {
                sock.setSoTimeout(5000);
                return in.read() == -1;
            } catch (IOException e) {
                return true; // reset: also closed
            }
        }

        public void close() throws IOException {
            sock.close();
        }
    }

    /** Keeps expected bridge noise (connections dropped by tests) out of the test output. */
    private static final class QuietLogger implements ILogger {
        public void printStackTrace(Throwable t) {}
        public void log(int level, String msg) {}
        public void warn(String msg) {}
    }
}
