-- Usage ledger. Costs are stored in whole micro-units of the configured currency rather than as
-- floating point: summing 30k float prices to answer "what did we spend" drifts, and the drift lands
-- exactly where a billing dispute happens.

CREATE TABLE IF NOT EXISTS usage_ledger (
    request_id        VARCHAR(64)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    model             VARCHAR(64)  NOT NULL,
    upstream          VARCHAR(64)  NOT NULL,
    outcome           VARCHAR(24)  NOT NULL,
    prompt_tokens     INT          NOT NULL,
    completion_tokens INT          NOT NULL,
    cost_micros       BIGINT       NOT NULL,
    latency_ms        BIGINT       NOT NULL,
    ttft_ms           BIGINT,
    cached            BOOLEAN      NOT NULL,
    created_at        TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ledger_tenant_time ON usage_ledger (tenant_id, created_at);

CREATE TABLE IF NOT EXISTS daily_spend (
    tenant_id    VARCHAR(64) NOT NULL,
    spend_day    DATE        NOT NULL,
    requests     INT         NOT NULL,
    tokens       BIGINT      NOT NULL,
    cost_micros  BIGINT      NOT NULL,
    PRIMARY KEY (tenant_id, spend_day)
);
