package com.virtual.card.api.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Standard error response envelope")
public record ErrorResponse(

        @Schema(description = "Machine-readable error code", example = "CARD_NOT_FOUND")
        String error,

        @Schema(description = "Human-readable error message")
        String message,

        @Schema(description = "Field-level validation errors, if applicable")
        List<FieldError> fieldErrors,

        @Schema(description = "Timestamp of the error")
        LocalDateTime timestamp
) {
    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, List.of(), LocalDateTime.now());
    }

    public static ErrorResponse withFieldErrors(String error, String message, List<FieldError> fieldErrors) {
        return new ErrorResponse(error, message, fieldErrors, LocalDateTime.now());
    }

    @Schema(description = "A single field-level validation error")
    public record FieldError(String field, String message) {}
}
