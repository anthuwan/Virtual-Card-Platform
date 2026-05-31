package com.virtual.card.infrastructure.outbox;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Transactional outbox event entity.
 *
 * <p>Written to the DB in the same transaction as the business operation
 * (spend, topUp, etc.). A scheduler polls PENDING events and publishes
 * them to Kafka, guaranteeing at-least-once delivery.
 *
 * <h2>Why this pattern?</h2>
 * <p>Without the outbox, there is a gap between DB commit and Kafka publish:
 * if the app crashes in that window, the event is silently lost. The outbox
 * eliminates this gap — either both the business data and the event are
 * persisted, or neither is (they're in the same transaction).
 *
 * <h2>Status lifecycle</h2>
 * <pre>
 *   PENDING → PUBLISHED  (normal flow — scheduler published successfully)
 *   PENDING → FAILED     (after max retries exceeded)
 * </pre>
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public enum Status { PENDING, PUBLISHED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;   // e.g. "CARD", "TRANSACTION"

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;       // card_id or transaction_id

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;       // e.g. "card.spend.successful"

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;         // JSON payload

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    protected OutboxEvent() {}

    public OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId   = aggregateId;
        this.eventType     = eventType;
        this.payload       = payload;
        this.status        = Status.PENDING;
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    public UUID getId()               { return id; }
    public String getAggregateType()  { return aggregateType; }
    public UUID getAggregateId()      { return aggregateId; }
    public String getEventType()      { return eventType; }
    public String getPayload()        { return payload; }
    public Status getStatus()         { return status; }
    public LocalDateTime getCreatedAt()   { return createdAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public int getRetryCount()        { return retryCount; }
    public String getLastError()      { return lastError; }

    // ─── State transitions ────────────────────────────────────────────────────

    public void markPublished() {
        this.status      = Status.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    public void markFailed(String error) {
        this.retryCount++;
        this.lastError = error;
        if (this.retryCount >= 5) {
            this.status = Status.FAILED;
        }
    }
}
