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

/**
 * The options of a connection for the current PHP request, sent by the client's "begin" message: whether
 * primitive results are sent as values. Strings are always UTF-8.
 * @author jostb
 *
 */
final class Options {

    private boolean preferValues;

    /**
     * Returns the string encoding, UTF-8.
     * @return The encoding name
     */
    public String getEncoding() {
	return Util.DEFAULT_ENCODING;
    }

    /**
     * Return a byte array using the string encoding.
     * @param s The string
     * @return The encoded bytes.
     */
    public byte[] getBytes(String s) {
	return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Returns true when primitive results (int, boolean, double, ...) are sent to the client as values
     * instead of object references.
     * @return the client's choice for the current PHP request
     */
    public boolean preferValues() {
	return preferValues;
    }

    /**
     * @param preferValues true to send primitive results as values
     */
    void setPreferValues(boolean preferValues) {
	this.preferValues = preferValues;
    }

    /** re-initialize for keep alive */
    protected void recycle() {
    }
}
