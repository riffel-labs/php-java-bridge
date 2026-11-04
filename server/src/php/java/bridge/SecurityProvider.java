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

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;

/**
 * How the bridge secures its connections: encryption, and who may connect. The built-in providers come from
 * {@link BridgeSecurity}: {@code plain}, {@code tls-anonymous} (the default) and {@code tls-certificates}.
 * <p>
 * A connection goes through these steps: {@link #secure(Socket)} (for example a TLS handshake), then the
 * client's {@code hello}. If {@link #requiresProof(Socket)} is true, the server answers with a challenge and
 * the client must prove its identity with a certificate and a signature, checked by
 * {@link #verifyProof(Socket, byte[], String, byte[])}, before it can send requests. See docs/security.md.
 * <p>
 * Methods other than {@link #start()} are called concurrently from the connection threads.
 */
public interface SecurityProvider {

    /** @return the provider's name, for example {@code tls-anonymous} */
    String name();

    /**
     * Prepare the provider; called once by {@link BridgeServer#start()} before the first connection.
     * @throws IOException if key material cannot be read
     * @throws GeneralSecurityException if key material is invalid
     */
    default void start() throws IOException, GeneralSecurityException {}

    /** @return a description for the startup log line; called after {@link #start()} */
    default String describe() {
        return name();
    }

    /**
     * Secure a newly accepted connection, for example with a TLS handshake. Runs on the connection's thread;
     * the socket has a read timeout set, so a client that stalls cannot hold the thread.
     * @param accepted the accepted TCP connection
     * @return the socket to talk over: the same one, or a TLS socket layered over it
     * @throws IOException if the connection cannot be secured; the server then closes it and logs it as a refused
     *         connection, except for an {@link java.io.EOFException}: throw that when the client closed the
     *         connection before it sent anything (a TCP check of the port), which is not an error
     */
    Socket secure(Socket accepted) throws IOException;

    /**
     * @param secured the socket returned by {@link #secure(Socket)}
     * @return true if the client must still prove its identity with a signed challenge
     */
    default boolean requiresProof(Socket secured) {
        return false;
    }

    /**
     * Check a client's proof of identity: its certificate must be acceptable, and {@code signature} must be
     * the signature, made with the certificate's private key, of the protocol's proof data (see
     * docs/protocol.md): the prefix {@code php-java-bridge-auth-v1\0}, the challenge, and the SHA-256 hash of
     * the server certificate of this connection.
     * @param secured the socket returned by {@link #secure(Socket)}
     * @param challenge the random bytes the server sent
     * @param certificatePem the client's certificate, optionally followed by intermediate certificates, as PEM
     * @param signature the client's signature
     * @throws GeneralSecurityException if the proof is rejected; its message tells the client why
     */
    default void verifyProof(Socket secured, byte[] challenge, String certificatePem, byte[] signature)
        throws GeneralSecurityException {
        throw new GeneralSecurityException(name() + " does not accept client certificates");
    }
}
