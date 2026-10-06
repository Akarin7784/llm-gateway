package com.example.llmgw.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A completed non-streaming exchange. {@code body} is the upstream payload forwarded verbatim;
 * {@code upstreamMillis} is measured inside the adapter so the controller can subtract it from the
 * end-to-end latency to derive the gateway's own overhead.
 */
public record UpstreamResult(JsonNode body, Usage usage, long upstreamMillis) {
}
