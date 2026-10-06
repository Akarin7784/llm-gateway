package com.example.llmgw.billing;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against a real H2 in MySQL mode rather than a mock, because the claims under test are SQL
 * claims: whether INSERT IGNORE really absorbs duplicates, and whether the daily roll-up accumulates
 * instead of overwriting.
 */
class LedgerStoreTest {

    private JdbcTemplate jdbc;
    private LedgerStore store;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        // A unique database per test class instance keeps parallel runs from sharing rows.
        dataSource.setURL("jdbc:h2:mem:ledger" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(dataSource);
        store = new LedgerStore(jdbc);
    }

    private static LedgerEntry entry(String requestId, String tenant, int prompt, int completion, long cost) {
        return new LedgerEntry(requestId, tenant, "gw-demo", "mock-primary", "success",
                prompt, completion, cost, 120, 40L, false, Instant.parse("2026-10-06T10:00:00Z"));
    }

    @Test
    void writesABatchAndReadsItBack() {
        store.insertLedger(List.of(entry("r1", "t1", 10, 5, 1000), entry("r2", "t1", 20, 7, 2000)));

        assertThat(store.ledgerRows()).isEqualTo(2);
        Map<String, Map<String, Object>> spend = store.spendByTenant();
        assertThat(spend.get("t1").get("total_tokens")).isEqualTo(42L);
        assertThat(spend.get("t1").get("cost_micros")).isEqualTo(3000L);
    }

    /** The invariant the whole spill-and-replay design rests on. */
    @Test
    void replayingTheSameRequestDoesNotDoubleBill() {
        List<LedgerEntry> batch = List.of(entry("dup", "t1", 10, 5, 1000));
        store.insertLedger(batch);
        store.insertLedger(batch);
        store.insertLedger(List.of(entry("dup", "t1", 999, 999, 999999)));

        assertThat(store.ledgerRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT prompt_tokens FROM usage_ledger WHERE request_id = 'dup'",
                Integer.class)).isEqualTo(10);
        assertThat(store.integrityProbe().get("distinct_request_ids"))
                .isEqualTo(store.integrityProbe().get("rows"));
    }

    @Test
    void dailyRollupAccumulatesAcrossBatches() {
        store.insertLedger(List.of(entry("d1", "t1", 10, 5, 100)));
        store.accumulateDaily(List.of(entry("d1", "t1", 10, 5, 100)));
        store.insertLedger(List.of(entry("d2", "t1", 20, 10, 200)));
        store.accumulateDaily(List.of(entry("d2", "t1", 20, 10, 200)));

        var row = jdbc.queryForMap("SELECT requests, tokens, cost_micros FROM daily_spend");
        assertThat(row.get("REQUESTS")).isEqualTo(2);
        assertThat(row.get("TOKENS")).isEqualTo(45L);
        assertThat(row.get("COST_MICROS")).isEqualTo(300L);
    }

    @Test
    void keepsTenantsApart() {
        store.insertLedger(List.of(entry("a", "t1", 10, 5, 100), entry("b", "t2", 1, 1, 1)));

        assertThat(store.spendByTenant()).containsKeys("t1", "t2");
        assertThat(store.spendByTenant().get("t2").get("requests")).isEqualTo(1L);
    }

    /** The reconciliation scope that keeps the equations meaningful across restarts. */
    @Test
    void outcomeQueryCanBeScopedToATimestamp() {
        LedgerEntry old = new LedgerEntry("old-1", "t1", "gw-demo", "mock-primary", "success",
                10, 5, 100, 10, 5L, false, Instant.parse("2026-01-01T00:00:00Z"));
        LedgerEntry recent = new LedgerEntry("new-1", "t1", "gw-demo", "mock-primary", "success",
                1, 1, 1, 10, 5L, false, Instant.parse("2026-10-06T00:00:00Z"));
        store.insertLedger(List.of(old, recent));

        assertThat(store.rowsByOutcome()).hasSize(1);
        assertThat(store.rowsByOutcome().get("success").get("tokens")).isEqualTo(17L);

        var scoped = store.rowsByOutcomeSince(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(scoped.get("success").get("rows")).isEqualTo(1L);
        assertThat(scoped.get("success").get("tokens")).isEqualTo(2L);
    }

    @Test
    void cachedExchangesAreBookedAtZeroCost() {
        LedgerEntry cached = new LedgerEntry("c1", "t1", "gw-demo", "cache", "cached",
                30, 12, 0, 0, null, true, Instant.parse("2026-10-06T10:00:00Z"));
        store.insertLedger(List.of(cached));

        assertThat(store.spendByTenant().get("t1").get("cached_requests")).isEqualTo(1L);
        assertThat(store.spendByTenant().get("t1").get("cost_micros")).isEqualTo(0L);
    }
}
