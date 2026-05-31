package com.virtual.card.infrastructure.audit;

import com.virtual.card.domain.transaction.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Async listener for {@link TransactionAuditEvent}.
 *
 * <p>The {@code @Async} annotation ensures audit processing happens on a separate
 * thread pool, keeping the transaction commit path fast. If audit logging fails,
 * the original transaction is unaffected.
 *
 * <p>In production this listener could be extended to:
 * <ul>
 *   <li>Write to a dedicated audit log table</li>
 *   <li>Publish to a Kafka topic for downstream consumers</li>
 *   <li>Push to a SIEM system for security monitoring</li>
 * </ul>
 */
@Component
public class AuditEventListener {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    @Async
    @EventListener
    public void onTransactionAuditEvent(TransactionAuditEvent event) {
        Transaction tx = event.getTransaction();
        // Structured audit log — in production, ship to a log aggregator (e.g. Datadog, ELK)
        auditLog.info("AUDIT | type={} | status={} | cardId={} | amount={} | txId={} | idempotencyKey={}",
                tx.type(),
                tx.status(),
                tx.cardId(),
                tx.amount(),
                tx.id(),
                tx.idempotencyKey());
    }
}
