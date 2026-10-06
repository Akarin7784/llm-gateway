package com.example.llmgw.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stand-in vendor with deterministic fault injection, so streaming, timeout, cancellation and
 * degradation can be load-tested without spending tokens or depending on a third party.
 *
 * Run: mvn -q exec:java -Dexec.mainClass=com.example.llmgw.mock.MockUpstreamServer -Dexec.args="9090"
 *
 * Requests may carry a {@code _mock} object to override behaviour per call:
 * {"chunks":8,"first_token_delay_ms":120,"chunk_delay_ms":30,"error_rate":0.5,"status":503,"stall_ms":5000}
 */
public final class MockUpstreamServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong completedStreams = new AtomicLong();
    private final AtomicLong abortedStreams = new AtomicLong();
    private final AtomicLong activeStreams = new AtomicLong();

    private volatile double globalErrorRate;
    private volatile int defaultChunkCount = 6;
    private volatile long defaultChunkDelayMillis = 25;

    private final int port;

    private MockUpstreamServer(int port) {
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        MockUpstreamServer server = new MockUpstreamServer(port);
        if (args.length > 1) {
            // Per-instance latency profile: two mocks can then be scored against each other.
            server.defaultChunkDelayMillis = Long.parseLong(args[1]);
        }
        server.start();
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/v1/chat/completions", this::handleCompletions);
        server.createContext("/stats", this::handleStats);
        server.createContext("/control", this::handleControl);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("mock upstream listening on http://127.0.0.1:" + port);
        System.out.println("  POST /v1/chat/completions  (supports stream + _mock fault injection)");
        System.out.println("  GET  /stats                (active / completed / aborted streams)");
        System.out.println("  POST /control              {\"error_rate\":1.0}");
    }

    private void handleCompletions(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        JsonNode body;
        try (InputStream in = exchange.getRequestBody()) {
            body = MAPPER.readTree(in);
        }

        JsonNode mock = body.path("_mock");
        double errorRate = mock.path("error_rate").asDouble(globalErrorRate);
        if (mock.hasNonNull("status") || errorRate > 0 && ThreadLocalRandom.current().nextDouble() < errorRate) {
            int status = mock.path("status").asInt(503);
            byte[] payload = "{\"error\":{\"message\":\"injected failure\",\"type\":\"server_error\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            return;
        }

        boolean streaming = body.path("stream").asBoolean(false);
        int chunks = mock.path("chunks").asInt(defaultChunkCount);
        long firstTokenDelay = mock.path("first_token_delay_ms").asLong(0);
        long chunkDelay = mock.path("chunk_delay_ms").asLong(defaultChunkDelayMillis);
        long stall = mock.path("stall_ms").asLong(0);
        long hangAfterFirstToken = mock.path("hang_after_first_token_ms").asLong(0);
        String model = body.path("model").asText("gw-demo");

        if (streaming) {
            stream(exchange, model, chunks, firstTokenDelay, chunkDelay, stall, hangAfterFirstToken, mock);
        } else {
            block(exchange, model, body.path("messages").toString().length(),
                    firstTokenDelay + chunks * chunkDelay, completionTokens(mock, chunks));
        }
    }

    private static int completionTokens(JsonNode mock, int fallback) {
        return Math.max(1, mock.path("usage_tokens").asInt(fallback));
    }

    private void stream(HttpExchange exchange, String model, int chunks, long firstTokenDelay, long chunkDelay,
                        long stallMillis, long hangAfterFirstToken, JsonNode mock) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        activeStreams.incrementAndGet();

        boolean aborted = true;
        long created = System.currentTimeMillis() / 1000;
        try (OutputStream out = exchange.getResponseBody()) {
            sleep(firstTokenDelay);
            for (int i = 0; i < chunks; i++) {
                write(out, sse(chunk(model, created, "tok" + i + " ", null)));
                if (i == 0 && hangAfterFirstToken > 0) {
                    // A vendor that has nothing to say still believes the connection is alive, so it
                    // keeps probing it with SSE comments. Nothing here reaches the gateway's forwarding
                    // path, but each write tells the vendor the instant its peer disappears -- which is
                    // what makes "time until the gateway releases us" measurable.
                    long until = System.currentTimeMillis() + hangAfterFirstToken;
                    while (System.currentTimeMillis() < until) {
                        sleep(50);
                        write(out, ": vendor keepalive\n\n");
                    }
                }
                sleep(chunkDelay);
            }
            sleep(stallMillis);
            write(out, sse(chunk(model, created, null, mock)));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            aborted = false;
            completedStreams.incrementAndGet();
        } catch (IOException e) {
            // Peer vanished mid-flight: exactly what an abandoned-stream metric must capture.
            abortedStreams.incrementAndGet();
        } finally {
            activeStreams.decrementAndGet();
            if (aborted) {
                System.out.println("[mock:" + port + "] peer gone before completion, active=" + activeStreams.get());
            }
        }
    }

    private void block(HttpExchange exchange, String model, int promptChars, long totalDelay,
                       int completionTokens) throws IOException {
        sleep(totalDelay);
        int promptTokens = Math.max(1, promptChars / 4);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", "mock response from port " + port);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "chatcmpl-mock-" + Long.toHexString(System.nanoTime()));
        payload.put("object", "chat.completion");
        payload.put("created", System.currentTimeMillis() / 1000);
        payload.put("model", model);
        payload.put("choices", java.util.List.of(choice));
        payload.put("usage", usage);

        byte[] bytes = MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Map<String, Object> chunk(String model, long created, String delta, JsonNode mock) {
        Map<String, Object> deltaView = new LinkedHashMap<>();
        if (delta != null) {
            deltaView.put("content", delta);
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", deltaView);
        choice.put("finish_reason", delta == null ? "stop" : null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "chatcmpl-mock-" + port);
        payload.put("object", "chat.completion.chunk");
        payload.put("created", created);
        payload.put("model", model);
        payload.put("choices", java.util.List.of(choice));

        if (delta == null && mock != null && mock.path("emit_usage").asBoolean(true)) {
            int completionTokens = mock.path("chunks").asInt(defaultChunkCount);
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", 8);
            usage.put("completion_tokens", completionTokens);
            usage.put("total_tokens", 8 + completionTokens);
            payload.put("usage", usage);
        }
        return payload;
    }

    private void handleStats(HttpExchange exchange) throws IOException {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("requests", requests.get());
        stats.put("active_streams", activeStreams.get());
        stats.put("completed_streams", completedStreams.get());
        stats.put("aborted_streams", abortedStreams.get());
        writeJson(exchange, 200, stats);
    }

    private void handleControl(HttpExchange exchange) throws IOException {
        JsonNode body;
        try (InputStream in = exchange.getRequestBody()) {
            body = MAPPER.readTree(in);
        }
        if (body.hasNonNull("error_rate")) {
            globalErrorRate = body.path("error_rate").asDouble();
        }
        if (body.hasNonNull("chunks")) {
            defaultChunkCount = body.path("chunks").asInt();
        }
        if (body.hasNonNull("chunk_delay_ms")) {
            defaultChunkDelayMillis = body.path("chunk_delay_ms").asLong();
        }
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("error_rate", globalErrorRate);
        ack.put("chunks", defaultChunkCount);
        ack.put("chunk_delay_ms", defaultChunkDelayMillis);
        writeJson(exchange, 200, ack);
    }

    private static String sse(Map<String, Object> chunk) throws IOException {
        return "data: " + MAPPER.writeValueAsString(chunk) + "\n\n";
    }

    private static void write(OutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void writeJson(HttpExchange exchange, int status, Map<String, Object> value) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
