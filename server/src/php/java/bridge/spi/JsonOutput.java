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

package php.java.bridge.spi;

/**
 * Writes one JSON document at a time into a reusable buffer, leaving room for a 4-byte length in front of it,
 * the framing of the wire protocol. Not thread-safe.
 * <p>
 * Usage: {@link #reset()}, write one value, then {@link #finish()} and send {@code buffer()[0 .. finish())}.
 * Write errors (for example nesting deeper than {@link JsonCodec#MAX_DEPTH}) are unchecked exceptions.
 */
public interface JsonOutput {

    /** Discard what was written and start a new document. */
    void reset();

    /** Start an object. */
    void startObject();

    /** End the current object. */
    void endObject();

    /**
     * Write a property name inside an object.
     * @param name the name
     */
    void name(String name);

    /** Start an array. */
    void startArray();

    /** End the current array. */
    void endArray();

    /** Write {@code null}. */
    void nullValue();

    /** @param value a boolean */
    void value(boolean value);

    /** @param value an integer */
    void value(long value);

    /** @param value a finite number; NaN and the infinities have no JSON form */
    void value(double value);

    /** @param value a string */
    void value(String value);

    /**
     * Write a string given as valid UTF-8 bytes.
     * @param utf8 the bytes
     * @param offset where the string starts
     * @param length its length in bytes
     */
    void utf8Value(byte[] utf8, int offset, int length);

    /**
     * Complete the document and store its length, as a 4-byte big-endian integer, in the first four bytes
     * of the buffer.
     * @return the number of bytes to send: 4 plus the length of the document
     */
    int finish();

    /** @return the buffer; valid until the next write or {@link #reset()} */
    byte[] buffer();
}
