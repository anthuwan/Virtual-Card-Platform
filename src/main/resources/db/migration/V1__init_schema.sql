-- Virtual Card Platform Schema
-- V1__init_schema.sql

CREATE TABLE cards (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    cardholder_name VARCHAR(255)    NOT NULL,
    balance         DECIMAL(19, 4)  NOT NULL DEFAULT 0,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    expires_at      TIMESTAMP,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_card_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED', 'EXPIRED'))
);

-- Idempotency: unique constraint on idempotency_key (partial — allows NULLs)
CREATE TABLE transactions (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    card_id         UUID            NOT NULL REFERENCES cards(id),
    type            VARCHAR(20)     NOT NULL,
    amount          DECIMAL(19, 4)  NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(255),
    description     VARCHAR(500),
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_transaction_type   CHECK (type   IN ('SPEND', 'TOP_UP')),
    CONSTRAINT chk_transaction_status CHECK (status IN ('SUCCESSFUL', 'DECLINED', 'PENDING')),
    CONSTRAINT chk_amount_positive    CHECK (amount > 0)
);

-- Indexes
CREATE UNIQUE INDEX idx_transactions_idempotency_key
    ON transactions(idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_transactions_card_id ON transactions(card_id);
CREATE INDEX idx_cards_status         ON cards(status);
CREATE INDEX idx_cards_expires_at     ON cards(expires_at) WHERE expires_at IS NOT NULL;
