package com.example.llmgw.api;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serialises writes to a single SSE response, since the forwarding worker and the heartbeat both
 * emit on it.
 *
 * Uses a lock rather than {@code synchronized} deliberately: on JDK 21 a {@code synchronized} block
 * that blocks pins the carrier thread, which is exactly the cost the virtual-thread model is here
 * to avoid. JEP 491 lifts that in a later release, but the gateway targets the LTS.
 */
final class SseWriter {

    private final SseEmitter emitter;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed;

    SseWriter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    void data(String payload) {
        emit(() -> emitter.send(SseEmitter.event().data(payload)));
    }

    void heartbeat() {
        emit(() -> emitter.send(SseEmitter.event().comment("hb")));
    }

    private void emit(WriteOperation operation) {
        lock.lock();
        try {
            if (closed) {
                throw new ClientDisconnectedException(new IOException("response already completed"));
            }
            operation.run();
        } catch (IOException | IllegalStateException e) {
            closed = true;
            throw new ClientDisconnectedException(e);
        } finally {
            lock.unlock();
        }
    }

    void completeQuietly() {
        lock.lock();
        try {
            if (!closed) {
                closed = true;
                emitter.complete();
            }
        } catch (RuntimeException ignored) {
            closed = true;
        } finally {
            lock.unlock();
        }
    }

    boolean closed() {
        return closed;
    }

    private interface WriteOperation {
        void run() throws IOException;
    }
}
