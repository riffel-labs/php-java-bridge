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
import java.io.StringReader;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.AttributeTypeAndValue;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;

import php.java.bridge.spi.TlsSupport;

/**
 * Certificates and PEM keys with Bouncy Castle's API classes. Bouncy Castle is never registered as a JCA
 * provider: keys are generated, signed and converted by the JDK's own providers, so nothing changes for the
 * rest of the JVM.
 */
final class BouncyCastleTls implements TlsSupport {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern IP_ADDRESS = Pattern.compile("^[0-9.]+$|^[0-9a-fA-F:]*:[0-9a-fA-F:.]*$");
    private static final Pattern URI = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.+$"); // checked after IP_ADDRESS

    @Override
    public KeyMaterial createCertificate(String commonName, KeyMaterial issuer, Usage usage,
                                         List<String> subjectAltNames, Instant notBefore, Instant notAfter)
        throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        KeyPair pair = generator.generateKeyPair();

        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, commonName).build();
        X500Name issuerName = issuer == null ? subject
                                             : X500Name.getInstance(issuer.certificate().getSubjectX500Principal().getEncoded());
        PrivateKey signingKey = issuer == null ? pair.getPrivate() : issuer.privateKey();

        try {
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuerName, new BigInteger(126, RANDOM).add(BigInteger.ONE), Date.from(notBefore), Date.from(notAfter),
                subject, pair.getPublic());
            JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(pair.getPublic()));
            if (issuer != null) {
                builder.addExtension(Extension.authorityKeyIdentifier, false,
                                     ext.createAuthorityKeyIdentifier(issuer.certificate()));
            }
            switch (usage) {
            case CA:
                builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
                break;
            case SERVER:
                builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
                builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
                break;
            case CLIENT:
                builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
                builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
                break;
            }
            if (subjectAltNames != null && !subjectAltNames.isEmpty()) {
                List<GeneralName> names = new ArrayList<>();
                for (String n : subjectAltNames) {
                    int type = IP_ADDRESS.matcher(n).matches() ? GeneralName.iPAddress
                             : URI.matcher(n).matches() ? GeneralName.uniformResourceIdentifier
                             : GeneralName.dNSName;
                    names.add(new GeneralName(type, n));
                }
                builder.addExtension(Extension.subjectAlternativeName, false,
                                     new GeneralNames(names.toArray(new GeneralName[0])));
            }
            X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder(signatureAlgorithm(signingKey)).build(signingKey)));

            List<X509Certificate> chain = new ArrayList<>();
            chain.add(cert);
            if (issuer != null) {
                for (X509Certificate c : issuer.chain()) {
                    if (!c.getSubjectX500Principal().equals(c.getIssuerX500Principal())) chain.add(c);
                }
            }
            return new KeyMaterial(pair.getPrivate(), List.copyOf(chain));
        } catch (CertIOException | OperatorCreationException e) {
            throw new GeneralSecurityException("Cannot create the certificate for " + commonName + ": " + e.getMessage(), e);
        }
    }

    private static String signatureAlgorithm(PrivateKey key) throws GeneralSecurityException {
        switch (key.getAlgorithm()) {
        case "EC": return "SHA256withECDSA";
        case "RSA": return "SHA256withRSA";
        case "Ed25519": case "EdDSA": return "Ed25519";
        default: throw new GeneralSecurityException("Unsupported issuer key type: " + key.getAlgorithm());
        }
    }

    @Override
    public PrivateKey readPrivateKey(String pem, char[] password) throws GeneralSecurityException {
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            for (Object o = parser.readObject(); o != null; o = parser.readObject()) {
                if (o instanceof PrivateKeyInfo info) return converter.getPrivateKey(info);
                if (o instanceof PEMKeyPair pair) return converter.getKeyPair(pair).getPrivate();
                if (o instanceof PKCS8EncryptedPrivateKeyInfo encrypted) {
                    if (password == null) throw new GeneralSecurityException("The private key is encrypted; a password is needed");
                    PrivateKeyInfo info = encrypted.decryptPrivateKeyInfo(
                        new JceOpenSSLPKCS8DecryptorProviderBuilder().build(password));
                    return converter.getPrivateKey(info);
                }
                if (o instanceof PEMEncryptedKeyPair encrypted) {
                    if (password == null) throw new GeneralSecurityException("The private key is encrypted; a password is needed");
                    return converter.getKeyPair(encrypted.decryptKeyPair(
                        new JcePEMDecryptorProviderBuilder().build(password))).getPrivate();
                }
            }
        } catch (PKCSException e) {
            throw new GeneralSecurityException("Cannot decrypt the private key (wrong password?): " + e.getMessage(), e);
        } catch (IOException | OperatorCreationException e) {
            throw new GeneralSecurityException("Cannot read the private key: " + e.getMessage(), e);
        }
        throw new GeneralSecurityException("No private key found in the PEM text");
    }

    @Override
    public List<String> subjectNames(X509Certificate certificate) {
        List<String> names = new ArrayList<>();
        X500Name subject = X500Name.getInstance(certificate.getSubjectX500Principal().getEncoded());
        for (RDN rdn : subject.getRDNs(BCStyle.CN)) {
            for (AttributeTypeAndValue v : rdn.getTypesAndValues()) {
                if (BCStyle.CN.equals(v.getType())) names.add(IETFUtils.valueToString(v.getValue()));
            }
        }
        try {
            Collection<List<?>> alt = certificate.getSubjectAlternativeNames();
            if (alt != null) {
                for (List<?> entry : alt) {
                    if (entry.size() >= 2 && (Integer.valueOf(GeneralName.dNSName).equals(entry.get(0))
                                              || Integer.valueOf(GeneralName.uniformResourceIdentifier).equals(entry.get(0)))) {
                        names.add(String.valueOf(entry.get(1)));
                    }
                }
            }
        } catch (CertificateParsingException e) {
            /* no usable alternative names */
        }
        return names;
    }
}
