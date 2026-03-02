-- ============================================================
-- V1 — Initial schema
-- Safe to run on an existing database (IF NOT EXISTS guards).
-- ============================================================

CREATE TABLE IF NOT EXISTS trade_records (
    trade_id                VARCHAR(20)   PRIMARY KEY,
    symbol                  VARCHAR(50),
    exchange                VARCHAR(10),
    sector                  VARCHAR(100),
    signal_type             VARCHAR(10),
    status                  VARCHAR(30),

    entry_price             DOUBLE PRECISION NOT NULL DEFAULT 0,
    target_price            DOUBLE PRECISION NOT NULL DEFAULT 0,
    stop_loss_price         DOUBLE PRECISION NOT NULL DEFAULT 0,
    risk_reward_ratio       DOUBLE PRECISION NOT NULL DEFAULT 0,
    capital_allocation_inr  DOUBLE PRECISION NOT NULL DEFAULT 0,
    confidence_score        DOUBLE PRECISION NOT NULL DEFAULT 0,

    -- Sub-scores
    fundamental_score       DOUBLE PRECISION NOT NULL DEFAULT 0,
    technical_score         DOUBLE PRECISION NOT NULL DEFAULT 0,
    macro_score             DOUBLE PRECISION NOT NULL DEFAULT 0,
    risk_reward_score       DOUBLE PRECISION NOT NULL DEFAULT 0,

    -- Timestamps
    generated_at            TIMESTAMP,
    expires_at              TIMESTAMP,
    approved_at             TIMESTAMP,
    executed_at             TIMESTAMP,
    closed_at               TIMESTAMP,

    -- Outcome
    exit_price              DOUBLE PRECISION,
    realised_pnl_inr        DOUBLE PRECISION,
    realised_pnl_pct        DOUBLE PRECISION,
    exit_reason             VARCHAR(255),
    target_hit              BOOLEAN NOT NULL DEFAULT FALSE,

    -- Metadata
    rejection_reason        VARCHAR(255),
    broker_order_id         VARCHAR(100),
    fundamental_summary     VARCHAR(500),
    technical_summary       VARCHAR(500),
    macro_context           VARCHAR(300)
);

CREATE TABLE IF NOT EXISTS portfolio_snapshots (
    id                      BIGSERIAL     PRIMARY KEY,
    snapshot_date           DATE,
    total_value_inr         DOUBLE PRECISION NOT NULL DEFAULT 0,
    cash_inr                DOUBLE PRECISION NOT NULL DEFAULT 0,
    deployed_inr            DOUBLE PRECISION NOT NULL DEFAULT 0,
    deployed_pct            DOUBLE PRECISION NOT NULL DEFAULT 0,
    open_positions          INTEGER       NOT NULL DEFAULT 0,
    unrealised_pnl_inr      DOUBLE PRECISION NOT NULL DEFAULT 0,
    day_pnl_inr             DOUBLE PRECISION NOT NULL DEFAULT 0,
    peak_value_inr          DOUBLE PRECISION NOT NULL DEFAULT 0,
    drawdown_from_peak_pct  DOUBLE PRECISION NOT NULL DEFAULT 0
);
