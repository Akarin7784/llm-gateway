package com.example.llmgw.upstream;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Releases the upstream response body on demand.
 *
 * Interrupting the pump thread is not enough: the JDK HttpClient's blocking body stream does not
 * close the socket on interrupt, so an abandoned client would otherwise leave the vendor generating
 * into a live connection until its next data chunk. Closing the stream is what actually breaks the
 * connection, which is what makes the vendor stop.
 */
public final class StreamAbortHandle {

    private final AtomicReference<InputStream> openBody = new AtomicReference<>();
    private volatile boolean aborted;

    /** False when the exchange already lost its race against an abort and must not proceed. */
    boolean bind(InputStream body) {
        if (aborted) {
            return false;
        }
        openBody.set(body);
        return !aborted;
    }

    void release() {
        openBody.set(null);
    }

    public void abort() {
        aborted = true;
        InputStream body = openBody.getAndSet(null);
        if (body != null) {
            closeQuietly(body);
        }
    }

    public boolean aborted() {
        return aborted;
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // Already closed by the peer or by the exchange unwinding.
        }
    }
}
