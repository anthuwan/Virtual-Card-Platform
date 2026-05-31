package com.virtual.card.scheduler;

import com.virtual.card.domain.card.CardService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
 * <h2>Distributed locking</h2>
 * <p>{@code @SchedulerLock} ensures only one application instance executes this job
 * at a time (via the {@code shedlock} table). {@code lockAtMostFor} caps the lock
 * duration to 5 minutes — if the JVM crashes mid-run, the lock auto-releases.
 * {@code lockAtLeastFor} prevents another instance from immediately re-running the
 * job within the same cron window, adding safety against clock drift.
 */
@Component
public class CardExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(CardExpirationScheduler.class);

    private final CardService cardService;

    public CardExpirationScheduler(CardService cardService) {
        this.cardService = cardService;
    }

    @Scheduled(cron = "${app.card.expiry-check-cron}")
    @SchedulerLock(name = "cardExpirationJob", lockAtMostFor = "5m", lockAtLeastFor = "1m")
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
