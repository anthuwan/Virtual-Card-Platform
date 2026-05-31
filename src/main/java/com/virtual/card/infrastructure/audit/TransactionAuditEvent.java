package com.virtual.card.infrastructure.audit;

import com.virtual.card.domain.transaction.Transaction;
import org.springframework.context.ApplicationEvent;

/**
 * Spring ApplicationEvent published after every transaction (success or decline).
 *
 * <p>Using the built-in Spring event system rather than Kafka keeps the audit trail
 * lightweight for this service. The listener is {@code @Async} so it does not add
 * latency to the HTTP response path.
 *
 * <p>Future evolution: swap the listener implementation with a Kafka producer to
 * fan out audit events to downstream consumers (fraud detection, reporting, etc.)
 * without changing the publisher side.
 */
public class TransactionAuditEvent extends ApplicationEvent {

    private final Transaction transaction;

    public TransactionAuditEvent(Object source, Transaction transaction) {
        super(source);
        this.transaction = transaction;
    }

    public Transaction getTransaction() {
        return transaction;
    }
}
