package com.example.llmgw.billing;

import com.example.llmgw.config.GatewayProperties;
import com.example.llmgw.obs.GatewayMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Asynchronous, batched, never-blocking writer for the usage ledger.
 *
 * Three properties carry the design:
 *
 * <p><b>Non-blocking on purpose.</b> {@link BlockingQueue#offer} rather than {@code put}: when the
 * database slows down, a request thread must not be made to wait on a billing insert. Overflow goes to
 * a spill file instead of being discarded, so the queue bound governs latency, not durability.
 *
 * <p><b>Idempotent replay.</b> The ledger is keyed by request id and written with INSERT IGNORE, so
 * replaying the spill file after a crash cannot double-bill. Without that property the spill path would
 * be a liability rather than a recovery mechanism.
 *
 * <p><b>Drained on shutdown.</b> The drain loop keeps flushing until the queue is empty, bounded by a
 * timeout, and anything left over is spilled. Otherwise the last exchanges of every restart would be
 * missing from the ledger, and reconciliation would report a permanent phantom delta.
 */
@Component
public class UsageLedgerWriter {

    private static final Logger log = LoggerFactory.getLogger(UsageLedgerWriter.class);

    private final LedgerStore store;
    private final GatewayProperties properties;
    private final GatewayMetrics metrics;
    private final ObjectMapper mapper;
    private final BlockingQueue<LedgerEntry> queue;
    private final Path spillPath;
    private final boolean enabled;
    private final ReentrantLock spillLock = new ReentrantLock();

    private volatile boolean running = true;
    private Thread drainer;

    public UsageLedgerWriter(LedgerStore store, GatewayProperties properties, GatewayMetrics metrics,
                             ObjectMapper mapper, MeterRegistry registry) {
        this.store = store;
        this.properties = properties;
        this.metrics = metrics;
        this.mapper = mapper;
        GatewayProperties.Billing config = properties.getBilling();
        this.enabled = config.isEnabled();
        this.queue = new ArrayBlockingQueue<>(config.getQueueCapacity());
        this.spillPath = Path.of(config.getSpillFile());
        registry.gauge("gateway.ledger.queue_depth", queue, java.util.Collection::size);
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            return;
        }
        replaySpill();
        drainer = Thread.ofVirtual().name("ledger-drain").start(this::drainLoop);
    }

    public void record(LedgerEntry entry) {
        if (!enabled) {
            return;
        }
        if (!queue.offer(entry)) {
            spill(List.of(entry), "queue-full");
        }
    }

    private void drainLoop() {
        GatewayProperties.Billing config = properties.getBilling();
        List<LedgerEntry> batch = new ArrayList<>(config.getBatchSize());
        while (running || !queue.isEmpty()) {
            try {
                LedgerEntry first = queue.poll(config.getFlushInterval().toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                LedgerEntry next;
                while (batch.size() < config.getBatchSize() && (next = queue.poll()) != null) {
                    batch.add(next);
                }
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Reached only if the loop exited on interrupt with entries still queued.
        List<LedgerEntry> stranded = new ArrayList<>();
        queue.drainTo(stranded);
        flush(stranded);
    }

    private void flush(List<LedgerEntry> batch) {
        if (batch.isEmpty()) {
            return;
        }
        int rows = batch.size();
        long startedAt = System.nanoTime();
        try {
            store.insertLedger(batch);
            store.accumulateDaily(batch);
            metrics.ledgerWritten(rows, (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException e) {
            // Keep the rows rather than the illusion of having written them.
            log.warn("ledger batch of {} rows failed to write ({}), spilling", rows, e.toString());
            spill(batch, "write-failed");
        }
        batch.clear();
    }

    @PreDestroy
    void stop() {
        if (!enabled) {
            return;
        }
        running = false;
        if (drainer == null) {
            return;
        }
        // Let the loop flush what it holds and exit on its own: interrupting first would turn a clean
        // shutdown into a spill of rows that could still have been written.
        long timeoutMillis = properties.getBilling().getShutdownDrainTimeout().toMillis();
        try {
            drainer.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (drainer.isAlive()) {
            log.warn("ledger drain did not finish within {}ms", timeoutMillis);
            drainer.interrupt();
        }
        List<LedgerEntry> leftover = new ArrayList<>();
        queue.drainTo(leftover);
        flush(leftover);
    }

    private void spill(List<LedgerEntry> entries, String reason) {
        if (entries.isEmpty()) {
            return;
        }
        StringBuilder lines = new StringBuilder();
        for (LedgerEntry entry : entries) {
            try {
                lines.append(mapper.writeValueAsString(entry)).append('\n');
            } catch (Exception e) {
                log.error("could not serialise ledger entry {}", entry.requestId(), e);
            }
        }
        spillLock.lock();
        try {
            if (spillPath.getParent() != null) {
                Files.createDirectories(spillPath.getParent());
            }
            Files.writeString(spillPath, lines.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            metrics.ledgerSpilled(reason, entries.size());
        } catch (IOException e) {
            // Last resort: the rows are gone, so say so loudly and count it rather than pretend.
            log.error("LEDGER LOSS: {} rows could not be spilled to {}", entries.size(), spillPath, e);
            metrics.ledgerLost(entries.size());
        } finally {
            spillLock.unlock();
        }
    }

    private void replaySpill() {
        if (!Files.exists(spillPath)) {
            return;
        }
        List<LedgerEntry> pending = new ArrayList<>();
        List<String> lines;
        spillLock.lock();
        try {
            lines = Files.readAllLines(spillPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("could not read spill file {}", spillPath, e);
            return;
        } finally {
            spillLock.unlock();
        }
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                pending.add(mapper.readValue(line, LedgerEntry.class));
            } catch (Exception e) {
                log.error("discarding unreadable spill line", e);
            }
        }
        if (pending.isEmpty()) {
            truncateSpill();
            return;
        }
        try {
            store.insertLedger(pending);
            store.accumulateDaily(pending);
        } catch (RuntimeException e) {
            // Leave the file alone: replay stays safe precisely because the writes are idempotent.
            log.warn("spill replay failed, keeping the file for the next start: {}", e.toString());
            return;
        }
        truncateSpill();
        metrics.ledgerReplayed(pending.size());
        log.info("replayed {} spilled ledger rows", pending.size());
    }

    private void truncateSpill() {
        spillLock.lock();
        try {
            Files.writeString(spillPath, "", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("could not truncate spill file {}", spillPath, e);
        } finally {
            spillLock.unlock();
        }
    }
}
