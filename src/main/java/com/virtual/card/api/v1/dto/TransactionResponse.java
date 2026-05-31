package com.virtual.card.api.v1.dto;

import com.virtual.card.domain.transaction.Transaction;
import com.virtual.card.domain.transaction.TransactionStatus;
import com.virtual.card.domain.transaction.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Transaction record")
public record TransactionResponse(

        @Schema(description = "Unique transaction identifier")
        UUID id,

        @Schema(description = "ID of the card this transaction belongs to")
        UUID cardId,

        @Schema(description = "SPEND or TOP_UP")
        TransactionType type,

        @Schema(description = "Transaction amount")
        BigDecimal amount,

        @Schema(description = "SUCCESSFUL, DECLINED, or PENDING")
        TransactionStatus status,

        @Schema(description = "Optional description")
        String description,

        @Schema(description = "Transaction creation timestamp")
        LocalDateTime createdAt
) {
    public static TransactionResponse from(Transaction tx) {
        return new TransactionResponse(
                tx.id(),
                tx.cardId(),
                tx.type(),
                tx.amount(),
                tx.status(),
                tx.description(),
                tx.createdAt()
        );
    }
}
