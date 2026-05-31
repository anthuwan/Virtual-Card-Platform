package com.virtual.card.domain.card;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Card} entities.
 *
 * <h2>Pessimistic Locking</h2>
 * <p>{@link #findByIdForUpdate} uses {@code @Lock(PESSIMISTIC_WRITE)} which
 * translates to {@code SELECT ... FOR UPDATE} in PostgreSQL — the same
 * database-level locking as JOOQ's {@code .forUpdate()}.
 *
 * <p>Concurrency flow:
 * <ol>
 *   <li>Thread A calls findByIdForUpdate → acquires row lock</li>
 *   <li>Thread B calls findByIdForUpdate → blocks at DB level, waits</li>
 *   <li>Thread A updates balance, commits → lock released</li>
 *   <li>Thread B unblocks, reads updated balance, makes its own decision</li>
 * </ol>
 */
public interface CardRepository extends JpaRepository<Card, UUID> {

    /**
     * Acquires a pessimistic write lock on the card row (SELECT ... FOR UPDATE).
     * Must be called within an active @Transactional context.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Card c WHERE c.id = :id")
    Optional<Card> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Returns all cards belonging to the given owner, ordered newest-first.
     * Used by the list-cards endpoint — cardholders see only their own cards.
     */
    List<Card> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    /**
     * Returns all cards in the given status whose expiry date is before the threshold.
     * Used by the scheduled card expiration job.
     */
    @Query("SELECT c FROM Card c WHERE c.status = :status " +
           "AND c.expiresAt IS NOT NULL AND c.expiresAt < :threshold")
    List<Card> findByStatusAndExpiresAtBefore(
            @Param("status") CardStatus status,
            @Param("threshold") LocalDateTime threshold);
}
