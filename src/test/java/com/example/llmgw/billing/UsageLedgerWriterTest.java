package com.example.llmgw.billing;

import com.example.llmgw.config.GatewayProperties;
import com.example.llmgw.obs.GatewayMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UsageLedgerWriterTest {

    @TempDir
    Path tempDir;

    private LedgerStore store;
    private JdbcTemplate jdbc;
    private GatewayProperties properties;
    private Path spillFile;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:writer" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(dataSource);
        store = new LedgerStore(jdbc);
        spillFile = tempDir.resolve("spill.jsonl");

        properties = new GatewayProperties();
        properties.getBilling().setSpillFile(spillFile.toString());
        properties.getBilling().setFlushInterval(java.time.Duration.ofMillis(20));
        properties.getBilling().setBatchSize(50);
    }

    private UsageLedgerWriter newWriter(int queueCapacity) {
        properties.getBilling().setQueueCapacity(queueCapacity);
        return new UsageLedgerWriter(store, properties,
                new GatewayMetrics(new SimpleMeterRegistry()), mapper(),
                new SimpleMeterRegistry());
    }

    private static ObjectMapper mapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }

    private static LedgerEntry entry(String id) {
        return new LedgerEntry(id, "t1", "gw-demo", "mock-primary", "success",
                5, 5, 50, 10, 5L, false, Instant.parse("2026-10-06T10:00:00Z"));
    }

    /**
     * Overflow must degrade into a file, not into silence: the queue fills because the database is slow,
     * and that is precisely when losing spend records is unacceptable.
     */
    @Test
    void queueOverflowSpillsAndReplayOnStartRecoversTheRows() throws Exception {
        UsageLedgerWriter writer = newWriter(2);

        // No start(): nothing drains, so the third record has nowhere to go but the spill file.
        writer.record(entry("w1"));
        writer.record(entry("w2"));
        writer.record(entry("w3"));
        assertThat(store.ledgerRows()).isZero();
        assertThat(Files.exists(spillFile)).isTrue();

        writer.start();
        writer.stop();

        assertThat(store.ledgerRows()).isEqualTo(3);
        assertThat(Files.readString(spillFile)).isEmpty();
    }

    @Test
    void replayingASpillAlreadyWrittenDoesNotDoubleCount() throws Exception {
        store.insertLedger(List.of(entry("already")));
        Files.writeString(spillFile, mapper().writeValueAsString(entry("already")) + "\n");

        UsageLedgerWriter writer = newWriter(10);
        writer.start();
        writer.stop();

        assertThat(store.ledgerRows()).isEqualTo(1);
    }

    @Test
    void shutdownDrainsTheQueueInsteadOfLosingIt() {
        UsageLedgerWriter writer = newWriter(1000);
        writer.start();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add("burst-" + i);
            writer.record(entry(ids.get(i)));
        }
        writer.stop();

        assertThat(store.ledgerRows()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT SUM(prompt_tokens + completion_tokens) FROM usage_ledger",
                Long.class)).isEqualTo(2000L);
    }

    @Test
    void dailyTotalsMatchTheRowsThatWereWritten() {
        UsageLedgerWriter writer = newWriter(100);
        writer.start();
        for (int i = 0; i < 30; i++) {
            writer.record(entry("d-" + i));
        }
        writer.stop();

        var rollup = jdbc.queryForMap("SELECT requests, tokens, cost_micros FROM daily_spend");
        assertThat(rollup.get("REQUESTS")).isEqualTo(30);
        assertThat(rollup.get("TOKENS")).isEqualTo(300L);
        assertThat(rollup.get("COST_MICROS")).isEqualTo(1500L);
    }
}
