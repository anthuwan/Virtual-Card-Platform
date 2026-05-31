package com.virtual.card.infrastructure.outbox;

import com.virtual.card.infrastructure.kafka.CardEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox service.
 *
 * <h2>Write side</h2>
 * <p>{@link #saveEvent} persists an outbox event within the caller's existing
 * transaction — same DB commit as the business operation. Call this from
 * {@code CardService} instead of publishing to Kafka directly.
 *
 * <h2>Read/publish side</h2>
 * <p>{@link #pollAndPublish} runs on a fixed schedule, fetches PENDING events,
 * and publishes them to Kafka (placeholder). On success marks PUBLISHED; on
 * failure increments retry count and eventually marks FAILED after 5 attempts.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>At-least-once</b> — event is retried until PUBLISHED or FAILED</li>
 *   <li><b>No silent loss</b> — app crash between commit and Kafka send is safe;
 *       the poller will pick up the PENDING event on restart</li>
 *   <li><b>Idempotency required downstream</b> — Kafka consumers must handle
 *       duplicate events (same event published twice on retry)</li>
 * </ul>
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final CardEventPublisher cardEventPublisher;

    public OutboxService(OutboxEventRepository outboxEventRepository,
                         CardEventPublisher cardEventPublisher) {
        this.outboxEventRepository = outboxEventRepository;
        this.cardEventPublisher    = cardEventPublisher;
    }

    /**
     * Saves an outbox event in the current transaction.
     * Must be called within an active {@code @Transactional} context.
     *
     * @param aggregateType e.g. "CARD" or "TRANSACTION"
     * @param aggregateId   the entity UUID
     * @param eventType     e.g. "card.spend.successful"
     * @param payload       JSON string of the event payload
     */
    @Transactional
    public void saveEvent(String aggregateType, UUID aggregateId, String eventType, String payload) {
        OutboxEvent event = new OutboxEvent(aggregateType, aggregateId, eventType, payload);
        outboxEventRepository.save(event);
        log.debug("Outbox event saved: type={}, aggregateId={}", eventType, aggregateId);
    }

    /**
     * Polls PENDING outbox events and publishes them to Kafka.
     * Runs every 5 seconds. Marks each event PUBLISHED or increments retry on failure.
     *
     * <p>TODO: replace log statements with real {@code kafkaTemplate.send()} calls.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
    @Transactional
    public void pollAndPublish() {
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents();
        if (pending.isEmpty()) return;

        log.debug("Outbox poller: found {} PENDING events", pending.size());

        for (OutboxEvent event : pending) {
            try {
                // TODO: replace with kafkaTemplate.send(event.getEventType(), event.getAggregateId().toString(), event.getPayload())
                log.info("[OUTBOX] Publishing: type={}, aggregateId={}, retryCount={}",
                        event.getEventType(), event.getAggregateId(), event.getRetryCount());

                event.markPublished();
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("[OUTBOX] Failed to publish event id={}, error={}", event.getId(), e.getMessage());
                event.markFailed(e.getMessage());
                outboxEventRepository.save(event);
            }
        }
    }
}
