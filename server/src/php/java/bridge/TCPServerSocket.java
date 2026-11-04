/*-*- mode: Java; tab-width:8 -*-*/

package php.java.bridge;

/*
 * Copyright (C) 2003-2007 Jost Boekemeier
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The plain TCP transport: a listening socket on one address and port.
 */
final class TCPServerSocket {

    private final ServerSocket sock;
    private final InetAddress address;

    /**
     * Bind a listening socket.
     * @param address the address to listen on, for example 127.0.0.1
     * @param port the port, or 0 to let the operating system choose one
     * @param backlog the connection backlog
     * @throws IOException if the socket cannot be bound
     */
    TCPServerSocket(InetAddress address, int port, int backlog) throws IOException {
        this.address = address;
        ServerSocket s = new ServerSocket();
        try {
            s.bind(new InetSocketAddress(address, port), backlog);
        } catch (IOException e) {
            s.close();
            throw e;
        }
        this.sock = s;
    }

    public void close() throws IOException {
        sock.close();
    }

    public Socket accept() throws IOException {
        Socket s = sock.accept();
        s.setTcpNoDelay(true);
        return s;
    }

    public String getSocketName() {
        return String.valueOf(sock.getLocalPort());
    }

    /** @return the port the socket listens on */
    int getPort() {
        return sock.getLocalPort();
    }

    public String toString() {
        return address.getHostAddress() + ":" + getSocketName();
    }
}
