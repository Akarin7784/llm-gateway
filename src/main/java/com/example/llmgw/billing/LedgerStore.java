package com.example.llmgw.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All SQL for the ledger, kept apart from the queueing so the statements can be tested against a real
 * database without starting a gateway.
 */
@Component
public class LedgerStore {

    /**
     * Idempotent by construction: {@code request_id} is the primary key and duplicates are ignored, so
     * a replayed spill file or a retried batch cannot double-bill. This is why the write path is plain
     * JDBC -- expressing "insert if unseen" through an ORM tends to become a select-then-insert race.
     */
    private static final String INSERT_LEDGER = """
            INSERT IGNORE INTO usage_ledger
            (request_id, tenant_id, model, upstream, outcome, prompt_tokens, completion_tokens,
             cost_micros, latency_ms, ttft_ms, cached, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String UPDATE_DAILY = """
            UPDATE daily_spend SET requests = requests + ?, tokens = tokens + ?, cost_micros = cost_micros + ?
            WHERE tenant_id = ? AND spend_day = ?""";

    private static final String INSERT_DAILY = """
            INSERT INTO daily_spend (tenant_id, spend_day, requests, tokens, cost_micros)
            VALUES (?, ?, ?, ?, ?)""";

    /** 12 columns per row; keep each batch under ~6000 bind parameters so any JDBC driver copes. */
    private static final int ROWS_PER_STATEMENT = 500;

    private final JdbcTemplate jdbc;

    public LedgerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertLedger(List<LedgerEntry> batch) {
        for (int from = 0; from < batch.size(); from += ROWS_PER_STATEMENT) {
            List<LedgerEntry> chunk = batch.subList(from, Math.min(from + ROWS_PER_STATEMENT, batch.size()));
            jdbc.batchUpdate(INSERT_LEDGER, chunk, chunk.size(), (ps, entry) -> {
                ps.setString(1, entry.requestId());
                ps.setString(2, entry.tenantId());
                ps.setString(3, entry.model());
                ps.setString(4, entry.upstream());
                ps.setString(5, entry.outcome());
                ps.setInt(6, entry.promptTokens());
                ps.setInt(7, entry.completionTokens());
                ps.setLong(8, entry.costMicros());
                ps.setLong(9, entry.latencyMillis());
                if (entry.ttftMillis() == null) {
                    ps.setNull(10, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(10, entry.ttftMillis());
                }
                ps.setBoolean(11, entry.cached());
                ps.setTimestamp(12, Timestamp.from(entry.createdAt()));
            });
        }
    }

    /**
     * Update-then-insert rather than a merge statement: the drain loop is single-threaded, so the
     * classic lost-update race this pattern usually has does not apply, and the two statements stay
     * portable across MySQL and H2 without dialect-specific syntax.
     */
    public void accumulateDaily(List<LedgerEntry> batch) {
        Map<String, long[]> byTenantDay = new LinkedHashMap<>();
        for (LedgerEntry entry : batch) {
            LocalDate day = entry.createdAt().atOffset(java.time.ZoneOffset.UTC).toLocalDate();
            long[] totals = byTenantDay.computeIfAbsent(entry.tenantId() + '\u0000' + day,
                    key -> new long[3]);
            totals[0] += 1;
            totals[1] += entry.totalTokens();
            totals[2] += entry.costMicros();
        }
        byTenantDay.forEach((key, totals) -> {
            String[] parts = key.split("\u0000");
            String tenant = parts[0];
            LocalDate day = LocalDate.parse(parts[1]);
            int updated = jdbc.update(UPDATE_DAILY, totals[0], totals[1], totals[2], tenant, day);
            if (updated == 0) {
                jdbc.update(INSERT_DAILY, tenant, day, totals[0], totals[1], totals[2]);
            }
        });
    }

    public long ledgerRows() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM usage_ledger", Long.class);
        return count == null ? 0 : count;
    }

    public Map<String, Map<String, Object>> spendByTenant() {
        Map<String, Map<String, Object>> view = new LinkedHashMap<>();
        jdbc.query("""
                SELECT tenant_id, COUNT(*) AS requests, SUM(prompt_tokens) AS prompt_tokens,
                       SUM(completion_tokens) AS completion_tokens, SUM(cost_micros) AS cost_micros,
                       SUM(CASE WHEN cached THEN 1 ELSE 0 END) AS cached_requests
                FROM usage_ledger GROUP BY tenant_id""",
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("requests", rs.getLong("requests"));
                    row.put("prompt_tokens", rs.getLong("prompt_tokens"));
                    row.put("completion_tokens", rs.getLong("completion_tokens"));
                    row.put("total_tokens", rs.getLong("prompt_tokens") + rs.getLong("completion_tokens"));
                    row.put("cost_micros", rs.getLong("cost_micros"));
                    row.put("cached_requests", rs.getLong("cached_requests"));
                    view.put(rs.getString("tenant_id"), row);
                });
        return view;
    }

    /** Rows grouped by outcome: the reconciliation compares each category against its own counter. */
    public Map<String, Map<String, Object>> rowsByOutcome() {
        Map<String, Map<String, Object>> view = new LinkedHashMap<>();
        jdbc.query("""
                SELECT outcome, COUNT(*) AS rows_count, SUM(prompt_tokens + completion_tokens) AS tokens,
                       SUM(cost_micros) AS cost_micros, SUM(latency_ms) AS latency_ms
                FROM usage_ledger GROUP BY outcome""",
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("rows", rs.getLong("rows_count"));
                    row.put("tokens", rs.getLong("tokens"));
                    row.put("cost_micros", rs.getLong("cost_micros"));
                    row.put("latency_ms_total", rs.getLong("latency_ms"));
                    view.put(rs.getString("outcome"), row);
                });
        return view;
    }

    /** Distinct request ids versus row count: unequal means something wrote the same call twice. */
    public Map<String, Object> integrityProbe() {
        return Map.of(
                "rows", ledgerRows(),
                "distinct_request_ids", jdbc.queryForObject("SELECT COUNT(DISTINCT request_id) FROM usage_ledger",
                        Long.class));
    }
}
