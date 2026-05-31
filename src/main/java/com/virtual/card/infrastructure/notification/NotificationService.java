package com.virtual.card.infrastructure.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Placeholder notification service.
 *
 * <p>All methods are no-ops that log intent. In production, each method would
 * call an email/SMS/push provider (e.g. SendGrid, Twilio, Firebase).
 *
 * <h2>Real implementation steps</h2>
 * <ol>
 *   <li>Inject a notification client (REST or SDK)</li>
 *   <li>Look up cardholder contact details from a user/profile service</li>
 *   <li>Send the appropriate message via the provider</li>
 *   <li>All notification calls should be async — never block the request path</li>
 * </ol>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final BigDecimal LOW_BALANCE_THRESHOLD = new BigDecimal("10.00");

    /**
     * Notifies cardholder that a spend was declined due to insufficient funds.
     * TODO: send push/SMS — "Your spend of £{amount} was declined (insufficient funds)."
     */
    public void notifySpendDeclined(UUID cardId, BigDecimal amount) {
        log.info("[NOTIFICATION-PLACEHOLDER] Would notify: spend DECLINED cardId={}, amount={}", cardId, amount);
    }

    /**
     * Notifies cardholder that a spend was successful.
     * TODO: send push/SMS — "£{amount} spent on your virtual card. Remaining balance: £{balance}."
     */
    public void notifySpendSuccessful(UUID cardId, BigDecimal amount, BigDecimal remainingBalance) {
        log.info("[NOTIFICATION-PLACEHOLDER] Would notify: spend OK cardId={}, amount={}, balance={}",
                cardId, amount, remainingBalance);
    }

    /**
     * Notifies cardholder when balance drops below {@link #LOW_BALANCE_THRESHOLD}.
     * TODO: send push/email — "Your virtual card balance is low: £{balance}."
     */
    public void notifyLowBalance(UUID cardId, BigDecimal balance) {
        if (balance.compareTo(LOW_BALANCE_THRESHOLD) < 0) {
            log.info("[NOTIFICATION-PLACEHOLDER] Would notify: LOW BALANCE cardId={}, balance={}", cardId, balance);
        }
    }

    /**
     * Notifies cardholder that their card has been blocked.
     * TODO: send push/email — "Your virtual card has been blocked. Contact support to unblock."
     */
    public void notifyCardBlocked(UUID cardId) {
        log.info("[NOTIFICATION-PLACEHOLDER] Would notify: card BLOCKED cardId={}", cardId);
    }

    /**
     * Notifies cardholder that their card has expired.
     * TODO: send email — "Your virtual card has expired. Please request a new one."
     */
    public void notifyCardExpired(UUID cardId) {
        log.info("[NOTIFICATION-PLACEHOLDER] Would notify: card EXPIRED cardId={}", cardId);
    }

    /**
     * Notifies cardholder of a successful top-up.
     * TODO: send push/SMS — "£{amount} added to your virtual card. New balance: £{balance}."
     */
    public void notifyTopUpSuccessful(UUID cardId, BigDecimal amount, BigDecimal newBalance) {
        log.info("[NOTIFICATION-PLACEHOLDER] Would notify: top-up OK cardId={}, amount={}, balance={}",
                cardId, amount, newBalance);
    }
}
