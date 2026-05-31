package com.virtual.card.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "Request to issue a new virtual card")
public record CreateCardRequest(

        @NotBlank(message = "Cardholder name is required")
        @Size(max = 255, message = "Cardholder name must not exceed 255 characters")
        @Schema(description = "Full name of the cardholder", example = "Jane Smith")
        String cardholderName,

        @NotNull(message = "Initial balance is required")
        @DecimalMin(value = "0.00", message = "Initial balance must be non-negative")
        @Digits(integer = 15, fraction = 4, message = "Balance must have at most 15 integer digits and 4 decimal places")
        @Schema(description = "Initial balance to load onto the card", example = "100.00")
        BigDecimal initialBalance,

        @FutureOrPresent(message = "Expiry date must be in the present or future")
        @Schema(description = "Optional custom expiry date/time. Defaults to 3 years from now if omitted.",
                example = "2029-01-01T00:00:00")
        LocalDateTime expiresAt
) {}
