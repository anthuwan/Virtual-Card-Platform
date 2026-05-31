package com.virtual.card.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

    private final UUID cardId;
    private final BigDecimal available;
    private final BigDecimal requested;

    public InsufficientFundsException(UUID cardId, BigDecimal available, BigDecimal requested) {
        super("Insufficient funds on card " + cardId
                + ": requested=" + requested + ", available=" + available);
        this.cardId = cardId;
        this.available = available;
        this.requested = requested;
    }

    public UUID getCardId() { return cardId; }
    public BigDecimal getAvailable() { return available; }
    public BigDecimal getRequested() { return requested; }
}
