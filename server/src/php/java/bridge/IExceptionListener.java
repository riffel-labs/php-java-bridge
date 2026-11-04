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

/**
 * Told about every Java exception the bridge returns to PHP as the result of a call, for example the
 * NumberFormatException thrown by <code>Integer.parseInt("x")</code>. These are ordinary results for PHP (it
 * receives them as JavaException), not failures of the bridge, so the bridge itself logs them only at the
 * verbose log levels. Bridge failures (protocol or connection errors) still go to the {@link ILogger}.
 * <p>
 * Called on the connection's thread; keep it quick.
 */
@FunctionalInterface
public interface IExceptionListener {

    /**
     * @param cause the exception thrown by the Java code (an InvocationTargetException is already unwrapped)
     * @param message the message PHP receives, for example
     *        <code>Invoke failed: [[c:Integer]]-&gt;parseInt((o:String)[o:String]). Cause: java.lang.NumberFormatException: ... VM: ...</code>
     */
    void exceptionReturned(Throwable cause, String message);
}
