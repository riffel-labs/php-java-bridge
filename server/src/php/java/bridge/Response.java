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


import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import php.java.bridge.spi.JsonOutput;

/**
 * This class is used to write the response to the front-end: one JSON message per request.
 * <p>
 * The writers decide what a result becomes (a value, an object reference, an array); the methods at the end
 * of this class write it. A value is first written in plain form, where JSON objects are PHP associative
 * arrays. If it turns out to need a tag (an object reference, a binary string, NaN or an infinity) after
 * plain output has started, the value is written again in tagged form. See docs/protocol.md.
 *
 * @author jostb
 *
 */
public final class Response {

    /**
     * A specialized writer which writes arrays as values.
     * Used by getValues().
     * @see JavaBridge#getValues(Object)
     */
    public static final int VALUES_WRITER = 1;

    /**
     * A specialized writer which casts the value.
     * Used by cast().
     * @see JavaBridge#cast(Object, Class)
     */
    public static final int COERCE_WRITER = 2;

    /** How much of a message the debug log shows. */
    private static final int LOG_PREVIEW = 1000;

    protected JavaBridge bridge;
    private final JsonOutput out;
    private boolean hasLastAsyncException, hasLastAsyncExceptionSet;

    // state of the reply being written
    private boolean open;          // the reply object has been started
    private boolean tagged;        // the value is written in tagged form
    private boolean valueStarted;  // the "value" member has been started
    private int depth;             // nesting inside the value
    private final ArrayDeque<Boolean> mapStack = new ArrayDeque<>();

    /** Thrown when a value written in plain form needs a tag; the value is then written again, tagged. */
    private static final class NeedsTags extends RuntimeException {
        private static final long serialVersionUID = 1L;
        NeedsTags() {
            super(null, null, false, false);
        }
    }
    private static final NeedsTags NEEDS_TAGS = new NeedsTags();

    static final String MSG = "FATAL: Undeclared java.lang.RuntimeException detected.";
    private static final class UndeclaredThrowableErrorMarker extends RuntimeException {
        private static final long serialVersionUID = -578332461418889089L;
	private Throwable e;
	public UndeclaredThrowableErrorMarker (Throwable e) {
	    this.e = e;
	}
	public String toString() {
	    return MSG + " " + String.valueOf(e);
	}
	public Throwable getCause () {
	    return this.e;
	}
	public String getMessage() {
	    return e.getMessage();
	}
	public String getLocalizedMessage() {
	    return e.getLocalizedMessage();
	}
	public StackTraceElement[] getStackTrace() {
	    return e.getStackTrace();
	}
    }

    protected final Object wrapUndeclared(Throwable o, boolean hasDeclaredExceptions) {
	if (hasDeclaredExceptions || bridge.options.preferValues()) return o;
	bridge.setLastAsyncException(o);
	bridge.warn(MSG + " " + o);
	return new UndeclaredThrowableErrorMarker(o);
    }

    protected abstract class DelegateWriter {
    	protected Class staticType;

    	public abstract boolean setResult(Object value);

	/**
	 * @param type - The result type
	 */
	public void setType(Class type) {
	    this.staticType = type;
	}
    }
    protected abstract class Writer extends DelegateWriter {
	protected boolean hasDeclaredExceptions;

	public boolean isAsync () {
	    return false;
	}
	public void setResultException(Throwable o, boolean hasDeclaredExceptions) {
	    this.hasDeclaredExceptions = hasDeclaredExceptions;
	    writeException(wrapUndeclared(o, hasDeclaredExceptions), hasDeclaredExceptions);
	}
	public void setResultObject(Object value) {
	    writeObject(value);
	}
	public void setResultClass(Class value) {
	    writeClass(value);
	}
	public void setResult(Object value, Class type, boolean hasDeclaredExceptions) {
	    this.hasDeclaredExceptions = hasDeclaredExceptions;
	    setType(type);
	    setResult(value);
	}
	public void setFinish(boolean keepAlive) {
	    writeFinish(keepAlive);
	}
	public void reset() {
	    writer = currentWriter;
	    resetReply();
	}
	/**
	 * Called at the end of each request: sends the reply.
	 * @throws IOException
	 */
	public void flush() throws IOException {
	    if (open) sendReply();
	    reset();
	}
    }
    protected abstract class WriterWithDelegate extends Writer {
	protected DelegateWriter delegate;

	public void setType(Class type) {
	    super.setType(type);
	    delegate.setType(type);
	}
    }
    protected class ArrayWriter extends DelegateWriter {
	public boolean setResult(Object value) {
		return false;
	}
    }
    protected class ArrayValuesWriter extends DelegateWriter {
	public boolean setResult(Object value) {
	    if (value.getClass().isArray()) {
		int length = Array.getLength(value);
		writeCompositeBegin_a();
		for (int i=0; i<length; i++) {
		    writer.setResult(Array.get(value, i));
		}
		writeCompositeEnd();
	    } else if (value instanceof java.util.Map) {
		Map ht = (Map) value;
		writeCompositeBegin_h();
		for (Iterator e = ht.entrySet().iterator(); e.hasNext(); ) {
		    Map.Entry entry = (Map.Entry)e.next();
		    Object key = entry.getKey();
		    if (key instanceof Number &&
			!(key instanceof Double || key instanceof Float)) {
			writePairBegin_n(((Number)key).longValue());
		    }
		    else {
			writePairBegin_s(String.valueOf(key));
		    }
		    writer.setResult(entry.getValue());
		}
		writeCompositeEnd();
	    } else if (value instanceof java.util.Collection) {
                Collection ht = (Collection) value;
                writeCompositeBegin_a();
                for (Iterator e = ht.iterator(); e.hasNext(); ) {
                    writer.setResult(e.next());
                }
                writeCompositeEnd();
	    }
	    else {
		return false;
	    }
	    return true;
        }
    }
    protected abstract class IncompleteClassicWriter extends WriterWithDelegate {

	public boolean setResult(Object value) {
	    if (value == null) {
		writeNull();
	    } else if (value instanceof byte[]) {
		writeString((byte[])value);
	    } else if (value instanceof java.lang.String) {
		writeString((String)value);
	    } else if (value instanceof PhpString) {
	        writeString(((PhpString)value).getBytes());
	    } else if (value instanceof java.lang.Number) {

		if (value instanceof java.lang.Integer ||
		    value instanceof java.lang.Short ||
		    value instanceof java.lang.Byte ||
		    value instanceof java.lang.Long) {
		    writeLong(((Number)value).longValue());
		} else {
		    /* Float, Double, BigDecimal, BigInteger, ... */
		    writeDouble(((Number)value).doubleValue());
		}

	    } else if (value instanceof java.lang.Boolean) {
		writeBoolean(((Boolean)value).booleanValue());

	    } else {
	        return false;
	    }
	    return true;
	}
  }
    protected abstract class IncompleteArrayValueWriter extends WriterWithDelegate {

	public boolean setResult(Object value) {
	    if (value == null) {
		writeNull();
	    } else if (value instanceof PhpString) {
	        writeString(((PhpString)value).getBytes());
	    } else if (value instanceof PhpExactNumber) {
	        writeLong(((PhpExactNumber)value).longValue());
	    } else {
	        return false;
	    }
	    return true;
	}
  }

    protected class ClassicWriter extends IncompleteClassicWriter {

	public boolean setResult(Object value) {
	    if(super.setResult(value)) return true;
	    if(!delegate.setResult(value))
		writeObject(value);
	    return true;
	}
    }
    protected class DefaultWriter extends WriterWithDelegate {

	public boolean setResult(Object value) {
	    if(staticType.isPrimitive()) {
   		if(staticType == Boolean.TYPE)
		    writeBoolean(((Boolean) value).booleanValue());
   		else if(staticType == Byte.TYPE || staticType == Short.TYPE || staticType == Integer.TYPE
   			|| staticType == Long.TYPE)
		    writeLong(((Number)value).longValue());
   		else if(staticType == Float.TYPE || staticType == Double.TYPE)
		    writeDouble(((Number)value).doubleValue());
   		else if(staticType == Character.TYPE)
		    writeString(Util.stringValueOf(value));
   		else if(staticType == java.lang.Void.TYPE)
   		    writeVoid();
  		else { Util.logFatal("Unknown type"); writeObject(value); }
	    } else if(value instanceof PhpString) {//  No need to check for Request.PhpNumber, this cannot happen.
		writeString(((PhpString)value).getBytes());
	    } else if(!delegate.setResult(value)) {
		writeObject(value);
	    }
	    return true;
        }
    }

    protected class ArrayValueWriter extends IncompleteArrayValueWriter {
        public void setResult(Object value, Class type, boolean hasDeclaredExceptions) {
	    if(!delegate.setResult(value)) setResultArray(value);
	}
	public boolean setResult(Object value) {
	    if(!super.setResult(value)) writeObject(value);
	    return true;
	}
	private boolean setResultArray(Object value) {
	    writeCompositeBegin_a();
	    setResult(value);
	    writeCompositeEnd();
	    return true;
	}
    }
    /** Writer used by the async protocol. It always returns a void or an object reference, even for NULL and
     * Class values, although Exceptions are handled. When the client-side cache is enabled, the client will
     * send the next call with the same signature without waiting for its reply. */
    protected class DefaultObjectWriter extends Writer {
	public void setResultObject(Object value) {
	    if(staticType == java.lang.Void.TYPE)  { writeVoid(hasDeclaredExceptions); return; }

	    if(value==null) value=Request.PHPNULL;
 	    writeObject(value, hasDeclaredExceptions);
	}
	public void setResultClass(Class value) {
	    if(value==null) value=Request.PhpNull.class; // shouldn't happen
	    writeClass(value, hasDeclaredExceptions);
	}
	public boolean setResult(Object value) {
	    setResultObject(value);
	    return true;
	}
    }
    /** Writer used by the async protocol. It always returns a void or an object reference, even for NULL,
     * Class or Exception values. */
    protected final class ObjectWriter extends DefaultObjectWriter {
	public void setResultException(Throwable o, boolean hasDeclaredExceptions) {
	    this.hasDeclaredExceptions = hasDeclaredExceptions;
	    setResultObject(wrapUndeclared(o, hasDeclaredExceptions));
	}
    }
    /** Writer used by the async protocol ("async": "ref"). It writes nothing but stores the result under the
     * next object id. */
    protected final class AsyncWriter extends Writer {
	public boolean isAsync () {
	    return true;
	}
	public void setResultException(Throwable o, boolean hasDeclaredExceptions) {
	    this.hasDeclaredExceptions = hasDeclaredExceptions;
	    setResultObject(wrapUndeclared(o, hasDeclaredExceptions));
	}
	public void setResultObject(Object value) {
	    if(staticType == java.lang.Void.TYPE)  throw new IllegalStateException ("Use the AsyncVoidWriter instead");

 	    if(value==null) value=Request.PHPNULL;
	    int id = bridge.globalRef.append(value);
	    if(bridge.logLevel>=4) bridge.logDebug(" |<- (no reply) stored as object " + id);
	}
	public void setResultClass(Class value) {
	    if(value==null) value=Request.PhpNull.class;
	    int id = bridge.globalRef.append(value);
	    if(bridge.logLevel>=4) bridge.logDebug(" |<- (no reply) stored as object " + id);
	}
	public boolean setResult(Object value) {
	    setResultObject(value);
	    return true;
	}
	public void flush() throws IOException {
     	    reset();
	}
    }
    /** Writer used by the async protocol ("async": "void"). It writes nothing and doesn't create a result. */
    protected final class AsyncVoidWriter extends Writer {
	public boolean isAsync () {
	    return true;
	}
	public void setResultException(Throwable o, boolean hasDeclaredExceptions) {
	    this.hasDeclaredExceptions = hasDeclaredExceptions;
	    wrapUndeclared(o, hasDeclaredExceptions);
	}
	public void setResultObject(Object value) {}
	public void setResultClass(Class value) {}
	public boolean setResult(Object value) {
	    return true;
	}
	public void flush() throws IOException {
     	    if(bridge.logLevel>=4) {
       	     bridge.logDebug(" |<- (no reply, no result)");
            }
     	    reset();
	}
    }

    protected final class CoerceWriter extends Writer {
	public void setResult(Object value, Class resultType, boolean hasDeclaredExceptions) {
	    // ignore resultType and use the coerce type
	    setResult(value);
	}

	public boolean setResult(Object value) {
		 if(value instanceof PhpString)
		     value = ((PhpString)value).getString();

		 if(staticType.isPrimitive()) {
		if(staticType == Boolean.TYPE) {
		    if(value instanceof Boolean)
		        writeBoolean(((Boolean) value).booleanValue());
		    else {
			if (value == null) {
			    writeBoolean(false);
			} else if (value instanceof byte[]) {
			    writeBoolean(((byte[])value).length != 0);
			} else if (value instanceof java.lang.String) {
			    writeBoolean(((String)value).length() != 0);
			} else if (value instanceof PhpString) {
			    writeBoolean(((PhpString)value).getBytes().length != 0);
			} else if (value instanceof java.lang.Number) {
			    if (value instanceof java.lang.Integer ||
				value instanceof java.lang.Short ||
				value instanceof java.lang.Byte) {
				writeBoolean(((Number)value).longValue() != 0);
			    } else {
				/* Float, Double, BigDecimal, BigInteger, Double, Long, ... */
				writeBoolean(((Number)value).doubleValue() != 0.0);
			    }
			} else {
			    writeBoolean(true);
			}
		    }
		} else if(staticType == Byte.TYPE || staticType == Short.TYPE || staticType == Integer.TYPE || staticType == Long.TYPE) {
		    if(value instanceof Number)
		        writeLong(((Number)value).longValue());
		    else {
		        try { writeLong(Long.valueOf(Util.stringValueOf(value)).longValue()); } catch (NumberFormatException n) { writeLong(0); }
		    }
		} else if(staticType == Float.TYPE || staticType == Double.TYPE) {
		    if(value instanceof Number)
		        writeDouble(((Number)value).doubleValue());
		    else {
		        try { writeDouble(Double.valueOf(Util.stringValueOf(value)).doubleValue()); } catch (NumberFormatException n) { writeDouble(0.0); }
		    }
		} else if(staticType == Character.TYPE) {
		    writeString(Util.stringValueOf(value));
		} else if(staticType == java.lang.Void.TYPE) {
   		    writeVoid();
		} else { Util.logFatal("Unknown type"); writeObject(value); }
	    } else if(staticType == String.class) {
		 if (value instanceof byte[])
		     writeString((byte[])value);
		 else
		     writeString(Util.stringValueOf(value));
	    } else {
		writeObject(value);
	    }
	    return true;
	}
    }
    private DelegateWriter getDefaultDelegate() {
	return new ArrayWriter();
    }

    private Writer getDefaultWriter() {
	if(bridge.options.preferValues()) {
	    WriterWithDelegate writer;
	    writer = new DefaultWriter();
	    writer.delegate = getDefaultDelegate();
	    return writer;
	}
	return getDefaultObjectWriter();
    }
    private Writer defaultWriter;
    private Writer writer, currentWriter, arrayValuesWriter=null, arrayValueWriter=null, coerceWriter=null;
    private Writer asyncWriter=null, asyncVoidWriter=null, objectWriter = null, valuesDefaultWriter = null;

    /**
     * Creates a new response object. The object is re-used for each request of the connection.
     * @param bridge The bridge.
     */
    public Response(JavaBridge bridge) {
	this.bridge=bridge;
	this.out = InternalLibraries.get().json().newOutput();
	currentWriter = writer = defaultWriter = getDefaultWriter();
    }

    /**
     * Select the default writer again after the client changed its options ("begin" message).
     */
    void optionsChanged() {
	if (bridge.options.preferValues()) {
	    if (valuesDefaultWriter == null) valuesDefaultWriter = getDefaultWriter();
	    defaultWriter = valuesDefaultWriter;
	} else {
	    defaultWriter = getDefaultObjectWriter();
	}
	currentWriter = writer = defaultWriter;
    }

    /**
     * Run a writer call: first in plain form, again in tagged form if the value needs tags. If writing fails,
     * the partial reply is discarded so that the caller can write an exception instead.
     */
    private void write(Runnable call) {
	try {
	    call.run();
	} catch (NeedsTags e) {
	    resetReply();
	    tagged = true;
	    try {
		call.run();
	    } catch (RuntimeException | Error x) {
		resetReply();
		throw x;
	    }
	} catch (RuntimeException | Error e) {
	    resetReply();
	    throw e;
	}
    }

    /**
      * Set the result packet.
      * @param value The throwable
      * @param hasDeclaredExceptions true if the method has declared
      * to throw exception(s), false otherwise
      */
    public void setResultException(Throwable value, boolean hasDeclaredExceptions) {
	resetReply();
     	write(() -> writer.setResultException(value, hasDeclaredExceptions));
    }

    /**
      * Set the result packet.
      * @param value The result object.
      */
    public void setResultClass(Class value) {
     	write(() -> writer.setResultClass(value));
    }

    /**
      * Set the result packet.
      * @param value The result object.
      * @param type The type of the result object.
     * @param hasDeclaredExceptions true if the method/procedure has declared exceptions, false otherwise
      */
    public void setResult(Object value, Class type, boolean hasDeclaredExceptions) {
     	write(() -> writer.setResult(value, type, hasDeclaredExceptions));
    }

    /**
     * Checks whether the asynchronous protocol is used
     * @return true if the current writer is an AsyncWriter, false otherwise
     */
    public boolean isAsync () {
	return writer.isAsync();
    }

    protected void setFinish(boolean keepAlive) {
	if (!hasLastAsyncExceptionSet) hasLastAsyncException = bridge.lastAsyncException != null;
	setDefaultWriter();
        writer.setFinish(keepAlive);
	hasLastAsyncException = hasLastAsyncExceptionSet = false;
    }
    /**
     * Selects a specialized writer which writes objects as an array.
     * Used by castToArray().
     * @see JavaBridge#castToArray(Object)
     */
    protected Writer setArrayValueWriter() {
	return writer = getArrayValueWriter();
    }
    Writer setArrayValuesWriter() {
	return writer = getArrayValuesWriter();
    }
    Writer setCoerceWriter() {
	return writer = getCoerceWriter();
    }
    /**
     * Selects a specialized writer which does not write anything.
     * Used by async. protocol.
     * @return The async. writer
     */
    public Writer setAsyncWriter() {
	return writer = getAsyncWriter();
    }
    /**
     * Selects a specialized writer which does not write anything and does not generate a result proxy
     * Used by async. protocol.
     * @return The async. writer
     */
    public Writer setAsyncVoidWriter() {
	return writer = getAsyncVoidWriter();
    }
    /**
     * Selects a specialized writer which always writes a void or an object reference, even for NULL, Class
     * and Exception values.
     * @return The object writer
     */
    public Writer setObjectWriter() {
	return currentWriter = getObjectWriter();
    }
    /**
     * Selects the default writer
     * @return The default writer
     */
    public Writer setDefaultWriter() {
	return writer = currentWriter = defaultWriter;
    }

    // ---------------------------------------------------------------- JSON output

    private void resetReply() {
	out.reset();
	open = false;
	tagged = false;
	valueStarted = false;
	depth = 0;
	mapStack.clear();
    }

    private void beginReply() {
	if (!open) {
	    out.reset();
	    out.startObject();
	    open = true;
	}
    }

    /** Start a value: at the top level, the "value" member of the reply. */
    private void startValue() {
	if (depth == 0) {
	    beginReply();
	    out.name("value");
	    valueStarted = true;
	}
    }

    /** The value about to be written needs a tag: switch to tagged form, or start over in it. */
    private void requireTags() {
	if (tagged) return;
	if (!valueStarted) {
	    tagged = true;
	    return;
	}
	throw NEEDS_TAGS;
    }

    /** Complete the reply and send it as one frame. */
    private void sendReply() throws IOException {
	if (tagged && valueStarted) {
	    out.name("tagged");
	    out.value(true);
	}
	out.endObject();
	sendFrame();
    }

    private void sendFrame() throws IOException {
	int n = out.finish();
	byte[] b = out.buffer();
	if (bridge.logLevel >= 4) {
	    bridge.logDebug(" <-- " + new String(b, 4, Math.min(n - 4, LOG_PREVIEW), StandardCharsets.UTF_8)
			    + (n - 4 > LOG_PREVIEW ? "..." : ""));
	}
	bridge.out.write(b, 0, n);
	bridge.out.flush();
	resetReply();
    }

    /**
     * Answer the client's "hello".
     * @param challenge the challenge the client must sign, or null if it need not prove its identity
     * @throws IOException if the reply cannot be sent
     */
    void writeHello(byte[] challenge) throws IOException {
	resetReply();
	beginReply();
	out.name("hello");
	out.value("php-java-bridge");
	out.name("v");
	out.value(Request.PROTOCOL_VERSION);
	out.name("server");
	out.value(Util.VERSION);
	if (challenge != null) {
	    out.name("challenge");
	    out.value(Base64.getEncoder().encodeToString(challenge));
	}
	out.endObject();
	sendFrame();
    }

    /**
     * Confirm that the client's proof was accepted.
     * @throws IOException if the reply cannot be sent
     */
    void writeAuthOk() throws IOException {
	resetReply();
	beginReply();
	out.name("auth");
	out.value("ok");
	out.endObject();
	sendFrame();
    }

    /**
     * Tell the client why the connection is closed.
     * @param message the reason
     * @throws IOException if the reply cannot be sent
     */
    void writeError(String message) throws IOException {
	resetReply();
	beginReply();
	out.name("error");
	out.value(message);
	out.endObject();
	sendFrame();
    }

    void writeString(byte s[]) {
	if (Util.isValidUtf8(s)) {
	    startValue();
	    out.utf8Value(s, 0, s.length);
	} else {
	    requireTags();
	    startValue();
	    out.startObject();
	    out.name("bytes");
	    out.value(Base64.getEncoder().encodeToString(s));
	    out.endObject();
	}
    }
    void writeString(String s) {
	// via UTF-8 bytes: an unpaired surrogate becomes '?' instead of failing
	byte[] b = s.getBytes(StandardCharsets.UTF_8);
	startValue();
	out.utf8Value(b, 0, b.length);
    }
    void writeBoolean(boolean b) {
	startValue();
	out.value(b);
    }
    void writeLong(long l) {
	startValue();
	out.value(l);
    }
    void writeDouble(double d) {
	if (Double.isNaN(d) || Double.isInfinite(d)) {
	    requireTags();
	    startValue();
	    out.startObject();
	    out.name("float");
	    out.value(Double.isNaN(d) ? "NaN" : d > 0 ? "INF" : "-INF");
	    out.endObject();
	} else {
	    startValue();
	    out.value(d);
	}
    }
    void writeVoid(boolean hasDeclaredExceptions) {
	if (depth > 0) { writeNull(); return; }
	beginReply();
	out.name("void");
	out.value(true);
	if (hasDeclaredExceptions) {
	    out.name("throws");
	    out.value(true);
	}
    }
    void writeVoid() {
	writeVoid(true);
    }
    void writeNull() {
	startValue();
	out.nullValue();
    }
    private static String kind(Class type) {
        if (type.isArray() ||
        List.class.isAssignableFrom(type) ||
        Map.class.isAssignableFrom(type)) {
            return "A";
        } else if(Collection.class.isAssignableFrom(type)) {
            return "C";
        } else if(Throwable.class.isAssignableFrom(type)) {
            return "E";
        }
        return "O";
    }
    /** An object reference; "throws" is only meaningful for the top-level result. */
    private void writeReference(Object o, String className, String kind, boolean hasDeclaredExceptions) {
	requireTags();
	startValue();
	int id = bridge.globalRef.append(o);
	out.startObject();
	out.name("obj");
	out.value(id);
	out.name("class");
	out.value(className);
	if (!"O".equals(kind)) {
	    out.name("kind");
	    out.value(kind);
	}
	if (hasDeclaredExceptions && depth == 0) {
	    out.name("throws");
	    out.value(true);
	}
	out.endObject();
    }
    /** write object (class, interface, object) */
    void writeObject(Object o, boolean hasDeclaredExceptions) {
        if(o==null) { writeNull(); return; }
        Class dynamicType = o.getClass();
        writeReference(o, dynamicType.getName(), kind(dynamicType), hasDeclaredExceptions);
    }
    void writeObject(Object o) {
	writeObject(o, true);
    }
    /** write object of type class. Only called from new JavaClass(...) */
    void writeClass(Class o, boolean hasDeclaredExceptions) {
        if(o==null) { writeNull(); return; }
        writeReference(o, o.getName(), "O", hasDeclaredExceptions);
    }
    void writeClass(Class o) {
	writeClass(o, true);
    }
    void writeException(Object o, boolean hasDeclaredExceptions) {
	resetReply();
	beginReply();
	out.name("exception");
	out.value(bridge.globalRef.append(o));
	if (hasDeclaredExceptions) {
	    out.name("throws");
	    out.value(true);
	}
    }
    void writeFinish(boolean keepAlive) {
	beginReply();
	out.name("end");
	if (hasLastAsyncException) {
	    out.value(keepAlive ? "a" : "e");
	} else {
	    out.value(keepAlive ? "A" : "E");
	}
    }
    void writeCompositeBegin_a() {
	startValue();
	out.startArray();
	mapStack.push(Boolean.FALSE);
	depth++;
    }
    void writeCompositeBegin_h() {
	startValue();
	if (tagged) {
	    out.startObject();
	    out.name("map");
	}
	out.startObject();
	mapStack.push(Boolean.TRUE);
	depth++;
    }
    void writeCompositeEnd() {
	depth--;
	if (mapStack.pop()) {
	    out.endObject();
	    if (tagged) out.endObject();
	} else {
	    out.endArray();
	}
    }
    void writePairBegin_s(String key) {
	out.name(key);
    }
    void writePairBegin_n(long key) {
	out.name(Long.toString(key));
    }

    /**
     * Send the reply of the current request, if any.
     * @throws IOException
     */
    public void flush() throws IOException {
      	writer.flush();
    }

    /**
     * Called at the end of each packed.
     */
    protected void reset() {
	writer.reset();
    }
    /** Set the current bridge object
     * @param bridge The bridge
     */
    protected void setBridge(JavaBridge bridge) {
	this.bridge = bridge;
    }
    /** re-initialize for keep alive */
    protected void recycle() {
	hasLastAsyncException = bridge.lastAsyncException != null;
	hasLastAsyncExceptionSet = true;

        reset();
        setDefaultWriter();
    }

    private Writer getArrayValuesWriter() {
        if(arrayValuesWriter==null) {
            WriterWithDelegate writer = new ClassicWriter();
            writer.delegate = new ArrayValuesWriter();
            arrayValuesWriter = writer;
        }
        return arrayValuesWriter;
    }

    private Writer getArrayValueWriter() {
        if(arrayValueWriter==null) {
            WriterWithDelegate writer = new ArrayValueWriter();
            writer.delegate = new ArrayValuesWriter();
            arrayValueWriter = writer;
        }
        return arrayValueWriter;
    }

    private Writer getCoerceWriter() {
        if(coerceWriter==null) return coerceWriter = new CoerceWriter();
        return coerceWriter;
    }
    private Writer getDefaultObjectWriter() {
	if(objectWriter==null) return objectWriter = new DefaultObjectWriter();
	return objectWriter;
    }
    private Writer getObjectWriter() {
	if(objectWriter==null) return objectWriter = new ObjectWriter();
	return objectWriter;
    }
    private Writer getAsyncWriter() {
	if(asyncWriter==null) return asyncWriter = new AsyncWriter();
	return asyncWriter;
    }
    private Writer getAsyncVoidWriter() {
	if(asyncVoidWriter==null) return asyncVoidWriter = new AsyncVoidWriter();
	return asyncVoidWriter;
    }
}
