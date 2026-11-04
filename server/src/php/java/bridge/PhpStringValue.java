/*-*- mode: Java; tab-width:8 -*-*/

package php.java.bridge;

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

import java.nio.charset.StandardCharsets;

/**
 * A string argument from PHP. PHP strings are bytes: the bridge converts them to a Java String or byte[]
 * depending on the parameter type of the method it calls. A text string arrives as a String; a binary
 * string (not valid UTF-8) arrives as its exact bytes.
 */
final class PhpStringValue extends PhpString {
    private String string;
    private byte[] bytes;

    /** @param string a text string */
    PhpStringValue(String string) {
        this.string = string;
    }

    /** @param bytes a binary string */
    PhpStringValue(byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public String getString() {
        if (string == null) string = new String(bytes, StandardCharsets.UTF_8);
        return string;
    }

    @Override
    public byte[] getBytes() {
        if (bytes == null) bytes = string.getBytes(StandardCharsets.UTF_8);
        return bytes;
    }
}
