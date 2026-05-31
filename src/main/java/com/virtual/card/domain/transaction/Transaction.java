package com.virtual.card.domain.transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain model representing a single financial operation on a card.
 *
 * <p>Each transaction is immutable once created. Declined transactions are also
 * persisted — this provides a complete audit trail and supports idempotency
 * (a duplicate request with the same key returns the original declined record).
 */
public record Transaction(
        UUID id,
        UUID cardId,
        TransactionType type,
        BigDecimal amount,
        TransactionStatus status,
        String idempotencyKey,
        String description,
        LocalDateTime createdAt
) {
    public boolean isSuccessful() {
        return status == TransactionStatus.SUCCESSFUL;
    }

    public boolean isDeclined() {
        return status == TransactionStatus.DECLINED;
    }
}
