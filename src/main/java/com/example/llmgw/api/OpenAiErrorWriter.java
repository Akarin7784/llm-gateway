package com.example.llmgw.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Renders failures in the OpenAI error envelope so unmodified SDK clients can still parse them.
 * Shared by the advice and by the auth filter, whose exceptions never reach {@code @ControllerAdvice}.
 */
@Component
public class OpenAiErrorWriter {

    private final ObjectMapper mapper;

    public OpenAiErrorWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> body(String message, String type, String code) {
        return Map.of("error", Map.of("message", message, "type", type, "code", code));
    }

    public Map<String, Object> body(GatewayException e) {
        return body(e.getMessage(), e.errorType(), e.code());
    }

    public void write(GatewayException e, HttpServletResponse response) throws IOException {
        response.setStatus(e.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), body(e));
    }
}
