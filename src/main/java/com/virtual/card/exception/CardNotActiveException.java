package com.virtual.card.exception;

import com.virtual.card.domain.card.CardStatus;
import java.util.UUID;

public class CardNotActiveException extends RuntimeException {

    private final UUID cardId;
    private final CardStatus currentStatus;

    public CardNotActiveException(UUID cardId, CardStatus currentStatus) {
        super("Card " + cardId + " is not active (current status: " + currentStatus + ")");
        this.cardId = cardId;
        this.currentStatus = currentStatus;
    }

    public UUID getCardId() { return cardId; }
    public CardStatus getCurrentStatus() { return currentStatus; }
}
