package com.virtual.card.domain.transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port (interface) for transaction persistence operations.
 */
public interface TransactionRepository {

    /**
     * Persists a new transaction record and returns the saved state.
     */
    Transaction create(UUID cardId, TransactionType type, BigDecimal amount,
                       TransactionStatus status, String idempotencyKey, String description);

    /**
     * Returns all transactions for a card, ordered by creation time descending.
     */
    List<Transaction> findByCardId(UUID cardId);

    /**
     * Finds a transaction by its idempotency key.
     * Returns empty if no transaction with that key exists.
     */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
