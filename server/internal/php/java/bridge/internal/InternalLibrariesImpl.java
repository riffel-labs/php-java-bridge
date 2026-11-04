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

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.jar.JarFile;

import php.java.bridge.spi.BridgeInternals;
import php.java.bridge.spi.JsonCodec;
import php.java.bridge.spi.TlsSupport;

/**
 * Entry point of the internal jar, created by the bridge through its internal class loader.
 */
public final class InternalLibrariesImpl implements BridgeInternals {

    private final JacksonCodec json = new JacksonCodec();
    private final BouncyCastleTls tls = new BouncyCastleTls();

    /** Created by the bridge. */
    public InternalLibrariesImpl() {}

    @Override
    public JsonCodec json() {
        return json;
    }

    @Override
    public TlsSupport tls() {
        return tls;
    }

    @Override
    public String describe() {
        return "Jackson " + tools.jackson.core.json.PackageVersion.VERSION + ", Bouncy Castle "
               + bundleVersion(org.bouncycastle.cert.X509v3CertificateBuilder.class);
    }

    private static String bundleVersion(Class<?> c) {
        try {
            CodeSource source = c.getProtectionDomain().getCodeSource();
            try (JarFile jar = new JarFile(Path.of(source.getLocation().toURI()).toFile())) {
                String v = jar.getManifest().getMainAttributes().getValue("Bundle-Version");
                return v != null ? v : "(unknown version)";
            }
        } catch (IOException | URISyntaxException | RuntimeException e) {
            return "(unknown version)";
        }
    }
}
