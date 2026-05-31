package com.virtual.card.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "Request for a monetary operation (spend or top-up)")
public record MoneyOperationRequest(

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 15, fraction = 4, message = "Amount must have at most 15 integer digits and 4 decimal places")
        @Schema(description = "Amount to deduct or add", example = "25.00")
        BigDecimal amount,

        @Size(max = 500)
        @Schema(description = "Optional human-readable description for the operation",
                example = "Online purchase - merchant XYZ")
        String description
) {}
