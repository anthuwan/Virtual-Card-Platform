package com.virtual.card.infrastructure.kafka;

import com.virtual.card.domain.card.Card;
import com.virtual.card.domain.transaction.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder Kafka event publisher.
 *
 * <p>In production, replace with a real {@code KafkaTemplate<String, Object>}
 * implementation. Each method would serialize the payload to JSON and publish
 * to the corresponding Kafka topic.
 *
 * <h2>Topics</h2>
 * <ul>
 *   <li>{@code card.created}     — new card issued</li>
 *   <li>{@code card.spend}       — spend attempted (SUCCESSFUL or DECLINED)</li>
 *   <li>{@code card.topup}       — top-up applied</li>
 *   <li>{@code card.status}      — card blocked, closed, or expired</li>
 * </ul>
 *
 * <h2>Real implementation steps</h2>
 * <ol>
 *   <li>Add {@code spring-kafka} dependency to pom.xml</li>
 *   <li>Configure {@code spring.kafka.bootstrap-servers} in application.yml</li>
 *   <li>Inject {@code KafkaTemplate<String, Object>} and call {@code kafkaTemplate.send(topic, key, payload)}</li>
 *   <li>Use card ID as the message key — guarantees ordering per card</li>
 * </ol>
 */
@Component
public class CardEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CardEventPublisher.class);

    private static final String TOPIC_CARD_CREATED = "card.created";
    private static final String TOPIC_CARD_SPEND   = "card.spend";
    private static final String TOPIC_CARD_TOPUP   = "card.topup";
    private static final String TOPIC_CARD_STATUS  = "card.status";

    /**
     * TODO: kafkaTemplate.send(TOPIC_CARD_CREATED, card.getId().toString(), toEvent(card))
     */
    public void publishCardCreated(Card card) {
        log.info("[KAFKA-PLACEHOLDER] Would publish to '{}': cardId={}, cardholder={}",
                TOPIC_CARD_CREATED, card.getId(), card.getCardholderName());
    }

    /**
     * TODO: kafkaTemplate.send(TOPIC_CARD_SPEND, tx.getCardId().toString(), toEvent(tx))
     */
    public void publishSpendEvent(Transaction tx) {
        log.info("[KAFKA-PLACEHOLDER] Would publish to '{}': cardId={}, amount={}, status={}",
                TOPIC_CARD_SPEND, tx.getCardId(), tx.getAmount(), tx.getStatus());
    }

    /**
     * TODO: kafkaTemplate.send(TOPIC_CARD_TOPUP, tx.getCardId().toString(), toEvent(tx))
     */
    public void publishTopUpEvent(Transaction tx) {
        log.info("[KAFKA-PLACEHOLDER] Would publish to '{}': cardId={}, amount={}, status={}",
                TOPIC_CARD_TOPUP, tx.getCardId(), tx.getAmount(), tx.getStatus());
    }

    /**
     * TODO: kafkaTemplate.send(TOPIC_CARD_STATUS, card.getId().toString(), toEvent(card))
     */
    public void publishCardStatusChanged(Card card) {
        log.info("[KAFKA-PLACEHOLDER] Would publish to '{}': cardId={}, newStatus={}",
                TOPIC_CARD_STATUS, card.getId(), card.getStatus());
    }
}
