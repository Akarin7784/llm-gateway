package com.example.llmgw.obs;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stamps the instant a request arrives, before any other filter runs.
 *
 * Under many concurrent streams the gap between "client sent it" and "the gateway started working on
 * it" is a large share of time-to-first-token, and without this stamp that time is invisible: every
 * internal timer begins further down the stack. Splitting admission latency from forwarding latency is
 * what tells you whether to add capacity or optimise code.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestArrivalFilter extends OncePerRequestFilter {

    public static final String ARRIVAL_ATTRIBUTE = "gateway.arrivalNanos";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(ARRIVAL_ATTRIBUTE, System.nanoTime());
        chain.doFilter(request, response);
    }
}
