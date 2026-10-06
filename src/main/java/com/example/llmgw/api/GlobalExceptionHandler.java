package com.example.llmgw.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final OpenAiErrorWriter writer;

    public GlobalExceptionHandler(OpenAiErrorWriter writer) {
        this.writer = writer;
    }

    @ExceptionHandler(GatewayException.class)
    public Map<String, Object> handleGateway(GatewayException e, HttpServletRequest request,
                                            HttpServletResponse response) {
        response.setStatus(e.status().value());
        request.setAttribute("gateway.errorCode", e.code());
        if (e.retryAfterMillis() > 0) {
            // Rounded up to whole seconds because Retry-After is second-granular by spec.
            response.setHeader("Retry-After", String.valueOf(Math.max(1, (e.retryAfterMillis() + 999) / 1000)));
        }
        return writer.body(e);
    }

    @ExceptionHandler(IllegalStateException.class)
    public Map<String, Object> handleIllegalState(IllegalStateException e, HttpServletResponse response) {
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return writer.body(e.getMessage(), "api_error", "internal_error");
    }
}
