package com.virtual.card.domain.card;

/**
 * Lifecycle states of a virtual card.
 *
 * <p>Valid transitions:
 * <pre>
 *   ACTIVE  → BLOCKED  (card suspended, reversible)
 *   ACTIVE  → CLOSED   (card permanently closed)
 *   ACTIVE  → EXPIRED  (card passed expiry date, set by scheduler)
 *   BLOCKED → ACTIVE   (card reactivated)
 *   BLOCKED → CLOSED   (card permanently closed from suspended state)
 * </pre>
 *
 * <p>Only ACTIVE cards can perform financial operations (spend / top-up).
 */
public enum CardStatus {
    ACTIVE,
    BLOCKED,
    CLOSED,
    EXPIRED
}
