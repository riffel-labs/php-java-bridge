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

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import php.java.bridge.spi.TlsSupport;

/**
 * The built-in security providers.
 * <ul>
 * <li>{@link #tlsAnonymous()}, the default: TLS with a key and certificate generated at startup. The
 * connection is encrypted; neither side is authenticated.</li>
 * <li>{@link #tlsCertificates(TlsCredentials, String...)}: TLS with a server certificate, and every client
 * must present a certificate that chains to one of the trusted roots: either in the TLS handshake, or, for a
 * client that holds its key in memory, by signing a challenge right after it.</li>
 * <li>{@link #plain()}: no encryption and no authentication; only for a loopback socket in a trusted
 * environment.</li>
 * </ul>
 * See docs/security.md.
 */
public final class BridgeSecurity {

    /** Name of the provider without encryption. */
    public static final String PLAIN = "plain";
    /** Name of the default provider. */
    public static final String TLS_ANONYMOUS = "tls-anonymous";
    /** Name of the provider with certificates. */
    public static final String TLS_CERTIFICATES = "tls-certificates";

    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};
    private static final String CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";
    private static final String ANY_EXTENDED_KEY_USAGE = "2.5.29.37.0";

    private BridgeSecurity() {}

    /** @return the provider without encryption or authentication */
    public static SecurityProvider plain() {
        return new Plain();
    }

    /** @return the default provider: TLS with a generated key, no authentication */
    public static SecurityProvider tlsAnonymous() {
        return new AnonymousTls();
    }

    /**
     * @param credentials the server's key and certificate, and the roots client certificates must chain to
     * @param allowedClients if given, accept only client certificates issued to one of these names (common
     *                       name, DNS name or URI, such as {@code urn:example:audience:bridge}); otherwise any
     *                       client certificate from the roots
     * @return the provider with certificates
     */
    public static SecurityProvider tlsCertificates(TlsCredentials credentials, String... allowedClients) {
        Objects.requireNonNull(credentials, "credentials");
        return new CertificateTls(() -> credentials, allowedClients);
    }

    /**
     * Like {@link #tlsCertificates(TlsCredentials, String...)}, with credentials that may change while the
     * server runs, for example short-lived certificates that are renewed: the supplier is asked for each new
     * connection, and must return the same object for as long as the credentials stay the same.
     * @param credentials supplies the current credentials
     * @param allowedClients if given, accept only client certificates issued to one of these names
     * @return the provider with certificates
     */
    public static SecurityProvider tlsCertificates(Supplier<TlsCredentials> credentials, String... allowedClients) {
        Objects.requireNonNull(credentials, "credentials");
        return new CertificateTls(credentials, allowedClients);
    }

    // ---------------------------------------------------------------- providers

    private static final class Plain implements SecurityProvider {
        public String name() {
            return PLAIN;
        }

        public String describe() {
            return PLAIN + " (no encryption, no authentication)";
        }

        public Socket secure(Socket accepted) {
            return accepted;
        }
    }

    private static final class AnonymousTls implements SecurityProvider {
        private volatile SSLContext context;
        private String fingerprint;

        public String name() {
            return TLS_ANONYMOUS;
        }

        public synchronized void start() throws GeneralSecurityException {
            if (context != null) return;
            Instant now = Instant.now();
            TlsSupport.KeyMaterial key = InternalLibraries.get().tls().createCertificate(
                "php-java-bridge", null, TlsSupport.Usage.SERVER, List.of("localhost", "127.0.0.1", "::1"),
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(3650)));
            fingerprint = fingerprint(key.certificate());
            context = context(key.privateKey(), key.chain(), null);
        }

        public String describe() {
            return TLS_ANONYMOUS + " (encrypted with a key generated at startup, certificate SHA-256 " + fingerprint
                   + "; clients are not authenticated)";
        }

        public Socket secure(Socket accepted) throws IOException {
            if (context == null) {
                try {
                    start();
                } catch (GeneralSecurityException e) {
                    throw new IOException("TLS setup failed: " + e.getMessage(), e);
                }
            }
            return handshake(context, accepted, false);
        }
    }

    private static final class CertificateTls implements SecurityProvider {
        private final Supplier<TlsCredentials> source;
        private final Set<String> allowed;
        private volatile Cached cached;

        private record Cached(TlsCredentials credentials, SSLContext context) {}

        CertificateTls(Supplier<TlsCredentials> source, String... allowedClients) {
            this.source = source;
            this.allowed = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(allowedClients)));
        }

        public String name() {
            return TLS_CERTIFICATES;
        }

        public void start() throws GeneralSecurityException {
            context(credentials());
        }

        public String describe() {
            TlsCredentials c;
            try {
                c = credentials();
            } catch (GeneralSecurityException e) {
                return TLS_CERTIFICATES + " (" + e.getMessage() + ")";
            }
            List<String> roots = new ArrayList<>();
            for (X509Certificate r : c.trustedRoots()) roots.add(r.getSubjectX500Principal().getName());
            return TLS_CERTIFICATES + " (server certificate " + c.chain().get(0).getSubjectX500Principal().getName()
                   + ", clients need a certificate from " + String.join("; ", roots)
                   + (allowed.isEmpty() ? "" : " issued to " + String.join(", ", allowed)) + ")";
        }

        private TlsCredentials credentials() throws GeneralSecurityException {
            TlsCredentials c = source.get();
            if (c == null) throw new GeneralSecurityException("no TLS credentials available");
            return c;
        }

        private SSLContext context(TlsCredentials c) throws GeneralSecurityException {
            Cached k = cached;
            if (k != null && k.credentials == c) return k.context;
            SSLContext ctx = BridgeSecurity.context(c.privateKey(), c.chain(), c.trustedRoots());
            cached = new Cached(c, ctx);
            return ctx;
        }

        public Socket secure(Socket accepted) throws IOException {
            SSLContext ctx;
            try {
                ctx = context(credentials());
            } catch (GeneralSecurityException e) {
                throw new IOException("TLS setup failed: " + e.getMessage(), e);
            }
            SSLSocket ssl = handshake(ctx, accepted, true);
            X509Certificate[] peer = peerCertificates(ssl);
            if (peer != null) {
                try {
                    checkAllowed(peer[0]);
                } catch (GeneralSecurityException e) {
                    ssl.close();
                    throw new IOException(e.getMessage());
                }
            }
            return ssl;
        }

        public boolean requiresProof(Socket secured) {
            return !(secured instanceof SSLSocket) || peerCertificates((SSLSocket) secured) == null;
        }

        public void verifyProof(Socket secured, byte[] challenge, String certificatePem, byte[] signature)
            throws GeneralSecurityException {
            List<X509Certificate> chain = TlsCredentials.certificates(certificatePem);
            X509Certificate leaf = chain.get(0);
            validate(chain, credentials().trustedRoots());
            checkClientUsage(leaf);
            checkAllowed(leaf);

            String algorithm = TlsCredentials.signatureAlgorithm(leaf.getPublicKey().getAlgorithm());
            if (algorithm == null) throw new GeneralSecurityException("unsupported client key type " + leaf.getPublicKey().getAlgorithm());
            Certificate[] local = ((SSLSocket) secured).getSession().getLocalCertificates();
            Signature s = Signature.getInstance(algorithm);
            s.initVerify(leaf.getPublicKey());
            s.update(Request.PROOF_PREFIX);
            s.update(challenge);
            s.update(sha256(local[0].getEncoded()));
            boolean ok;
            try {
                ok = s.verify(signature);
            } catch (java.security.SignatureException e) {
                ok = false;
            }
            if (!ok) throw new GeneralSecurityException("the signature does not match the certificate");
        }

        private void checkAllowed(X509Certificate cert) throws GeneralSecurityException {
            if (allowed.isEmpty()) return;
            List<String> names = InternalLibraries.get().tls().subjectNames(cert);
            for (String n : names) {
                if (allowed.contains(n)) return;
            }
            throw new GeneralSecurityException("the client certificate " + names + " is not one of the allowed clients");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void validate(List<X509Certificate> chain, List<X509Certificate> roots) throws GeneralSecurityException {
        Set<TrustAnchor> anchors = new HashSet<>();
        for (X509Certificate r : roots) anchors.add(new TrustAnchor(r, null));
        List<X509Certificate> path = new ArrayList<>(chain);
        path.removeIf(roots::contains); // a client may send the root along; the path ends below it
        if (path.isEmpty()) throw new GeneralSecurityException("the client certificate is a trusted root itself");
        CertPath certPath = CertificateFactory.getInstance("X.509").generateCertPath(path);
        PKIXParameters params = new PKIXParameters(anchors);
        params.setRevocationEnabled(false);
        try {
            CertPathValidator.getInstance("PKIX").validate(certPath, params);
        } catch (CertPathValidatorException e) {
            throw new GeneralSecurityException("the client certificate is not trusted: " + e.getMessage(), e);
        }
    }

    private static void checkClientUsage(X509Certificate leaf) throws GeneralSecurityException {
        List<String> eku = leaf.getExtendedKeyUsage();
        if (eku != null && !eku.contains(CLIENT_AUTH) && !eku.contains(ANY_EXTENDED_KEY_USAGE)) {
            throw new GeneralSecurityException("the certificate is not meant for TLS clients (extended key usage)");
        }
        boolean[] ku = leaf.getKeyUsage();
        if (ku != null && !ku[0]) {
            throw new GeneralSecurityException("the certificate's key may not sign (key usage)");
        }
    }

    private static SSLContext context(PrivateKey key, List<X509Certificate> chain, List<X509Certificate> roots)
        throws GeneralSecurityException {
        char[] password = "php-java-bridge".toCharArray(); // in-memory key store only
        try {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            keys.load(null, null);
            keys.setKeyEntry("bridge", key, password, chain.toArray(new X509Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, password);
            TrustManager[] trust = null;
            if (roots != null) {
                KeyStore anchors = KeyStore.getInstance("PKCS12");
                anchors.load(null, null);
                int i = 0;
                for (X509Certificate r : roots) anchors.setCertificateEntry("root" + i++, r);
                TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
                tmf.init(anchors);
                trust = tmf.getTrustManagers();
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), trust, null);
            return ctx;
        } catch (IOException e) {
            throw new GeneralSecurityException(e.getMessage(), e);
        }
    }

    /**
     * The TLS handshake on an accepted connection. A connection closed before it sent anything - a TCP check of
     * the port, as load balancers and service monitors make - throws {@link EOFException}, which the server does
     * not count as an error. Every other failure is a refused connection.
     */
    private static SSLSocket handshake(SSLContext context, Socket accepted, boolean wantClientAuth) throws IOException {
        int first = accepted.getInputStream().read();
        if (first == -1) throw new EOFException("the client closed the connection before the TLS handshake");
        SSLSocket ssl = (SSLSocket) context.getSocketFactory()
            .createSocket(accepted, new ByteArrayInputStream(new byte[] {(byte) first}), true);
        ssl.setUseClientMode(false);
        SSLParameters p = ssl.getSSLParameters();
        p.setProtocols(PROTOCOLS);
        p.setWantClientAuth(wantClientAuth);
        ssl.setSSLParameters(p);
        ssl.startHandshake();
        return ssl;
    }

    private static X509Certificate[] peerCertificates(SSLSocket s) {
        try {
            Certificate[] c = s.getSession().getPeerCertificates();
            X509Certificate[] x = new X509Certificate[c.length];
            for (int i = 0; i < c.length; i++) x[i] = (X509Certificate) c[i];
            return x.length == 0 ? null : x;
        } catch (SSLPeerUnverifiedException e) {
            return null;
        }
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the SHA-256 fingerprint of a certificate, as colon-separated hex bytes */
    static String fingerprint(X509Certificate cert) {
        try {
            byte[] h = sha256(cert.getEncoded());
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < h.length; i++) {
                if (i > 0) b.append(':');
                b.append(Character.forDigit((h[i] >> 4) & 15, 16)).append(Character.forDigit(h[i] & 15, 16));
            }
            return b.toString().toUpperCase(java.util.Locale.ROOT);
        } catch (java.security.cert.CertificateEncodingException e) {
            return "?";
        }
    }
}
