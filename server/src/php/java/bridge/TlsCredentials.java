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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The key material of the {@code tls-certificates} provider: the server's private key and certificate chain,
 * and the root certificates that client certificates must chain to.
 * <p>
 * Credentials can come from PEM files, from PEM text held in memory, or from JDK key objects. Nothing is
 * written to disk.
 */
public final class TlsCredentials {

    private static final Pattern PEM_CERTIFICATE =
        Pattern.compile("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----");

    private final PrivateKey privateKey;
    private final List<X509Certificate> chain;
    private final List<X509Certificate> trustedRoots;

    private TlsCredentials(PrivateKey privateKey, List<X509Certificate> chain, List<X509Certificate> trustedRoots) {
        this.privateKey = privateKey;
        this.chain = chain;
        this.trustedRoots = trustedRoots;
    }

    /**
     * @param privateKey the server's private key
     * @param chain the server's certificate first, then any intermediate certificates
     * @param trustedRoots the certificates client certificates must chain to
     * @return the credentials
     * @throws GeneralSecurityException if the private key does not belong to the server certificate
     * @throws IllegalArgumentException if the chain or the roots are empty
     */
    public static TlsCredentials of(PrivateKey privateKey, List<X509Certificate> chain,
                                    List<X509Certificate> trustedRoots) throws GeneralSecurityException {
        Objects.requireNonNull(privateKey, "privateKey");
        if (chain == null || chain.isEmpty()) throw new IllegalArgumentException("the certificate chain is empty");
        if (trustedRoots == null || trustedRoots.isEmpty()) throw new IllegalArgumentException("no trusted root certificates");
        checkKeyMatches(privateKey, chain.get(0));
        return new TlsCredentials(privateKey, List.copyOf(chain), List.copyOf(trustedRoots));
    }

    /**
     * @param certificateChainPem the server's certificate, then any intermediate certificates, as PEM
     * @param privateKeyPem the server's private key as PEM: PKCS#8 (plain or encrypted), PKCS#1 or SEC1
     * @param keyPassword the password of an encrypted key, or null
     * @param trustedRootsPem the certificates client certificates must chain to, as PEM
     * @return the credentials
     * @throws GeneralSecurityException if the text cannot be read or the key does not match the certificate
     */
    public static TlsCredentials fromPem(String certificateChainPem, String privateKeyPem, char[] keyPassword,
                                         String trustedRootsPem) throws GeneralSecurityException {
        PrivateKey key = InternalLibraries.get().tls().readPrivateKey(privateKeyPem, keyPassword);
        return of(key, certificates(certificateChainPem), certificates(trustedRootsPem));
    }

    /**
     * Read the credentials from PEM files.
     * @param certificateChain the server's certificate chain
     * @param privateKey the server's private key
     * @param keyPassword the password of an encrypted key, or null
     * @param trustedRoots the certificates client certificates must chain to
     * @return the credentials
     * @throws IOException if a file cannot be read
     * @throws GeneralSecurityException if a file's content is invalid, naming the file
     */
    public static TlsCredentials fromFiles(Path certificateChain, Path privateKey, char[] keyPassword,
                                           Path trustedRoots) throws IOException, GeneralSecurityException {
        List<X509Certificate> chain = certificates(certificateChain);
        List<X509Certificate> roots = certificates(trustedRoots);
        PrivateKey key;
        try {
            key = InternalLibraries.get().tls().readPrivateKey(Files.readString(privateKey, StandardCharsets.US_ASCII), keyPassword);
        } catch (GeneralSecurityException e) {
            throw new GeneralSecurityException(privateKey + ": " + e.getMessage(), e);
        }
        return of(key, chain, roots);
    }

    private static List<X509Certificate> certificates(Path file) throws IOException, GeneralSecurityException {
        try {
            return certificates(Files.readString(file, StandardCharsets.US_ASCII));
        } catch (CertificateException e) {
            throw new CertificateException(file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Read the certificates in PEM text; other PEM blocks are skipped.
     * @param pem the text
     * @return the certificates in the order they appear
     * @throws CertificateException if there is none or one is malformed
     */
    public static List<X509Certificate> certificates(String pem) throws CertificateException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> list = new ArrayList<>();
        Matcher m = PEM_CERTIFICATE.matcher(pem);
        while (m.find()) {
            byte[] der;
            try {
                der = Base64.getMimeDecoder().decode(m.group(1));
            } catch (IllegalArgumentException e) {
                throw new CertificateException("malformed PEM certificate", e);
            }
            list.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
        }
        if (list.isEmpty()) throw new CertificateException("no certificate found in the PEM text");
        return list;
    }

    /** @return the server's private key */
    public PrivateKey privateKey() {
        return privateKey;
    }

    /** @return the server's certificate, then any intermediate certificates */
    public List<X509Certificate> chain() {
        return chain;
    }

    /** @return the certificates client certificates must chain to */
    public List<X509Certificate> trustedRoots() {
        return trustedRoots;
    }

    /** The signature algorithm used with a key type, or null if the bridge does not support it. */
    static String signatureAlgorithm(String keyAlgorithm) {
        switch (keyAlgorithm) {
        case "EC": return "SHA256withECDSA";
        case "RSA": return "SHA256withRSA";
        case "Ed25519": case "EdDSA": return "Ed25519";
        default: return null;
        }
    }

    private static void checkKeyMatches(PrivateKey key, X509Certificate cert) throws GeneralSecurityException {
        String algorithm = signatureAlgorithm(key.getAlgorithm());
        if (algorithm == null) throw new GeneralSecurityException("unsupported key type " + key.getAlgorithm());
        byte[] probe = "php-java-bridge key check".getBytes(StandardCharsets.US_ASCII);
        Signature s = Signature.getInstance(algorithm);
        s.initSign(key);
        s.update(probe);
        byte[] signature = s.sign();
        Signature v = Signature.getInstance(algorithm);
        v.initVerify(cert.getPublicKey());
        v.update(probe);
        if (!v.verify(signature)) {
            throw new GeneralSecurityException("the private key does not belong to the certificate "
                                               + cert.getSubjectX500Principal().getName());
        }
    }
}
