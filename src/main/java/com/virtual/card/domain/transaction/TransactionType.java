package com.virtual.card.domain.transaction;

/**
 * Classifies the direction of funds movement on a card.
 */
public enum TransactionType {
    /** Funds deducted from the card balance. */
    SPEND,
    /** Funds added to the card balance. */
    TOP_UP
}
