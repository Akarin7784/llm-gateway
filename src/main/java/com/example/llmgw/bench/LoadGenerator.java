package com.example.llmgw.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Load generator for the gateway, written against the JDK HTTP client so the measuring instrument is
 * not the thing being measured: wrk/ab put their own connection model between the numbers and the
 * gateway. One blocking request per virtual thread keeps every sample an honest per-request latency
 * rather than an async queueing artifact.
 *
 * Run one target per JVM invocation and compare invocations. Measuring both legs in one process would
 * let that process's own garbage collection show up as "gateway overhead".
 *
 *   java -cp "$CP" com.example.llmgw.bench.LoadGenerator --url ... --concurrency 64 --requests 2000
 */
public final class LoadGenerator {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private String url;
    private String token;
    private String model;
    private boolean stream;
    private long timeoutMillis;
    private int concurrency;
    private int mockChunks = 1;
    private long mockIntervalMillis;
    private HttpClient client;

    public static void main(String[] args) {
        new LoadGenerator().execute(parseArgs(args));
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[++i]);
            }
        }
        return options;
    }

    private void execute(Map<String, String> options) {
        url = options.getOrDefault("url", "http://127.0.0.1:8080/v1/chat/completions");
        token = options.getOrDefault("token", "sk-bench-dev");
        model = options.getOrDefault("model", "gw-cheap");
        concurrency = Integer.parseInt(options.getOrDefault("concurrency", "32"));
        int requests = Integer.parseInt(options.getOrDefault("requests", "500"));
        int warmup = Integer.parseInt(options.getOrDefault("warmup", "50"));
        stream = Boolean.parseBoolean(options.getOrDefault("stream", "false"));
        timeoutMillis = Long.parseLong(options.getOrDefault("timeout-ms", "30000"));
        // Sustained streams, not instant ones: a gateway for LLM traffic is judged by how many
        // long-lived responses it can hold open, and a zero-length stream measures request rate instead.
        mockChunks = Integer.parseInt(options.getOrDefault("mock-chunks", "1"));
        mockIntervalMillis = Long.parseLong(options.getOrDefault("mock-interval-ms", "0"));
        if (stream && mockChunks == 1) {
            mockChunks = 50;
        }

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        // Warm the JIT, the pool and the upstream keep-alive path before anything is measured.
        drive(Math.min(warmup, requests), 0);
        LatencyStats stats = drive(requests, warmup);

        Map<String, Object> report = stats.report();
        report.put("target", url);
        report.put("concurrency", concurrency);
        report.put("stream", stream);
        report.put("mock_chunks", mockChunks);
        report.put("mock_interval_ms", mockIntervalMillis);
        report.put("approx_stream_seconds", mockChunks * mockIntervalMillis / 1000.0);
        try {
            System.out.println(MAPPER.writeValueAsString(report));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private LatencyStats drive(int requests, int startId) {
        ConcurrentLinkedQueue<long[]> samples = new ConcurrentLinkedQueue<>();
        AtomicInteger errors = new AtomicInteger();
        AtomicLong firstErrorAt = new AtomicLong(-1);
        List<String> errorKinds = new ArrayList<>();
        AtomicInteger nextRequestId = new AtomicInteger(startId);
        int endId = startId + requests;
        long startedAt = System.nanoTime();

        // Closed loop: exactly `concurrency` requests in flight, each worker pulling the next id when it
        // finishes. Launching all requests at once would measure this client's ability to open sockets,
        // not the gateway's behaviour under a bounded offered load.
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < Math.min(concurrency, requests); i++) {
            workers.add(Thread.ofVirtual().unstarted(() -> {
                int requestId;
                while ((requestId = nextRequestId.getAndIncrement()) < endId) {
                    try {
                        samples.add(once(requestId));
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        firstErrorAt.compareAndSet(-1, System.nanoTime() - startedAt);
                        recordErrorKind(errorKinds, e);
                    }
                }
            }));
        }
        workers.forEach(Thread::start);
        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new LatencyStats(samples, errors.get(), System.nanoTime() - startedAt, errorKinds,
                firstErrorAt.get(), requests, stream);
    }

    private static void recordErrorKind(List<String> errorKinds, Exception e) {
        synchronized (errorKinds) {
            if (errorKinds.size() < 5) {
                errorKinds.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /** Returns {totalMillis, timeToFirstByteOrTokenMillis}. */
    private long[] once(int requestId) throws Exception {
        // Distinct content per request: an identical prompt would be answered from cache, and the number
        // would then describe the cache rather than the forwarding path.
        String body = "{\"model\":\"" + model + "\",\"temperature\":0.7,"
                + "\"_mock\":{\"chunks\":" + mockChunks + ",\"chunk_delay_ms\":" + mockIntervalMillis
                + ",\"first_token_delay_ms\":0},"
                + "\"stream\":" + stream + ","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"bench request " + requestId + "\"}]}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        long startedAt = System.nanoTime();
        if (!stream) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long total = elapsedMillis(startedAt);
            if (response.statusCode() / 100 != 2) {
                throw new IOException("status " + response.statusCode() + " " + trim(response.body()));
            }
            return new long[]{total, total};
        }

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            String detail = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("status " + response.statusCode() + " " + trim(detail));
        }
        long firstToken = -1;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (firstToken < 0 && line.startsWith("data:") && !line.startsWith("data: [DONE]")) {
                    firstToken = elapsedMillis(startedAt);
                }
                if (line.startsWith("data: [DONE]")) {
                    break;
                }
            }
        }
        long total = elapsedMillis(startedAt);
        return new long[]{total, Math.max(0, firstToken)};
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        String flattened = value.replaceAll("\\s+", " ");
        return flattened.length() <= 120 ? flattened : flattened.substring(0, 120);
    }

    private static final class LatencyStats {
        private final List<Long> latencies = new ArrayList<>();
        private final List<Long> ttfts = new ArrayList<>();
        private final int errors;
        private final double seconds;
        private final List<String> errorKinds;
        private final long firstErrorNanos;
        private final int requests;
        private final boolean streaming;

        LatencyStats(ConcurrentLinkedQueue<long[]> samples, int errors, long durationNanos,
                     List<String> errorKinds, long firstErrorNanos, int requests, boolean streaming) {
            for (long[] sample : samples) {
                latencies.add(sample[0]);
                ttfts.add(sample[1]);
            }
            this.errors = errors;
            this.seconds = durationNanos / 1_000_000_000.0;
            this.errorKinds = errorKinds;
            this.firstErrorNanos = firstErrorNanos;
            this.requests = requests;
            this.streaming = streaming;
        }

        Map<String, Object> report() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("requests", requests);
            view.put("completed", latencies.size());
            view.put("errors", errors);
            view.put("rps", round(seconds == 0 ? 0 : latencies.size() / seconds));
            view.put("p50_ms", percentile(latencies, 50));
            view.put("p95_ms", percentile(latencies, 95));
            view.put("p99_ms", percentile(latencies, 99));
            view.put("max_ms", latencies.isEmpty() ? 0 : Collections.max(latencies));
            if (streaming) {
                view.put("ttft_p50_ms", percentile(ttfts, 50));
                view.put("ttft_p99_ms", percentile(ttfts, 99));
            }
            if (errors > 0) {
                view.put("first_error_at_ms", round(firstErrorNanos / 1_000_000.0));
                view.put("error_samples", errorKinds);
            }
            return view;
        }

        private static long percentile(List<Long> values, int percent) {
            if (values.isEmpty()) {
                return -1;
            }
            List<Long> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            int index = (int) Math.ceil(percent / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }

        private static double round(double value) {
            return Math.round(value * 100) / 100.0;
        }
    }
}
