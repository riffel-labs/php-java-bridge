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

package php.java.bridge.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import php.java.bridge.spi.JsonCodec;
import php.java.bridge.spi.JsonOutput;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonWriteFeature;

/**
 * JSON with Jackson's streaming API. Strings and documents may be as large as a Java array allows; nesting is
 * limited to {@link JsonCodec#MAX_DEPTH} in both directions.
 */
final class JacksonCodec implements JsonCodec {

    private final JsonFactory factory = JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder()
                               .maxNestingDepth(MAX_DEPTH)
                               .maxStringLength(Integer.MAX_VALUE)
                               .maxNameLength(Integer.MAX_VALUE)
                               .maxDocumentLength(-1)
                               .maxTokenCount(-1)
                               .build())
        .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(MAX_DEPTH).build())
        .disable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES)
        .rootValueSeparator((String) null)
        .build();

    @Override
    public Object parse(byte[] data, int offset, int length) throws IOException {
        try (JsonParser p = factory.createParser(ObjectReadContext.empty(), data, offset, length)) {
            JsonToken t = p.nextToken();
            if (t == null) throw new IOException("empty message");
            Object value = read(p, t);
            if (p.nextToken() != null) throw new IOException("unexpected data after the JSON value");
            return value;
        } catch (JacksonException e) {
            throw new IOException("malformed JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static Object read(JsonParser p, JsonToken t) {
        switch (t) {
        case START_OBJECT: {
            Map<String, Object> map = new LinkedHashMap<>();
            for (t = p.nextToken(); t != JsonToken.END_OBJECT; t = p.nextToken()) {
                String name = p.currentName();
                map.put(name, read(p, p.nextToken()));
            }
            return map;
        }
        case START_ARRAY: {
            List<Object> list = new ArrayList<>();
            for (t = p.nextToken(); t != JsonToken.END_ARRAY; t = p.nextToken()) list.add(read(p, t));
            return list;
        }
        case VALUE_STRING:
            return p.getString();
        case VALUE_NUMBER_INT:
            return p.getNumberType() == JsonParser.NumberType.BIG_INTEGER ? p.getBigIntegerValue()
                                                                         : (Object) p.getLongValue();
        case VALUE_NUMBER_FLOAT:
            return p.getDoubleValue();
        case VALUE_TRUE:
            return Boolean.TRUE;
        case VALUE_FALSE:
            return Boolean.FALSE;
        case VALUE_NULL:
            return null;
        default:
            throw new IllegalStateException("unexpected JSON token " + t);
        }
    }

    @Override
    public JsonOutput newOutput() {
        return new Output();
    }

    /** A growable byte buffer whose first four bytes are reserved for the frame length. */
    private static final class Buffer extends ByteArrayOutputStream {
        Buffer() {
            super(8192);
            clear();
        }

        void clear() {
            count = 4;
        }

        byte[] array() {
            return buf;
        }

        @Override
        public void reset() {
            clear();
        }
    }

    private final class Output implements JsonOutput {
        private final Buffer buffer = new Buffer();
        private JsonGenerator gen;

        private JsonGenerator g() {
            if (gen == null) gen = factory.createGenerator(ObjectWriteContext.empty(), buffer, JsonEncoding.UTF8);
            return gen;
        }

        @Override
        public void reset() {
            if (gen != null) {
                // closing returns the generator's buffers for reuse; whatever it still writes is discarded below
                try {
                    gen.close();
                } catch (RuntimeException e) {
                    /* a generator left inside an object or array: nothing to keep */
                }
                gen = null;
            }
            buffer.clear();
        }

        @Override public void startObject() { g().writeStartObject(); }
        @Override public void endObject() { g().writeEndObject(); }
        @Override public void name(String name) { g().writeName(name); }
        @Override public void startArray() { g().writeStartArray(); }
        @Override public void endArray() { g().writeEndArray(); }
        @Override public void nullValue() { g().writeNull(); }
        @Override public void value(boolean value) { g().writeBoolean(value); }
        @Override public void value(long value) { g().writeNumber(value); }
        @Override public void value(String value) { g().writeString(value); }

        @Override
        public void value(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("JSON has no form for " + value);
            }
            g().writeNumber(value);
        }

        @Override
        public void utf8Value(byte[] utf8, int offset, int length) {
            g().writeUTF8String(utf8, offset, length);
        }

        @Override
        public int finish() {
            if (gen != null) gen.flush();
            int n = buffer.size();
            int len = n - 4;
            byte[] b = buffer.array();
            b[0] = (byte) (len >>> 24);
            b[1] = (byte) (len >>> 16);
            b[2] = (byte) (len >>> 8);
            b[3] = (byte) len;
            return n;
        }

        @Override
        public byte[] buffer() {
            return buffer.array();
        }
    }
}
