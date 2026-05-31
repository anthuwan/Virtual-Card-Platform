package com.virtual.card.scheduler;

import com.virtual.card.domain.card.CardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Scheduled job that transitions expired cards from ACTIVE to EXPIRED status.
 *
 * <p>The cron expression is externally configurable via {@code app.card.expiry-check-cron}.
 * Default: every hour on the hour.
 *
 * <p>The job delegates to {@link CardService#expireCards} which finds all ACTIVE cards
 * whose {@code expires_at} is in the past and marks them EXPIRED in a single batch.
 *
 * <h2>Scaling consideration</h2>
 * <p>In a multi-instance deployment this job would run on every instance simultaneously.
 * Solutions: (1) use ShedLock or Spring Batch to ensure only one instance runs at a time;
 * (2) move to an event-driven model where a single scheduler emits card-expiry events
 * consumed by one instance.
 */
@Component
public class CardExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(CardExpirationScheduler.class);

    private final CardService cardService;

    public CardExpirationScheduler(CardService cardService) {
        this.cardService = cardService;
    }

    @Scheduled(cron = "${app.card.expiry-check-cron}")
    public void expireCards() {
        LocalDateTime now = LocalDateTime.now();
        log.info("Running card expiration check at {}", now);
        try {
            cardService.expireCards(now);
        } catch (Exception e) {
            // Log and swallow — scheduler must not crash the JVM
            log.error("Card expiration job failed", e);
        }
    }
}
