package com.virtual.card.domain.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Transaction} entities.
 */
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Returns all transactions for a card ordered by creation time descending.
     */
    @Query("SELECT t FROM Transaction t WHERE t.card.id = :cardId ORDER BY t.createdAt DESC")
    List<Transaction> findByCardId(@Param("cardId") UUID cardId);

    /**
     * Finds a transaction by its idempotency key.
     * Returns empty if no transaction with that key exists.
     */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
