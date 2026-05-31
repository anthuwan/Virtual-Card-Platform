package com.virtual.card.domain.card;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port (interface) for card persistence operations.
 *
 * <p>The service layer depends only on this interface; the JOOQ implementation lives in
 * the infrastructure layer. This inversion keeps the domain free of persistence details
 * and makes unit testing straightforward with simple mocks.
 */
public interface CardRepository {

    /**
     * Persists a new card and returns the saved state.
     */
    Card create(String cardholderName, BigDecimal initialBalance, LocalDateTime expiresAt);

    /**
     * Finds a card by its ID. Returns empty if not found.
     */
    Optional<Card> findById(UUID id);

    /**
     * Finds a card by ID and acquires a pessimistic write lock (SELECT FOR UPDATE).
     *
     * <p>Must be called within an active transaction. Used during spend and top-up
     * to prevent lost updates under concurrent access.
     */
    Optional<Card> findByIdForUpdate(UUID id);

    /**
     * Updates the balance of an existing card.
     */
    Card updateBalance(UUID id, BigDecimal newBalance);

    /**
     * Transitions a card to a new status.
     */
    Card updateStatus(UUID id, CardStatus status);

    /**
     * Returns all cards in the given status whose expiry date is before the given instant.
     * Used by the scheduled expiration job.
     */
    List<Card> findByStatusAndExpiresAtBefore(CardStatus status, LocalDateTime threshold);
}
