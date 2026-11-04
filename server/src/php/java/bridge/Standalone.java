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
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import php.java.bridge.spi.TlsSupport;
import php.java.bridge.spi.TlsSupport.KeyMaterial;

/**
 * The command line entry point: runs a {@link BridgeServer} until the process is stopped.
 *
 * <pre>
 * java -jar php-java-bridge-server.jar --listen 127.0.0.1:9267 --threads 50 --log-level 2
 * java -cp "php-java-bridge-server.jar:lib/*" php.java.bridge.Standalone --listen 9267
 * java -jar php-java-bridge-server.jar --security tls-certificates --tls-cert server.pem --tls-key server-key.pem --tls-ca ca.pem
 * </pre>
 *
 * The upstream argument form <code>INET_LOCAL:PORT [LOGLEVEL [LOGFILE]]</code> is also accepted.
 */
public final class Standalone {

    private Standalone() {}

    /** Command line settings. */
    static final class Options {
        InetAddress address = InetAddress.getLoopbackAddress();
        int port = BridgeServer.DEFAULT_PORT;
        int threads = BridgeServer.DEFAULT_THREADS;
        int logLevel = Util.DEFAULT_LOG_LEVEL;
        String logFile;
        String security = BridgeSecurity.TLS_ANONYMOUS;
        String tlsCert, tlsKey, tlsKeyPasswordEnv, tlsCa;
        List<String> allowedClients = new ArrayList<>();
        String demoCertificates;
        boolean help;
        boolean version;
    }

    static void usage(PrintStream out) {
        out.println("PHP/Java Bridge " + Util.VERSION);
        out.println();
        out.println("Usage: java -jar php-java-bridge-server.jar [options]");
        out.println("       java -cp \"php-java-bridge-server.jar" + java.io.File.pathSeparator
                    + "lib/*\" php.java.bridge.Standalone [options]");
        out.println();
        out.println("Options:");
        out.println("  --listen [ADDRESS:]PORT     address and port to listen on (default 127.0.0.1:9267)");
        out.println("  --threads N                 connections served at once (default " + BridgeServer.DEFAULT_THREADS + ")");
        out.println("  --log-level N               0 off, 1 fatal, 2 errors (default), 3 info, 4 debug, 5 method calls");
        out.println("  --log-file FILE             append the log to FILE instead of standard error");
        out.println("  --security PROVIDER         tls-anonymous (default): encrypted, anyone who can connect gets in");
        out.println("                              tls-certificates: encrypted, clients need a certificate");
        out.println("                              plain: no encryption, no authentication");
        out.println("  --tls-cert FILE             tls-certificates: the server's certificate chain (PEM)");
        out.println("  --tls-key FILE              tls-certificates: the server's private key (PEM)");
        out.println("  --tls-key-password-env VAR  tls-certificates: environment variable with the key's password");
        out.println("  --tls-ca FILE               tls-certificates: root certificate(s) client certificates must chain to (PEM)");
        out.println("  --tls-allow-client NAME     tls-certificates: accept only clients whose certificate has this");
        out.println("                              common name, DNS name or URI (repeat for more names)");
        out.println("  --make-demo-certs DIR       write a demo root, server and client certificate to DIR and exit");
        out.println("  --version                   print the version and exit");
        out.println("  --help                      print this help and exit");
        out.println();
        out.println("Upstream form: INET_LOCAL:PORT [LOGLEVEL [LOGFILE]]  (INET:PORT listens on all interfaces)");
        out.println();
        out.println("A client that is let in can run any Java code in this JVM. Keep the bridge on 127.0.0.1, or use");
        out.println("--security tls-certificates. The jars in internal/ next to the server jar are the bridge's own");
        out.println("libraries; put the jars you want to call from PHP on the classpath (lib/ with the start scripts).");
    }

    /**
     * Parse the command line.
     * @param args the arguments
     * @return the settings
     * @throws IllegalArgumentException if the arguments are invalid
     */
    static Options parse(String[] args) {
        Options o = new Options();
        if (args.length > 0 && (args[0].startsWith("INET_LOCAL:") || args[0].startsWith("INET:"))) {
            return parseUpstream(args, o);
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
            case "--listen":    listen(o, value(args, ++i, a)); break;
            case "--threads":   o.threads = number(value(args, ++i, a), a, 1, Integer.MAX_VALUE); break;
            case "--log-level": o.logLevel = number(value(args, ++i, a), a, 0, 6); break;
            case "--log-file":  o.logFile = value(args, ++i, a); break;
            case "--security":  o.security = security(value(args, ++i, a)); break;
            case "--tls-cert":  o.tlsCert = value(args, ++i, a); break;
            case "--tls-key":   o.tlsKey = value(args, ++i, a); break;
            case "--tls-key-password-env": o.tlsKeyPasswordEnv = value(args, ++i, a); break;
            case "--tls-ca":    o.tlsCa = value(args, ++i, a); break;
            case "--tls-allow-client": o.allowedClients.add(value(args, ++i, a)); break;
            case "--make-demo-certs": o.demoCertificates = value(args, ++i, a); break;
            case "--version":   o.version = true; break;
            case "--help": case "-h": case "-?": o.help = true; break;
            default: throw new IllegalArgumentException("Unknown argument: " + a);
            }
        }
        boolean tlsOptions = o.tlsCert != null || o.tlsKey != null || o.tlsKeyPasswordEnv != null || o.tlsCa != null
                             || !o.allowedClients.isEmpty();
        if (BridgeSecurity.TLS_CERTIFICATES.equals(o.security)) {
            if (o.tlsCert == null || o.tlsKey == null || o.tlsCa == null) {
                throw new IllegalArgumentException("--security tls-certificates needs --tls-cert, --tls-key and --tls-ca");
            }
        } else if (tlsOptions) {
            throw new IllegalArgumentException("the --tls-* options are only used with --security tls-certificates");
        }
        return o;
    }

    private static String security(String s) {
        switch (s) {
        case BridgeSecurity.PLAIN: case BridgeSecurity.TLS_ANONYMOUS: case BridgeSecurity.TLS_CERTIFICATES:
            return s;
        default:
            throw new IllegalArgumentException("Unknown --security " + s + "; use tls-anonymous, tls-certificates or plain");
        }
    }

    private static Options parseUpstream(String[] args, Options o) {
        if (args.length > 3) throw new IllegalArgumentException("Too many arguments");
        String name = args[0];
        boolean local = name.startsWith("INET_LOCAL:");
        o.port = number(name.substring(name.indexOf(':') + 1), "port", 0, 65535);
        if (!local) {
            try {
                o.address = InetAddress.getByName("0.0.0.0");
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }
        if (args.length > 1) o.logLevel = number(args[1], "log level", 0, 6);
        if (args.length > 2 && !args[2].isEmpty()) o.logFile = args[2];
        return o;
    }

    private static void listen(Options o, String spec) {
        String host = null;
        String port = spec;
        int colon = spec.lastIndexOf(':');
        if (colon >= 0) {
            host = spec.substring(0, colon);
            port = spec.substring(colon + 1);
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        }
        o.port = number(port, "--listen port", 0, 65535);
        if (host != null && !host.isEmpty()) {
            try {
                o.address = InetAddress.getByName(host);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Unknown host: " + host);
            }
        }
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) throw new IllegalArgumentException(option + " needs a value");
        return args[i];
    }

    private static int number(String s, String what, int min, int max) {
        int n;
        try {
            n = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number for " + what + ": " + s);
        }
        if (n < min || n > max) throw new IllegalArgumentException(what + " must be between " + min + " and " + max);
        return n;
    }

    /**
     * Build the security provider the options ask for.
     * @param o the options
     * @return the provider
     * @throws IOException if a key or certificate file cannot be read
     * @throws GeneralSecurityException if its content is invalid
     */
    static SecurityProvider securityProvider(Options o) throws IOException, GeneralSecurityException {
        switch (o.security) {
        case BridgeSecurity.PLAIN:
            return BridgeSecurity.plain();
        case BridgeSecurity.TLS_CERTIFICATES: {
            char[] password = null;
            if (o.tlsKeyPasswordEnv != null) {
                String p = System.getenv(o.tlsKeyPasswordEnv);
                if (p == null) throw new GeneralSecurityException("the environment variable " + o.tlsKeyPasswordEnv + " is not set");
                password = p.toCharArray();
            }
            TlsCredentials c = TlsCredentials.fromFiles(Path.of(o.tlsCert), Path.of(o.tlsKey), password, Path.of(o.tlsCa));
            return BridgeSecurity.tlsCertificates(c, o.allowedClients.toArray(new String[0]));
        }
        default:
            return BridgeSecurity.tlsAnonymous();
        }
    }

    /**
     * Write a demo PKI: a root certificate, a server certificate for localhost and 127.0.0.1 that is also named
     * urn:php-java-bridge:demo:server, and a client certificate named php-client and urn:php-java-bridge:demo:client,
     * each valid for a year. The root's private key is not kept.
     * @param dir the directory, created if needed
     * @return the files written
     * @throws IOException if a file cannot be written
     * @throws GeneralSecurityException if a key or certificate cannot be created
     */
    static List<Path> makeDemoCertificates(Path dir) throws IOException, GeneralSecurityException {
        TlsSupport tls = InternalLibraries.get().tls();
        Instant from = Instant.now().minus(Duration.ofHours(1));
        Instant to = from.plus(Duration.ofDays(366));
        KeyMaterial root = tls.createCertificate("php-java-bridge demo root", null, TlsSupport.Usage.CA, List.of(), from, to);
        KeyMaterial server = tls.createCertificate("localhost", root, TlsSupport.Usage.SERVER,
                                                   List.of("localhost", "127.0.0.1", "::1", "urn:php-java-bridge:demo:server"),
                                                   from, to);
        KeyMaterial client = tls.createCertificate("php-client", root, TlsSupport.Usage.CLIENT,
                                                   List.of("urn:php-java-bridge:demo:client"), from, to);
        Files.createDirectories(dir);
        List<Path> files = new ArrayList<>();
        files.add(write(dir.resolve("ca.pem"), pem(root.certificate())));
        files.add(write(dir.resolve("server.pem"), pem(server.chain())));
        files.add(write(dir.resolve("server-key.pem"), pem("PRIVATE KEY", server.privateKey().getEncoded())));
        files.add(write(dir.resolve("client.pem"), pem(client.chain())));
        files.add(write(dir.resolve("client-key.pem"), pem("PRIVATE KEY", client.privateKey().getEncoded())));
        return files;
    }

    private static Path write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.US_ASCII);
        return file;
    }

    static String pem(X509Certificate cert) throws CertificateEncodingException {
        return pem("CERTIFICATE", cert.getEncoded());
    }

    static String pem(List<X509Certificate> chain) throws CertificateEncodingException {
        StringBuilder b = new StringBuilder();
        for (X509Certificate c : chain) b.append(pem(c));
        return b.toString();
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
               + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
               + "\n-----END " + type + "-----\n";
    }

    /**
     * Start the bridge and serve until the process is stopped (Ctrl+C or a service stop).
     * Exits with status 1 if the arguments are invalid or the server cannot start.
     * @param args the command line, see {@link #usage(PrintStream)}
     */
    public static void main(String[] args) {
        Options o;
        try {
            o = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Run with --help for usage.");
            System.exit(1);
            return;
        }
        if (o.help) {
            usage(System.out);
            return;
        }
        if (o.version) {
            System.out.println(Util.VERSION);
            return;
        }
        if (o.demoCertificates != null) {
            try {
                for (Path f : makeDemoCertificates(Path.of(o.demoCertificates))) System.out.println("Wrote " + f);
                System.out.println("Demo certificates only: the root's private key was not kept, and the client key is unencrypted.");
            } catch (IOException | GeneralSecurityException | IllegalStateException e) {
                System.err.println("Cannot write the demo certificates: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        BridgeServer.Builder b = BridgeServer.builder()
            .address(o.address)
            .port(o.port)
            .threads(o.threads)
            .logLevel(o.logLevel);
        if (o.logFile != null) {
            try {
                b.logger(FileLogger.open(o.logFile));
            } catch (IOException e) {
                System.err.println("Cannot open log file " + o.logFile + ": " + e.getMessage());
                System.exit(1);
                return;
            }
        }

        BridgeServer server;
        try {
            b.security(securityProvider(o));
            server = b.start();
        } catch (IOException | GeneralSecurityException e) {
            System.err.println("Cannot start the bridge on " + o.address.getHostAddress() + ":" + o.port + ": " + e.getMessage());
            System.exit(1);
            return;
        } catch (IllegalStateException e) {
            System.err.println("Cannot start the bridge: " + e.getMessage());
            System.exit(1);
            return;
        }
        if (!o.address.isLoopbackAddress() && !BridgeSecurity.TLS_CERTIFICATES.equals(o.security)) {
            System.err.println("WARNING: listening on " + o.address.getHostAddress() + " with --security " + o.security
                               + ". Anyone who can reach this port can run any Java code in this JVM.");
        }
        System.err.println("PHP/Java Bridge " + Util.VERSION + " listening on " + o.address.getHostAddress() + ":"
                           + server.getPort() + " (security " + o.security + ", threads " + o.threads
                           + ", log level " + o.logLevel + ")");

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "JavaBridge-shutdown"));
        try {
            server.awaitTermination();
        } catch (InterruptedException e) {
            server.stop();
        }
    }
}
