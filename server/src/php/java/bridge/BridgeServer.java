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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The bridge as a server: listens on a TCP socket and serves each PHP connection on its own thread.
 *
 * <pre>
 * BridgeServer server = BridgeServer.builder()
 *         .address("127.0.0.1")
 *         .port(9267)
 *         .threads(50)
 *         .security(BridgeSecurity.tlsCertificates(TlsCredentials.fromFiles(cert, key, null, roots)))
 *         .exceptionListener((cause, message) -&gt; log.info(message))
 *         .start();
 * ...
 * server.stop();
 * </pre>
 *
 * A PHP process keeps its connection open between requests, so each connected PHP worker holds one thread.
 * When all threads are busy, new connections wait until one is released.
 * <p>
 * <b>Security:</b> a client that is let in can create any Java object and call any method in this JVM. The
 * default provider, {@link BridgeSecurity#tlsAnonymous()}, encrypts connections but lets in anyone who can
 * reach the port, so listen on the loopback address (the default). {@code BridgeSecurity.tlsCertificates}
 * admits only clients with a certificate. See docs/security.md.
 * <p>
 * The log level and logger are JVM-wide settings of the bridge ({@link Util#logLevel},
 * {@link Util#setDefaultLogger(ILogger)}); {@link #start()} applies the ones given to the builder.
 */
public final class BridgeServer implements AutoCloseable {

    /** The default port, 9267. */
    public static final int DEFAULT_PORT = 9267;

    /** The default number of connection threads: the system property php.java.bridge.threads, or 20. */
    public static final int DEFAULT_THREADS = Integer.getInteger("php.java.bridge.threads", 20);

    /** How long a new connection may take to finish its TLS handshake and hello, in milliseconds. */
    static final int HANDSHAKE_TIMEOUT = 20_000;

    private final InetAddress address;
    private final int requestedPort;
    private final int maxThreads;
    private final int logLevel;
    private final ILogger logger;
    private final IExceptionListener exceptionListener;
    private final SecurityProvider security;

    private final Semaphore permits;
    private final AtomicInteger busy = new AtomicInteger();
    private final AtomicLong accepted = new AtomicLong();
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final CountDownLatch terminated = new CountDownLatch(1);

    private TCPServerSocket socket;
    private int port;
    private ThreadPoolExecutor executor;
    private Thread acceptThread;
    private CountingLogger countingLogger;
    private volatile boolean stopping;

    private BridgeServer(Builder b) {
        this.address = b.address;
        this.requestedPort = b.port;
        this.maxThreads = b.threads;
        this.logLevel = b.logLevel;
        this.logger = b.logger;
        this.exceptionListener = b.exceptionListener;
        this.security = b.security;
        this.permits = new Semaphore(b.threads);
    }

    /**
     * @return a builder with the defaults: 127.0.0.1, port 9267, {@link #DEFAULT_THREADS} threads, log level 2,
     *         security {@code tls-anonymous}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Bind the socket and start accepting connections. Returns once the socket is listening.
     * @return this server
     * @throws IOException if the socket cannot be bound, for example because the port is in use, or the
     *         security provider's key material cannot be read or is invalid
     * @throws IllegalStateException if the server was already started, or the internal libraries are missing
     */
    public synchronized BridgeServer start() throws IOException {
        if (socket != null || stopping) throw new IllegalStateException("already started");

        Util.logLevel = logLevel;
        countingLogger = new CountingLogger(logger != null ? logger : Util.getLogger());
        Util.setDefaultLogger(countingLogger);

        String libraries = InternalLibraries.get().describe();
        try {
            security.start();
        } catch (GeneralSecurityException e) {
            throw new IOException(security.name() + ": " + e.getMessage(), e);
        }

        TCPServerSocket s = new TCPServerSocket(address, requestedPort, Util.BACKLOG);
        socket = s;
        port = s.getPort();

        executor = new ThreadPoolExecutor(0, maxThreads, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                                          threads("JavaBridge-connection-", true));
        acceptThread = threads("JavaBridge-accept", false).newThread(this::acceptLoop);
        acceptThread.start();

        Util.logMessage("PHP/Java Bridge " + Util.VERSION + " listening on " + s + ", security " + security.describe()
                        + ", threads " + maxThreads + ", log level " + logLevel + ", Java " + Util.VM_NAME
                        + ", internal libraries " + libraries);
        return this;
    }

    private static ThreadFactory threads(String name, boolean daemon) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name.endsWith("-") ? name + n.incrementAndGet() : name);
            t.setDaemon(daemon);
            return t;
        };
    }

    private void acceptLoop() {
        try {
            while (!stopping) {
                try {
                    permits.acquire(); // wait for a free thread before taking the next connection
                } catch (InterruptedException e) {
                    break;
                }
                Socket sock;
                try {
                    sock = socket.accept();
                } catch (IOException e) {
                    permits.release();
                    if (stopping) break;
                    Util.printStackTrace(e);
                    pause();
                    continue;
                }
                accepted.incrementAndGet();
                connections.add(sock);
                try {
                    executor.execute(() -> serve(sock));
                } catch (RejectedExecutionException e) {
                    connections.remove(sock);
                    closeQuietly(sock);
                    permits.release();
                }
            }
        } finally {
            terminated.countDown();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void serve(Socket sock) {
        busy.incrementAndGet();
        try {
            Util.logDebug("Connection accepted from " + sock.getRemoteSocketAddress());
            sock.setSoTimeout(HANDSHAKE_TIMEOUT); // until the client is let in; then no timeout
            Socket secured;
            try {
                secured = security.secure(sock);
            } catch (EOFException e) {
                // Closed before it sent anything: a TCP check of the port, not a failure
                Util.logDebug("Connection from " + sock.getRemoteSocketAddress() + " closed: " + e.getMessage());
                return;
            } catch (IOException e) {
                if (!stopping) Util.logError("Connection from " + sock.getRemoteSocketAddress() + " refused by "
                                             + security.name() + ": " + e.getMessage());
                return;
            }
            JavaBridge bridge = new JavaBridge(exceptionListener);
            bridge.connection = new Connection(secured, security);
            bridge.in = new BufferedInputStream(secured.getInputStream(), 65536);
            bridge.out = new BufferedOutputStream(secured.getOutputStream(), 65536);
            bridge.run(); // returns when the client closes the connection
        } catch (IOException e) {
            if (!stopping) Util.printStackTrace(e);
        } finally {
            busy.decrementAndGet();
            connections.remove(sock);
            closeQuietly(sock);
            permits.release();
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            /* already closed */
        }
    }

    /**
     * Stop accepting connections, close the open ones and stop the connection threads. Calls in progress are
     * interrupted. Does nothing if the server is not running.
     */
    public void stop() {
        synchronized (this) {
            if (socket == null || stopping) return;
            stopping = true;
        }
        try {
            socket.close();
        } catch (IOException e) {
            Util.printStackTrace(e);
        }
        acceptThread.interrupt();
        for (Socket s : connections) closeQuietly(s);
        executor.shutdownNow();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
            acceptThread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Util.logMessage("PHP/Java Bridge stopped");
    }

    /** Same as {@link #stop()}. */
    public void close() {
        stop();
    }

    /**
     * Wait until the server has stopped accepting connections, that is until {@link #stop()} is called.
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    /**
     * @return the port the server listens on (the chosen port, if it was started with port 0), or 0 before start
     */
    public synchronized int getPort() {
        return port;
    }

    /** @return the address the server listens on */
    public InetAddress getAddress() {
        return address;
    }

    /** @return the security provider */
    public SecurityProvider getSecurity() {
        return security;
    }

    /**
     * @return current thread and connection counts and the number of bridge errors logged since start
     */
    public Stats stats() {
        ThreadPoolExecutor ex;
        CountingLogger cl;
        synchronized (this) {
            ex = executor;
            cl = countingLogger;
        }
        int b = busy.get();
        int pool = ex == null ? 0 : ex.getPoolSize();
        return new Stats(b, Math.max(0, pool - b), maxThreads, accepted.get(), cl == null ? 0 : cl.errors.get());
    }

    /**
     * A snapshot of the server's state.
     *
     * @param busyThreads threads serving a connection (a connected PHP worker holds its thread between requests)
     * @param idleThreads started threads waiting for a connection
     * @param maxThreads the most threads the server starts
     * @param connectionsAccepted connections accepted since start
     * @param errors bridge errors and fatal errors logged since start (protocol or connection failures;
     *               exceptions returned to PHP are not errors)
     */
    public record Stats(int busyThreads, int idleThreads, int maxThreads, long connectionsAccepted, long errors) {}

    /** Counts the errors passing through to the configured logger. */
    private static final class CountingLogger implements ILogger {
        private final ILogger delegate;
        private final AtomicLong errors = new AtomicLong();

        CountingLogger(ILogger delegate) {
            this.delegate = delegate instanceof CountingLogger ? ((CountingLogger) delegate).delegate : delegate;
        }

        public void printStackTrace(Throwable t) {
            errors.incrementAndGet();
            delegate.printStackTrace(t);
        }

        public void log(int level, String msg) {
            if (level <= ERROR) errors.incrementAndGet();
            delegate.log(level, msg);
        }

        public void warn(String msg) {
            delegate.warn(msg);
        }
    }

    /** Settings for a {@link BridgeServer}. */
    public static final class Builder {
        private InetAddress address = InetAddress.getLoopbackAddress();
        private int port = DEFAULT_PORT;
        private int threads = DEFAULT_THREADS;
        private int logLevel = Util.DEFAULT_LOG_LEVEL;
        private ILogger logger;
        private IExceptionListener exceptionListener;
        private SecurityProvider security;

        private Builder() {}

        /**
         * @param address the address to listen on; default the loopback address 127.0.0.1
         * @return this builder
         */
        public Builder address(InetAddress address) {
            if (address == null) throw new NullPointerException("address");
            this.address = address;
            return this;
        }

        /**
         * @param host the host name or IP address to listen on; default 127.0.0.1
         * @return this builder
         * @throws UnknownHostException if the name cannot be resolved
         */
        public Builder address(String host) throws UnknownHostException {
            return address(InetAddress.getByName(host));
        }

        /**
         * @param port the port, 0 to let the operating system choose one; default 9267
         * @return this builder
         */
        public Builder port(int port) {
            if (port < 0 || port > 65535) throw new IllegalArgumentException("port " + port);
            this.port = port;
            return this;
        }

        /**
         * @param threads the most connections served at once; default {@link BridgeServer#DEFAULT_THREADS}
         * @return this builder
         */
        public Builder threads(int threads) {
            if (threads < 1) throw new IllegalArgumentException("threads " + threads);
            this.threads = threads;
            return this;
        }

        /**
         * @param logLevel 0 off, 1 fatal, 2 errors (default), 3 info, 4 debug, 5 method calls
         * @return this builder
         */
        public Builder logLevel(int logLevel) {
            if (logLevel < 0 || logLevel > 6) throw new IllegalArgumentException("log level " + logLevel);
            this.logLevel = logLevel;
            return this;
        }

        /**
         * @param logger where the bridge logs; default the current {@link Util#getLogger()} (System.err)
         * @return this builder
         */
        public Builder logger(ILogger logger) {
            this.logger = logger;
            return this;
        }

        /**
         * @param exceptionListener told about every Java exception returned to PHP; default none
         * @return this builder
         */
        public Builder exceptionListener(IExceptionListener exceptionListener) {
            this.exceptionListener = exceptionListener;
            return this;
        }

        /**
         * @param security how connections are secured; default {@link BridgeSecurity#tlsAnonymous()}
         * @return this builder
         */
        public Builder security(SecurityProvider security) {
            this.security = Objects.requireNonNull(security, "security");
            return this;
        }

        /** @return a server with these settings, not yet started */
        public BridgeServer build() {
            if (security == null) security = BridgeSecurity.tlsAnonymous();
            return new BridgeServer(this);
        }

        /**
         * Build and start a server.
         * @return the started server
         * @throws IOException if the socket cannot be bound
         */
        public BridgeServer start() throws IOException {
            return build().start();
        }
    }
}
