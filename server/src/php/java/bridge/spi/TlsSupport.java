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

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;

/**
 * Key and certificate support the JDK does not offer: creating certificates and reading every common PEM
 * private key format.
 */
public interface TlsSupport {

    /** What a created certificate may be used for. */
    enum Usage {
        /** a certificate authority: signs other certificates */
        CA,
        /** a TLS server */
        SERVER,
        /** a TLS client */
        CLIENT
    }

    /**
     * Create an EC (P-256) key pair and a certificate for it.
     * @param commonName the subject's common name (CN)
     * @param issuer the CA that signs the certificate, or null for a self-signed certificate
     * @param usage what the certificate may be used for
     * @param subjectAltNames DNS names, IP addresses and URIs (a name with a scheme, such as
     *                        {@code urn:example:service:bridge}) for the subject alternative name extension; may be
     *                        empty
     * @param notBefore start of validity
     * @param notAfter end of validity
     * @return the private key and the certificate chain, the new certificate first; the chain does not include
     *         a self-signed root
     * @throws GeneralSecurityException if the key or the certificate cannot be created
     */
    KeyMaterial createCertificate(String commonName, KeyMaterial issuer, Usage usage, List<String> subjectAltNames,
                                  Instant notBefore, Instant notAfter) throws GeneralSecurityException;

    /**
     * Read a private key from PEM text: PKCS#8 (plain or encrypted), PKCS#1 RSA or SEC1 EC keys. Other PEM
     * blocks in the text, such as certificates, are skipped.
     * @param pem the PEM text
     * @param password the password of an encrypted key, or null
     * @return the first private key in the text
     * @throws GeneralSecurityException if there is no key, the password is missing or wrong, or the key is
     *         malformed
     */
    PrivateKey readPrivateKey(String pem, char[] password) throws GeneralSecurityException;

    /**
     * @param certificate a certificate
     * @return the names it is issued to: the common names (CN) of its subject, then the DNS names and URIs of
     *         its subject alternative name extension
     */
    List<String> subjectNames(X509Certificate certificate);

    /**
     * A private key and its certificate chain.
     * @param privateKey the key
     * @param chain the certificates, the key's own certificate first
     */
    record KeyMaterial(PrivateKey privateKey, List<X509Certificate> chain) {
        /** @return the key's own certificate */
        public X509Certificate certificate() {
            return chain.get(0);
        }
    }
}
