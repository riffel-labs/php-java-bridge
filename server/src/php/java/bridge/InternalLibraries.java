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
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

import php.java.bridge.spi.BridgeInternals;

/**
 * Loads the bridge's internal libraries (the internal jar, Jackson and Bouncy Castle) from a directory of their
 * own, in a class loader that sees only the JDK and the {@code php.java.bridge.spi} interfaces. The classes PHP
 * can use are looked up through the context class loader, which never sees these libraries, so PHP code (and
 * the jars in server/lib) can bring other versions of the same libraries without a conflict.
 * <p>
 * The directory is the system property {@value #PROPERTY} if set, otherwise {@code internal/} next to the
 * server jar. This keeps libraries apart; it is not a security boundary.
 */
final class InternalLibraries {

    /** System property naming the directory of the internal jars. */
    static final String PROPERTY = "php.java.bridge.internal";

    private static final String IMPLEMENTATION = "php.java.bridge.internal.InternalLibrariesImpl";
    private static final String SPI_PACKAGE = "php.java.bridge.spi.";

    private static volatile BridgeInternals instance;

    private InternalLibraries() {}

    /**
     * @return the internal libraries, loaded on first use
     * @throws IllegalStateException if they cannot be found or loaded
     */
    static BridgeInternals get() {
        BridgeInternals i = instance;
        if (i == null) {
            synchronized (InternalLibraries.class) {
                i = instance;
                if (i == null) instance = i = load(locate());
            }
        }
        return i;
    }

    /** @return the directory the internal jars are loaded from */
    static Path locate() {
        String dir = System.getProperty(PROPERTY);
        if (dir != null && !dir.isEmpty()) return Path.of(dir);
        CodeSource source = InternalLibraries.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("Cannot tell where the bridge was loaded from; set -D" + PROPERTY
                                            + "=DIR to the directory with the internal jars");
        }
        try {
            Path location = Path.of(source.getLocation().toURI());
            Path base = Files.isDirectory(location) ? location : location.getParent();
            return base.resolve("internal");
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot locate the internal jars; set -D" + PROPERTY + "=DIR", e);
        }
    }

    private static BridgeInternals load(Path dir) {
        List<URL> jars = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path f : files) jars.add(f.toUri().toURL());
        } catch (IOException e) {
            throw missing(dir, e);
        }
        if (jars.isEmpty()) throw missing(dir, null);
        jars.sort((a, b) -> a.toString().compareTo(b.toString()));

        ClassLoader parent = new SpiOnly(InternalLibraries.class.getClassLoader());
        URLClassLoader loader = new URLClassLoader("php-java-bridge-internal", jars.toArray(new URL[0]), parent);
        try {
            Class<?> c = Class.forName(IMPLEMENTATION, true, loader);
            return (BridgeInternals) c.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            throw new IllegalStateException("Cannot load the internal libraries from " + dir + ": " + e, e);
        }
    }

    private static IllegalStateException missing(Path dir, Throwable cause) {
        return new IllegalStateException("The internal libraries are missing: no jars in " + dir.toAbsolutePath()
                                         + ". They ship in server/internal/ next to the server jar; or set -D"
                                         + PROPERTY + "=DIR.", cause);
    }

    /** Parent of the internal class loader: the JDK, plus the interfaces in php.java.bridge.spi. */
    private static final class SpiOnly extends ClassLoader {
        static {
            registerAsParallelCapable();
        }

        private final ClassLoader core;

        SpiOnly(ClassLoader core) {
            super("php-java-bridge-spi", ClassLoader.getPlatformClassLoader());
            this.core = core;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(SPI_PACKAGE)) return core.loadClass(name);
            return super.loadClass(name, resolve);
        }
    }
}
