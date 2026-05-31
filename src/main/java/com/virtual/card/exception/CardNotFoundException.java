package com.virtual.card.exception;

import java.util.UUID;

public class CardNotFoundException extends RuntimeException {

    private final UUID cardId;

    public CardNotFoundException(UUID cardId) {
        super("Card not found: " + cardId);
        this.cardId = cardId;
    }

    public UUID getCardId() {
        return cardId;
    }
}
