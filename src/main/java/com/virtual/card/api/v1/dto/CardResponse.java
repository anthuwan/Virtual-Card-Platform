package com.virtual.card.api.v1.dto;

import com.virtual.card.domain.card.Card;
import com.virtual.card.domain.card.CardStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Virtual card details")
public record CardResponse(

        @Schema(description = "Unique card identifier")
        UUID id,

        @Schema(description = "Full name of the cardholder")
        String cardholderName,

        @Schema(description = "Current balance")
        BigDecimal balance,

        @Schema(description = "Card status: ACTIVE, BLOCKED, CLOSED, or EXPIRED")
        CardStatus status,

        @Schema(description = "Card expiry date/time")
        LocalDateTime expiresAt,

        @Schema(description = "Card creation timestamp")
        LocalDateTime createdAt
) {
    public static CardResponse from(Card card) {
        return new CardResponse(
                card.id(),
                card.cardholderName(),
                card.balance(),
                card.status(),
                card.expiresAt(),
                card.createdAt()
        );
    }
}
