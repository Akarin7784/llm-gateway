package com.example.llmgw.upstream;

import com.example.llmgw.protocol.UpstreamResult;
import com.example.llmgw.protocol.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Talks the OpenAI dialect, which most vendors expose natively. Requests are forwarded as the
 * original client JSON with only {@code model}/{@code stream} rewritten, so fields the gateway does
 * not model (tools, response_format, n, ...) reach the vendor intact.
 */
@Component
public class OpenAiCompatibleAdapter implements UpstreamAdapter {

    private static final String DATA_PREFIX = "data:";

    private final ObjectMapper mapper;

    public OpenAiCompatibleAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String vendor() {
        return "openai-compatible";
    }

    @Override
    public UpstreamResult complete(UpstreamTarget target, JsonNode payload) throws UpstreamException {
        ObjectNode body = prepare(payload, target, false);
        HttpRequest request = newRequest(target, body, false);
        long startedAt = System.nanoTime();
        try {
            HttpResponse<String> response = client(target)
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsedMillis = elapsedMillis(startedAt);
            int status = response.statusCode();
            if (status / 100 != 2) {
                throw new UpstreamException(target.name(), UpstreamException.forStatus(status), status,
                        "upstream responded " + status + ": " + truncate(response.body()));
            }
            JsonNode parsed = mapper.readTree(response.body());
            return new UpstreamResult(parsed, readUsage(parsed), elapsedMillis);
        } catch (IOException e) {
            throw new UpstreamException(target.name(), UpstreamException.Kind.UNAVAILABLE, describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(target.name(), UpstreamException.Kind.CANCELLED, "interrupted", e);
        }
    }

    @Override
    public void stream(UpstreamTarget target, JsonNode payload, StreamCallback callback,
                       StreamAbortHandle abortHandle) throws UpstreamException {
        ObjectNode body = prepare(payload, target, true);
        HttpRequest request = newRequest(target, body, true);
        try {
            HttpResponse<InputStream> response = client(target)
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status / 100 != 2) {
                String detail = readAll(response.body());
                throw new UpstreamException(target.name(), UpstreamException.forStatus(status), status,
                        "upstream responded " + status + ": " + truncate(detail));
            }
            InputStream raw = response.body();
            if (!abortHandle.bind(raw)) {
                closeQuietly(raw);
                throw new UpstreamException(target.name(), UpstreamException.Kind.CANCELLED,
                        "aborted before the response body was read", null);
            }

            Usage[] lastUsage = new Usage[]{Usage.ZERO};
            String[] finishReason = new String[]{null};
            boolean[] firstTokenSeen = new boolean[]{false};

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (abortHandle.aborted()) {
                        break;
                    }
                    if (!line.startsWith(DATA_PREFIX)) {
                        // Vendor-level keepalive comments carry nothing to forward.
                        continue;
                    }
                    String data = line.substring(DATA_PREFIX.length()).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode chunk = mapper.readTree(data);
                    if (!firstTokenSeen[0]) {
                        firstTokenSeen[0] = true;
                        callback.onFirstToken();
                    }
                    Usage chunkUsage = readUsage(chunk);
                    if (chunkUsage.totalTokens() > 0) {
                        lastUsage[0] = chunkUsage;
                    }
                    String reason = readFinishReason(chunk);
                    if (reason != null) {
                        finishReason[0] = reason;
                    }
                    String delta = readDelta(chunk);
                    if (delta != null && !delta.isEmpty()) {
                        callback.onDelta(delta);
                    }
                }
            } finally {
                abortHandle.release();
            }
            if (abortHandle.aborted()) {
                throw new UpstreamException(target.name(), UpstreamException.Kind.CANCELLED,
                        "stream aborted", null);
            }
            callback.onCompleted(lastUsage[0], finishReason[0]);
        } catch (IOException e) {
            throw new UpstreamException(target.name(), UpstreamException.Kind.UNAVAILABLE, describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(target.name(), UpstreamException.Kind.CANCELLED, "interrupted", e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Peer already gone.
        }
    }

    private ObjectNode prepare(JsonNode payload, UpstreamTarget target, boolean streaming) {
        ObjectNode body = payload.deepCopy();
        body.put("model", target.model());
        body.put("stream", streaming);
        if (streaming) {
            // Ask for a final usage chunk; without it a streamed call has no billable token count.
            ObjectNode options = body.objectNode();
            options.put("include_usage", true);
            body.set("stream_options", options);
        } else {
            body.remove("stream_options");
        }
        return body;
    }

    private HttpRequest newRequest(UpstreamTarget target, ObjectNode body, boolean streaming) {
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise upstream request", e);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target.completionsUri()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + target.apiKey())
                // HttpClient's request timeout bounds the wait for the response, not the body: for a
                // streamed call that is exactly the time-to-first-token budget.
                .timeout(streaming ? target.firstTokenTimeout() : target.readTimeout());
        if (streaming) {
            builder.header("Accept", "text/event-stream");
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
    }

    private HttpClient client(UpstreamTarget target) {
        return HttpClients.forConnectTimeout(target.connectTimeout());
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private static Usage readUsage(JsonNode node) {
        JsonNode usage = node.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return Usage.ZERO;
        }
        return new Usage(usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("total_tokens").asInt(0));
    }

    private static String readDelta(JsonNode chunk) {
        JsonNode choices = chunk.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        return choices.get(0).path("delta").path("content").asText(null);
    }

    private static String readFinishReason(JsonNode chunk) {
        JsonNode choices = chunk.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode reason = choices.get(0).path("finish_reason");
        return reason.isTextual() ? reason.asText() : null;
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + message;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
