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
 * The bridge's internal libraries (JSON and certificate handling), implemented in the internal jar and loaded
 * in a class loader of their own. That loader sees the JDK and this package, nothing else; the Java code PHP
 * calls never sees the libraries.
 * <p>
 * Only types from the JDK and from this package cross the boundary.
 */
public interface BridgeInternals {

    /** @return the JSON reader and writer used for the wire protocol */
    JsonCodec json();

    /** @return key, certificate and PEM support */
    TlsSupport tls();

    /** @return the libraries and their versions, for the startup log line */
    String describe();
}
