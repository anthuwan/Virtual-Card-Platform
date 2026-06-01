package com.virtual.card.domain.card;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * JPA entity representing a virtual card.
 *
 * <p>Uses {@code BigDecimal} for balance to avoid floating-point precision
 * issues common in financial applications.
 *
 * <p>Balance integrity is enforced at two layers:
 * <ul>
 *   <li>Service layer — checked before every spend operation</li>
 *   <li>Database layer — CHECK (balance >= 0) constraint via Flyway migration</li>
 * </ul>
 *
 * <p>Pessimistic locking ({@code SELECT ... FOR UPDATE}) is applied via
 * {@code @Lock(LockModeType.PESSIMISTIC_WRITE)} in {@link CardRepository}
 * for spend and top-up operations to prevent race conditions.
 */
@Entity
@Table(name = "cards")
public class Card {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id = UUID.randomUUID(); // assigned in Java — available immediately, before any DB flush

    @Column(name = "cardholder_name", nullable = false, length = 255)
    private String cardholderName;

    /**
     * JWT subject claim (`sub`) of the authenticated user who owns this card.
     * Used to enforce that cardholders can only access their own cards.
     */
    @Column(name = "owner_id", length = 255)
    private String ownerId;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CardStatus status;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Optimistic locking version counter.
     *
     * <p>JPA increments this on every UPDATE. If two concurrent transactions
     * read version=5 and both try to update, only the first succeeds — the second
     * gets {@code OptimisticLockException} because the DB row now has version=6.
     *
     * <p>This is a defence-in-depth complement to pessimistic locking:
     * pessimistic locking serialises requests at the DB level;
     * optimistic locking catches any edge cases that slip through
     * (e.g. cache reads that bypass the lock).
     *
     * <p>Callers should use {@code @Retryable(OptimisticLockException.class)}
     * to transparently retry on conflict. See {@link com.virtual.card.domain.card.CardService}.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    // JPA requires a no-arg constructor
    protected Card() {}

    public Card(String cardholderName, BigDecimal balance, CardStatus status, LocalDateTime expiresAt) {
        this.cardholderName = cardholderName;
        this.balance = balance;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    public Card(String cardholderName, BigDecimal balance, CardStatus status, LocalDateTime expiresAt, String ownerId) {
        this(cardholderName, balance, status, expiresAt);
        this.ownerId = ownerId;
    }

    // ─── Business Methods ─────────────────────────────────────────────────────

    /** Returns true if this card can perform financial operations. */
    public boolean isOperational() {
        return status == CardStatus.ACTIVE;
    }

    /** Returns true if the card has passed its expiry date. */
    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    public UUID getId()                  { return id; }
    public String getCardholderName()    { return cardholderName; }
    public String getOwnerId()           { return ownerId; }
    public BigDecimal getBalance()       { return balance; }
    public CardStatus getStatus()        { return status; }
    public LocalDateTime getExpiresAt()  { return expiresAt; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
    public LocalDateTime getUpdatedAt()  { return updatedAt; }
    public Long getVersion()             { return version; }

    // ─── Setters (used by service layer) ─────────────────────────────────────

    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public void setStatus(CardStatus status)   { this.status = status; }
}
