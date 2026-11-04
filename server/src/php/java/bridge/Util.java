/*-*- mode: Java; tab-width:8 -*-*/

package php.java.bridge;

/*
 * Copyright (C) 2003-2007 Jost Boekemeier
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

import java.nio.charset.StandardCharsets;

/**
 * Shared constants, logging and message formatting for the bridge.
 */
public final class Util {

    private Util() {}

    /** The bridge version, taken from the jar manifest ("unknown" when running from classes). */
    public static final String VERSION = version();

    /** The name the bridge uses for itself in log lines. */
    public static final String EXTENSION_NAME = "JavaBridge";

    /** The Java version and vendor, for example "21.0.5@https://adoptium.net/". Part of every exception message sent to PHP. */
    public static final String VM_NAME = System.getProperty("java.version") + "@" + System.getProperty("java.vendor.url");

    /** Only for internal use */
    public static final byte HEX_DIGITS[] = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    /** ASCII encoding */
    public static final String ASCII = "ASCII";

    /** UTF-8 encoding */
    public static final String UTF8 = "UTF-8";

    /** The default encoding for strings exchanged with PHP. */
    public static final String DEFAULT_ENCODING = UTF8;

    /** Backlog for the listening socket. */
    public static final int BACKLOG = 20;

    /** Only for internal use */
    public static final Class[] ZERO_PARAM = new Class[0];

    /** The default log level: errors and exceptions. */
    public static final int DEFAULT_LOG_LEVEL = 2;

    /**
     * The log level:<br>
     * 0: log off <br>
     * 1: log fatal <br>
     * 2: log messages/exceptions <br>
     * 3: log verbose <br>
     * 4: log debug <br>
     * 5: log method invocations
     */
    public static volatile int logLevel = DEFAULT_LOG_LEVEL;

    private static volatile ILogger defaultLogger = new FileLogger(System.err);

    private static String version() {
        Package p = Util.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v == null ? "unknown" : v;
    }

    /**
     * Set the logger the bridge writes to. The default writes to System.err.
     * @param logger the logger
     */
    public static void setDefaultLogger(ILogger logger) {
        if (logger == null) throw new NullPointerException("logger");
        defaultLogger = logger;
    }

    /**
     * @return the logger the bridge writes to.
     */
    public static ILogger getLogger() {
        return defaultLogger;
    }

    /**
     * Print a message on a given log level
     * @param level The log level
     * @param msg The message
     */
    public static void println(int level, String msg) {
        getLogger().log(level, msg);
    }

    /**
     * Display a warning if logLevel &gt;= 1
     * @param msg The warn message
     */
    public static void warn(String msg) {
        if (logLevel <= 0) return;
        getLogger().warn(msg);
    }

    /**
     * Log a stack trace
     * @param t The Throwable
     */
    public static void printStackTrace(Throwable t) {
        getLogger().printStackTrace(t);
    }

    /**
     * Display a debug message
     * @param msg The message
     */
    public static void logDebug(String msg) {
        if (logLevel > 3) println(4, msg);
    }

    /**
     * Display a fatal error
     * @param msg The error
     */
    public static void logFatal(String msg) {
        if (logLevel > 0) println(1, msg);
    }

    /**
     * Display an error or an exception
     * @param msg The error or the exception
     */
    public static void logError(String msg) {
        if (logLevel > 1) println(2, msg);
    }

    /**
     * Display a message
     * @param msg The message
     */
    public static void logMessage(String msg) {
        if (logLevel > 2) println(3, msg);
    }

    /**
     * Return the class name
     * @param obj The object
     * @return The class name
     */
    public static String getClassName(Object obj) {
        if (obj == null) return "null";
        Class c = getClass(obj);
        String name = c.getName();
        if (name.startsWith("[")) name = "array_of_" + name.substring(1);
        return name;
    }

    /**
     * Return the short class name
     * @param obj The object
     * @return The class name
     */
    public static String getShortClassName(Object obj) {
        String name = getClassName(obj);
        int idx = name.lastIndexOf('.');
        if (idx != -1) name = name.substring(idx + 1);
        return name;
    }

    /**
     * Return the class or the object, if obj is already a class.
     * @param obj The object
     * @return Either obj or the class of obj.
     */
    public static Class getClass(Object obj) {
        if (obj == null) return null;
        return obj instanceof Class ? (Class) obj : obj.getClass();
    }

    /**
     * Append an object to a StringBuffer
     * @param obj The object
     * @param buf The StringBuffer
     */
    public static void appendObject(Object obj, StringBuffer buf) {
        if (obj == null) { buf.append("null"); return; }

        if (obj instanceof Class) {
            if (((Class) obj).isInterface())
                buf.append("[i:");
            else
                buf.append("[c:");
        } else {
            buf.append("[o:");
        }
        buf.append(getShortClassName(obj));
        buf.append("]:");
        buf.append("\"");
        buf.append(Util.stringValueOf(obj));
        buf.append("\"");
    }

    /**
     * Append a stack trace to buf.
     * @param throwable The throwable object
     * @param trace The trace from PHP
     * @param buf The current buffer.
     */
    public static void appendTrace(Throwable throwable, String trace, StringBuffer buf) {
        buf.append(" at:\n");
        StackTraceElement stack[] = throwable.getStackTrace();
        int top = stack.length;
        for (int i = 0; i < top; i++) {
            buf.append("#-");
            buf.append(top - i);
            buf.append(" ");
            buf.append(stack[i].toString());
            buf.append("\n");
        }
        buf.append(trace);
    }

    /**
     * Append a parameter object to a StringBuffer
     * @param obj The object
     * @param buf The StringBuffer
     */
    public static void appendShortObject(Object obj, StringBuffer buf) {
        if (obj == null) { buf.append("null"); return; }

        if (obj instanceof Class) {
            if (((Class) obj).isInterface())
                buf.append("[i:");
            else
                buf.append("[c:");
        } else {
            buf.append("[o:");
        }
        buf.append(getShortClassName(obj));
        buf.append("]");
    }

    /**
     * Append a function parameter to a StringBuffer
     * @param c The parameter
     * @param buf The StringBuffer
     */
    public static void appendParam(Class c, StringBuffer buf) {
        if (c.isInterface())
            buf.append("(i:");
        else if (c == java.lang.Class.class)
            buf.append("(c:");
        else
            buf.append("(o:");
        buf.append(getShortClassName(c));
        buf.append(")");
    }

    /**
     * Append a function parameter to a StringBuffer
     * @param obj The parameter object
     * @param buf The StringBuffer
     */
    public static void appendParam(Object obj, StringBuffer buf) {
        if (obj instanceof Class) {
            Class c = (Class) obj;
            if (c.isInterface())
                buf.append("(i:");
            else
                buf.append("(c:");
        } else
            buf.append("(o:");
        buf.append(getShortClassName(obj));
        buf.append(")");
    }

    /**
     * Return function arguments and their types as a String
     * @param args The args
     * @param params The associated types
     * @return A new string
     */
    public static String argsToString(Object args[], Class[] params) {
        StringBuffer buffer = new StringBuffer("");
        appendArgs(args, params, buffer);
        return buffer.toString();
    }

    /**
     * Append function arguments and their types to a StringBuffer
     * @param args The args
     * @param params The associated types
     * @param buf The StringBuffer
     */
    public static void appendArgs(Object args[], Class[] params, StringBuffer buf) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (params != null) {
                    appendParam(params[i], buf);
                }
                appendShortObject(args[i], buf);

                if (i + 1 < args.length) buf.append(", ");
            }
        }
    }

    /**
     * Locale-independent getBytes(), uses ASCII encoding
     * @param s The String
     * @return The ASCII encoded bytes
     */
    public static byte[] toBytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * String.valueOf() that never returns null, even for a dynamic proxy whose toString() returns null.
     *
     * @param object The object or dynamic proxy
     * @return The string representation of object
     */
    public static String stringValueOf(Object object) {
        String s = String.valueOf(object);
        if (s == null) s = String.valueOf(s);
        return s;
    }

    /**
     * Return the thread context class loader
     * @return The context class loader
     */
    public static ClassLoader getContextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = JavaBridge.class.getClassLoader();
        return loader;
    }

    /**
     * Load a class through the thread context class loader.
     * @param name The class name
     * @return The class
     * @throws ClassNotFoundException if the class cannot be found
     */
    public static Class classForName(String name) throws ClassNotFoundException {
        return Class.forName(name, true, getContextClassLoader());
    }

    /**
     * Check whether bytes are well-formed UTF-8 (no overlong forms, no surrogates, nothing above U+10FFFF),
     * the same rule PHP's json_encode applies.
     * @param b the bytes
     * @return true if b is valid UTF-8
     */
    static boolean isValidUtf8(byte[] b) {
        int i = 0, n = b.length;
        while (i < n) {
            int c = b[i] & 0xFF;
            if (c < 0x80) { i++; continue; }
            int len;
            if (c >= 0xC2 && c <= 0xDF) len = 2;
            else if (c >= 0xE0 && c <= 0xEF) len = 3;
            else if (c >= 0xF0 && c <= 0xF4) len = 4;
            else return false;
            if (i + len > n) return false;
            int c1 = b[i + 1] & 0xFF;
            if ((c1 & 0xC0) != 0x80) return false;
            if (c == 0xE0 && c1 < 0xA0) return false; // overlong
            if (c == 0xED && c1 > 0x9F) return false; // surrogates
            if (c == 0xF0 && c1 < 0x90) return false; // overlong
            if (c == 0xF4 && c1 > 0x8F) return false; // above U+10FFFF
            for (int k = 2; k < len; k++) {
                if ((b[i + k] & 0xC0) != 0x80) return false;
            }
            i += len;
        }
        return true;
    }
}
