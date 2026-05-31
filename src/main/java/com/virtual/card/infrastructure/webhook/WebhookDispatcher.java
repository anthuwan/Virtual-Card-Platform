package com.virtual.card.infrastructure.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Placeholder webhook dispatcher.
 *
 * <p>In production, webhooks allow external systems (e.g. a merchant platform,
 * a mobile app backend) to receive real-time events without polling the API.
 *
 * <h2>Real implementation steps</h2>
 * <ol>
 *   <li>Store webhook endpoint URLs per card/account in the database</li>
 *   <li>On each event, look up registered endpoints</li>
 *   <li>POST a signed JSON payload to each endpoint (HMAC-SHA256 signature in header)</li>
 *   <li>Retry with exponential backoff on failure (store delivery attempts in DB)</li>
 *   <li>Expose a webhook management API — register, list, delete endpoints</li>
 * </ol>
 *
 * <h2>Event types</h2>
 * <ul>
 *   <li>{@code card.spend.successful}</li>
 *   <li>{@code card.spend.declined}</li>
 *   <li>{@code card.topup.successful}</li>
 *   <li>{@code card.status.blocked}</li>
 *   <li>{@code card.status.closed}</li>
 *   <li>{@code card.status.expired}</li>
 * </ul>
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    /**
     * Dispatches a webhook event to all registered endpoints for the given card.
     *
     * @param cardId    the card the event relates to
     * @param eventType the event type string (e.g. "card.spend.successful")
     * @param payload   the event payload object (will be serialized to JSON)
     *
     * TODO: look up registered webhook URLs for cardId, POST payload with HMAC signature
     */
    public void dispatch(UUID cardId, String eventType, Object payload) {
        log.info("[WEBHOOK-PLACEHOLDER] Would POST event='{}' for cardId={} to registered endpoints",
                eventType, cardId);
        // TODO:
        // List<String> endpoints = webhookRepository.findByCardId(cardId);
        // endpoints.forEach(url -> httpClient.post(url, sign(payload)));
    }

    /**
     * Dispatches a global platform event (not card-specific).
     * e.g. system maintenance, rate limit warnings.
     *
     * TODO: POST to all registered global webhook endpoints
     */
    public void dispatchGlobal(String eventType, Object payload) {
        log.info("[WEBHOOK-PLACEHOLDER] Would POST global event='{}'", eventType);
    }
}
