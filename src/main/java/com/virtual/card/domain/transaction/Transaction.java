package com.virtual.card.domain.transaction;

import com.virtual.card.domain.card.Card;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * JPA entity representing a single financial operation on a card.
 *
 * <p>Each transaction is immutable once created — there are no setters.
 * Declined transactions are persisted alongside successful ones to provide:
 * <ul>
 *   <li>A complete audit trail</li>
 *   <li>Idempotency support — a duplicate key returns the original declined record</li>
 * </ul>
 *
 * <p>The {@code idempotency_key} column has a partial unique index in the database
 * (WHERE idempotency_key IS NOT NULL) as a safety net against concurrent duplicate inserts.
 */
@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_transactions_card_id", columnList = "card_id"),
        @Index(name = "idx_transactions_idempotency_key", columnList = "idempotency_key", unique = true)
})
public class Transaction {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id = UUID.randomUUID(); // assigned in Java — available immediately, before any DB flush

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false, updatable = false)
    private Card card;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private TransactionType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "idempotency_key", length = 255, updatable = false)
    private String idempotencyKey;

    @Column(name = "description", length = 500, updatable = false)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Transaction() {}

    public Transaction(Card card, TransactionType type, BigDecimal amount,
                       TransactionStatus status, String idempotencyKey, String description) {
        this.card = card;
        this.type = type;
        this.amount = amount;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.description = description;
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    public UUID getId()                  { return id; }
    public Card getCard()                { return card; }
    public UUID getCardId()              { return card != null ? card.getId() : null; }
    public TransactionType getType()     { return type; }
    public BigDecimal getAmount()        { return amount; }
    public TransactionStatus getStatus() { return status; }
    public String getIdempotencyKey()    { return idempotencyKey; }
    public String getDescription()       { return description; }
    public LocalDateTime getCreatedAt()  { return createdAt; }

    public boolean isSuccessful() { return status == TransactionStatus.SUCCESSFUL; }
    public boolean isDeclined()   { return status == TransactionStatus.DECLINED; }
}
