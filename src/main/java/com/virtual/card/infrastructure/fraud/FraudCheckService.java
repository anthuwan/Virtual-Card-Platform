package com.virtual.card.infrastructure.fraud;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Placeholder fraud detection service.
 *
 * <p>Currently always returns {@code false} (no fraud detected).
 * In production, this would call an internal rules engine or an external
 * fraud provider (e.g. Stripe Radar, Featurespace, custom ML model).
 *
 * <h2>Real implementation — signals to check</h2>
 * <ul>
 *   <li>Velocity — too many spends in a short window (e.g. 5 spends in 1 minute)</li>
 *   <li>Amount anomaly — amount far exceeds the card's historical average</li>
 *   <li>Geo mismatch — spend location inconsistent with cardholder's usual region</li>
 *   <li>Odd hours — transaction at unusual time for this cardholder</li>
 *   <li>Declined pattern — multiple declines followed by a large successful spend</li>
 * </ul>
 *
 * <h2>Integration steps</h2>
 * <ol>
 *   <li>Inject a REST client or Kafka consumer for the fraud provider</li>
 *   <li>Call {@code fraudClient.evaluate(cardId, amount, metadata)}</li>
 *   <li>Return {@code true} if risk score exceeds configured threshold</li>
 *   <li>Callers record the transaction as {@code DECLINED} with reason {@code FRAUD_SUSPECTED}</li>
 * </ol>
 */
@Service
public class FraudCheckService {

    private static final Logger log = LoggerFactory.getLogger(FraudCheckService.class);

    /**
     * Returns {@code true} if the spend looks fraudulent and should be blocked.
     *
     * <p>Wrapped in a Resilience4j circuit breaker ({@code fraudCheck}).
     * If the fraud service is slow or failing, the circuit opens after
     * {@code slidingWindowSize} failures and falls back to {@link #fraudFallback}
     * — defaulting to {@code false} (allow spend) to prevent service disruption.
     *
     * <p>TODO: call fraud provider API / rules engine here.
     */
    @CircuitBreaker(name = "fraudCheck", fallbackMethod = "fraudFallback")
    public boolean isSuspicious(UUID cardId, BigDecimal amount) {
        log.debug("[FRAUD-PLACEHOLDER] Fraud check skipped: cardId={}, amount={} — always CLEAN", cardId, amount);
        // TODO: return fraudClient.getRiskScore(cardId, amount) > RISK_THRESHOLD;
        return false;
    }

    /**
     * Checks velocity — too many transactions in a short window.
     *
     * <p>Circuit breaker protected — falls back to {@code false} if service is unavailable.
     *
     * <p>TODO: query recent transaction count from Redis or DB and compare to threshold.
     */
    @CircuitBreaker(name = "fraudCheck", fallbackMethod = "velocityFallback")
    public boolean isVelocityBreached(UUID cardId) {
        log.debug("[FRAUD-PLACEHOLDER] Velocity check skipped: cardId={}", cardId);
        // TODO: count transactions in last N seconds and compare to limit
        return false;
    }

    // ─── Circuit breaker fallbacks ────────────────────────────────────────────

    /**
     * Fallback when fraud service circuit is OPEN.
     * Defaults to allowing the spend — availability over strict fraud blocking.
     * Alert should fire in production when this is invoked.
     */
    private boolean fraudFallback(UUID cardId, BigDecimal amount, Throwable ex) {
        log.warn("[FRAUD-CB-FALLBACK] Circuit open — skipping fraud check: cardId={}, error={}", cardId, ex.getMessage());
        // TODO: increment a "fraud_check_skipped" metric and trigger an alert
        return false; // fail-open: allow spend when fraud service is down
    }

    private boolean velocityFallback(UUID cardId, Throwable ex) {
        log.warn("[FRAUD-CB-FALLBACK] Circuit open — skipping velocity check: cardId={}, error={}", cardId, ex.getMessage());
        return false;
    }
}
