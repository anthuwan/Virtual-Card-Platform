package com.virtual.card.domain.card;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain model representing a virtual card.
 *
 * <p>Intentionally kept as a plain record (no JPA annotations) to keep the domain
 * layer free of infrastructure concerns. The JOOQ repository layer handles persistence
 * and maps database records to/from this type.
 *
 * <p>Using {@code BigDecimal} for balance to avoid floating-point precision issues
 * common in financial applications.
 */
public record Card(
        UUID id,
        String cardholderName,
        BigDecimal balance,
        CardStatus status,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * Returns true if this card can perform financial operations.
     * Only ACTIVE cards are permitted to spend or receive top-ups.
     */
    public boolean isOperational() {
        return status == CardStatus.ACTIVE;
    }

    /**
     * Returns true if the card has expired based on the given point in time.
     */
    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }
}
