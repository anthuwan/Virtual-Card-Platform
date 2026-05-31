-- V2: Add optimistic lock version column to cards
--     Add transactional outbox table for reliable event publishing

-- Optimistic locking: version column on cards
-- JPA @Version increments this on every UPDATE.
-- Two concurrent transactions reading version=N both try to UPDATE WHERE version=N —
-- only one succeeds; the other gets a conflict and must retry.
ALTER TABLE cards ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Transactional Outbox table
-- Events are written here IN THE SAME DB TRANSACTION as the card/transaction update.
-- A scheduler polls this table and publishes to Kafka, then marks events as PUBLISHED.
-- This guarantees at-least-once delivery even if the app crashes between DB commit and Kafka send.
CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50)  NOT NULL,          -- e.g. 'CARD', 'TRANSACTION'
    aggregate_id    UUID         NOT NULL,           -- card_id or transaction_id
    event_type      VARCHAR(100) NOT NULL,           -- e.g. 'card.spend.successful'
    payload         TEXT         NOT NULL,           -- JSON payload
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING, PUBLISHED, FAILED
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMP,
    retry_count     INT          NOT NULL DEFAULT 0,
    last_error      TEXT,

    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- Index for the poller — only fetch PENDING events ordered by creation time
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox_events (status, created_at)
    WHERE status = 'PENDING';

-- Index for aggregate lookups (debugging/replaying events for a specific card)
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);
