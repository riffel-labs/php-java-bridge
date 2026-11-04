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

import java.io.IOException;

/**
 * Reads and writes JSON. Syntax only: what the messages mean is up to the bridge.
 */
public interface JsonCodec {

    /** Deepest nesting of arrays and objects accepted in either direction. */
    int MAX_DEPTH = 10_000;

    /**
     * Parse one JSON document.
     * @param data the bytes, UTF-8
     * @param offset where the document starts
     * @param length its length in bytes
     * @return the value: objects become {@code LinkedHashMap<String, Object>} (in document order), arrays
     *         {@code ArrayList<Object>}, integers {@code Long} ({@code BigInteger} if they do not fit), other
     *         numbers {@code Double}, plus {@code String}, {@code Boolean} and {@code null}
     * @throws IOException if the bytes are not one well-formed JSON document
     */
    Object parse(byte[] data, int offset, int length) throws IOException;

    /** @return a new writer; each connection uses its own */
    JsonOutput newOutput();
}
