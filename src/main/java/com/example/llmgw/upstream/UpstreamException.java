package com.example.llmgw.upstream;

/** Classifies an upstream failure so the router can decide whether degrading is safe. */
public class UpstreamException extends Exception {

    public enum Kind {
        TIMEOUT,
        UNAVAILABLE,
        RETRYABLE_STATUS,
        CLIENT_ERROR,
        PROTOCOL,
        CANCELLED;

        public boolean retryable() {
            return this == TIMEOUT || this == UNAVAILABLE || this == RETRYABLE_STATUS;
        }
    }

    private final String upstream;
    private final Kind kind;
    private final int statusCode;

    public UpstreamException(String upstream, Kind kind, int statusCode, String message) {
        super(message);
        this.upstream = upstream;
        this.kind = kind;
        this.statusCode = statusCode;
    }

    public UpstreamException(String upstream, Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.upstream = upstream;
        this.kind = kind;
        this.statusCode = 0;
    }

    public String upstream() {
        return upstream;
    }

    public Kind kind() {
        return kind;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return kind.retryable();
    }

    public static Kind forStatus(int status) {
        if (status == 429 || status >= 500) {
            return Kind.RETRYABLE_STATUS;
        }
        if (status >= 400) {
            return Kind.CLIENT_ERROR;
        }
        return Kind.PROTOCOL;
    }
}
