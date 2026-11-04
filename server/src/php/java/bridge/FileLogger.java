/*-*- mode: Java; tab-width:8 -*-*/

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

package php.java.bridge;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The bridge's built-in logger: timestamped lines on a PrintStream (System.err by default, or a file).
 */
public final class FileLogger implements ILogger {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("MMM dd HH:mm:ss", Locale.ENGLISH);

    private final PrintStream out;

    /**
     * Log to the given stream.
     * @param out the stream, for example System.err
     */
    public FileLogger(PrintStream out) {
        if (out == null) throw new NullPointerException("out");
        this.out = out;
    }

    /**
     * Log to a file, appending to it.
     * @param file the log file
     * @return the logger
     * @throws IOException if the file cannot be opened
     */
    public static FileLogger open(String file) throws IOException {
        return new FileLogger(new PrintStream(new FileOutputStream(file, true), true, StandardCharsets.UTF_8));
    }

    private void line(String label, String msg) {
        String s = LocalDateTime.now().format(FORMAT) + " " + Util.EXTENSION_NAME + " " + label + ": " + msg;
        synchronized (out) {
            out.println(s);
            out.flush();
        }
    }

    /** {@inheritDoc} */
    public void printStackTrace(Throwable t) {
        if (Util.logLevel > 0) {
            if (t instanceof Error) {
                line("FATAL", "An error occurred: " + t);
            } else if (Util.logLevel > 1) {
                line("ERROR", "An exception occurred: " + t);
            } else {
                return;
            }
            synchronized (out) {
                t.printStackTrace(out);
                out.flush();
            }
        }
    }

    /** {@inheritDoc} */
    public void log(int level, String msg) {
        String label;
        switch (level) {
        case FATAL: label = "FATAL"; break;
        case ERROR: label = "ERROR"; break;
        case INFO:  label = "INFO "; break;
        case DEBUG: label = "DEBUG"; break;
        default:    label = String.valueOf(level); break;
        }
        line(label, msg);
    }

    /** {@inheritDoc} */
    public void warn(String msg) {
        line("WARNING", msg);
    }

    public String toString() {
        return "FileLogger";
    }
}
