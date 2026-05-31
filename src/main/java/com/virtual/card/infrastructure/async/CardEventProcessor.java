package com.virtual.card.infrastructure.async;

import com.virtual.card.domain.card.Card;
import com.virtual.card.domain.transaction.Transaction;
import com.virtual.card.domain.transaction.TransactionStatus;
import com.virtual.card.infrastructure.kafka.CardEventPublisher;
import com.virtual.card.infrastructure.notification.NotificationService;
import com.virtual.card.infrastructure.webhook.WebhookDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Async post-processing of card events.
 *
 * <p>All methods run on a separate thread pool ({@code @Async}) so downstream
 * processing (Kafka publish, notifications, webhooks) never adds latency to
 * the main request path. If any step fails, the original transaction is
 * unaffected — the DB commit has already happened.
 *
 * <p>This class orchestrates:
 * <ol>
 *   <li>Kafka event publishing</li>
 *   <li>Cardholder notifications (push/SMS/email)</li>
 *   <li>Webhook dispatch to registered external endpoints</li>
 * </ol>
 *
 * <h2>Thread pool</h2>
 * <p>Configured via {@code spring.task.execution} in application.yml.
 * Default Spring async pool is used here; replace with a named executor
 * ({@code @Async("cardEventExecutor")}) for dedicated pool sizing in production.
 */
@Component
public class CardEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(CardEventProcessor.class);

    private final CardEventPublisher kafkaPublisher;
    private final NotificationService notificationService;
    private final WebhookDispatcher webhookDispatcher;

    public CardEventProcessor(CardEventPublisher kafkaPublisher,
                              NotificationService notificationService,
                              WebhookDispatcher webhookDispatcher) {
        this.kafkaPublisher = kafkaPublisher;
        this.notificationService = notificationService;
        this.webhookDispatcher = webhookDispatcher;
    }

    /**
     * Processes all downstream events after a spend transaction.
     * Runs asynchronously — does not block the HTTP response.
     */
    @Async
    public void processSpendEvent(Transaction tx) {
        log.debug("Async processing spend event: txId={}, status={}", tx.getId(), tx.getStatus());
        try {
            kafkaPublisher.publishSpendEvent(tx);

            if (tx.getStatus() == TransactionStatus.SUCCESSFUL) {
                notificationService.notifySpendSuccessful(tx.getCardId(), tx.getAmount(), null);
                notificationService.notifyLowBalance(tx.getCardId(), tx.getAmount()); // balance check
                webhookDispatcher.dispatch(tx.getCardId(), "card.spend.successful", tx);
            } else if (tx.getStatus() == TransactionStatus.DECLINED) {
                notificationService.notifySpendDeclined(tx.getCardId(), tx.getAmount());
                webhookDispatcher.dispatch(tx.getCardId(), "card.spend.declined", tx);
            }
        } catch (Exception e) {
            // Never let async processing failure propagate — log and move on
            log.error("Async spend event processing failed: txId={}, error={}", tx.getId(), e.getMessage(), e);
        }
    }

    /**
     * Processes all downstream events after a top-up transaction.
     * Runs asynchronously — does not block the HTTP response.
     */
    @Async
    public void processTopUpEvent(Transaction tx) {
        log.debug("Async processing top-up event: txId={}", tx.getId());
        try {
            kafkaPublisher.publishTopUpEvent(tx);
            notificationService.notifyTopUpSuccessful(tx.getCardId(), tx.getAmount(), null);
            webhookDispatcher.dispatch(tx.getCardId(), "card.topup.successful", tx);
        } catch (Exception e) {
            log.error("Async top-up event processing failed: txId={}, error={}", tx.getId(), e.getMessage(), e);
        }
    }

    /**
     * Processes all downstream events after a card status change (block/close/expire).
     * Runs asynchronously — does not block the HTTP response.
     */
    @Async
    public void processCardStatusEvent(Card card) {
        log.debug("Async processing card status event: cardId={}, status={}", card.getId(), card.getStatus());
        try {
            kafkaPublisher.publishCardStatusChanged(card);
            switch (card.getStatus()) {
                case BLOCKED  -> {
                    notificationService.notifyCardBlocked(card.getId());
                    webhookDispatcher.dispatch(card.getId(), "card.status.blocked", card);
                }
                case EXPIRED  -> {
                    notificationService.notifyCardExpired(card.getId());
                    webhookDispatcher.dispatch(card.getId(), "card.status.expired", card);
                }
                case CLOSED   -> webhookDispatcher.dispatch(card.getId(), "card.status.closed", card);
                default       -> log.debug("No notification configured for status: {}", card.getStatus());
            }
        } catch (Exception e) {
            log.error("Async card status event processing failed: cardId={}, error={}", card.getId(), e.getMessage(), e);
        }
    }

    /**
     * Processes all downstream events after a new card is created.
     * Runs asynchronously — does not block the HTTP response.
     */
    @Async
    public void processCardCreatedEvent(Card card) {
        log.debug("Async processing card created event: cardId={}", card.getId());
        try {
            kafkaPublisher.publishCardCreated(card);
            webhookDispatcher.dispatch(card.getId(), "card.created", card);
        } catch (Exception e) {
            log.error("Async card created event processing failed: cardId={}, error={}", card.getId(), e.getMessage(), e);
        }
    }
}
