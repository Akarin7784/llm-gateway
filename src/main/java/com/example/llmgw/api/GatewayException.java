package com.example.llmgw.api;

import org.springframework.http.HttpStatus;

public class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String errorType;
    private final long retryAfterMillis;

    public GatewayException(HttpStatus status, String code, String errorType, String message) {
        this(status, code, errorType, message, 0);
    }

    public GatewayException(HttpStatus status, String code, String errorType, String message,
                            long retryAfterMillis) {
        super(message);
        this.status = status;
        this.code = code;
        this.errorType = errorType;
        this.retryAfterMillis = retryAfterMillis;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String errorType() {
        return errorType;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public static GatewayException unauthenticated(String message) {
        return new GatewayException(HttpStatus.UNAUTHORIZED, "invalid_api_key", "authentication_error", message);
    }

    public static GatewayException badRequest(String message) {
        return new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request", "invalid_request_error", message);
    }

    public static GatewayException modelNotFound(String model) {
        return new GatewayException(HttpStatus.NOT_FOUND, "model_not_found", "invalid_request_error",
                "no upstream configured for model '" + model + "'");
    }

    public static GatewayException rateLimited(String message, long retryAfterMillis) {
        return new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded", "rate_limit_error",
                message, retryAfterMillis);
    }

    public static GatewayException upstreamFailure(String message, Throwable cause) {
        GatewayException exception = new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_failure",
                "api_error", message);
        exception.initCause(cause);
        return exception;
    }
}
