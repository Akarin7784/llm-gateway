package com.example.llmgw.auth;

import com.example.llmgw.api.GatewayException;
import com.example.llmgw.api.OpenAiErrorWriter;
import com.example.llmgw.obs.GatewayMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String TENANT_ATTRIBUTE = "gateway.tenant";
    public static final String REQUEST_ID_ATTRIBUTE = "gateway.requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final String BEARER_PREFIX = "Bearer ";

    private final TenantRegistry tenants;
    private final OpenAiErrorWriter errorWriter;
    private final GatewayMetrics metrics;

    public ApiKeyAuthFilter(TenantRegistry tenants, OpenAiErrorWriter errorWriter, GatewayMetrics metrics) {
        this.tenants = tenants;
        this.errorWriter = errorWriter;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String apiKey = header != null && header.startsWith(BEARER_PREFIX)
                ? header.substring(BEARER_PREFIX.length()).trim()
                : null;

        Tenant tenant;
        try {
            tenant = tenants.authenticate(apiKey);
        } catch (GatewayException e) {
            metrics.reject("auth");
            errorWriter.write(e, response);
            return;
        }

        request.setAttribute(TENANT_ATTRIBUTE, tenant);
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        request.setAttribute(REQUEST_ID_ATTRIBUTE,
                requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId);
        chain.doFilter(request, response);
    }
}
