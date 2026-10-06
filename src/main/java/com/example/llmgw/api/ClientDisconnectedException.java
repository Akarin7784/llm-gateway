package com.example.llmgw.api;

/** Raised when the downstream peer is gone, unwinding the upstream read loop as it propagates. */
final class ClientDisconnectedException extends RuntimeException {

    ClientDisconnectedException(Throwable cause) {
        super(cause);
    }
}
