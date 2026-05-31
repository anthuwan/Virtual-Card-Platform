package com.virtual.card.api.v1;

import com.virtual.card.api.v1.dto.CardResponse;
import com.virtual.card.api.v1.dto.CreateCardRequest;
import com.virtual.card.api.v1.dto.MoneyOperationRequest;
import com.virtual.card.api.v1.dto.TransactionResponse;
import com.virtual.card.domain.card.Card;
import com.virtual.card.domain.card.CardService;
import com.virtual.card.domain.transaction.Transaction;
import com.virtual.card.scheduler.CardExpirationProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for virtual card operations.
 *
 * <p>API versioned under {@code /api/v1} to allow non-breaking evolution.
 * The {@code Idempotency-Key} header is supported on mutating operations
 * (spend, top-up) to enable safe client-side retries.
 */
@RestController
@RequestMapping("/api/v1/cards")
@Tag(name = "Cards", description = "Virtual card issuance and management")
public class CardController {

    private static final Logger log = LoggerFactory.getLogger(CardController.class);
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final CardService cardService;
    private final CardExpirationProperties expirationProperties;

    public CardController(CardService cardService, CardExpirationProperties expirationProperties) {
        this.cardService = cardService;
        this.expirationProperties = expirationProperties;
    }

    // ─── Card Lifecycle ───────────────────────────────────────────────────────

    @PostMapping
    @Operation(
            summary = "Issue a new virtual card",
            description = "Creates a new ACTIVE virtual card with an initial balance. " +
                    "If expiresAt is omitted, defaults to the configured period (default: 3 years).",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Card created"),
                    @ApiResponse(responseCode = "400", description = "Validation error")
            }
    )
    public ResponseEntity<CardResponse> createCard(@Valid @RequestBody CreateCardRequest request) {
        LocalDateTime expiresAt = request.expiresAt() != null
                ? request.expiresAt()
                : LocalDateTime.now().plusMonths(expirationProperties.defaultExpiryMonths());

        Card card = cardService.createCard(request.cardholderName(), request.initialBalance(), expiresAt);
        log.info("Card issued via API: id={}", card.id());

        return ResponseEntity
                .created(URI.create("/api/v1/cards/" + card.id()))
                .body(CardResponse.from(card));
    }

    @GetMapping("/{cardId}")
    @Operation(
            summary = "Retrieve card details",
            description = "Returns current card information including balance and status.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Card found"),
                    @ApiResponse(responseCode = "404", description = "Card not found")
            }
    )
    public CardResponse getCard(@PathVariable UUID cardId) {
        return CardResponse.from(cardService.getCard(cardId));
    }

    @PatchMapping("/{cardId}/block")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Block a card", description = "Suspends an ACTIVE card. Spending and top-ups are disabled.")
    public CardResponse blockCard(@PathVariable UUID cardId) {
        return CardResponse.from(cardService.blockCard(cardId));
    }

    @PatchMapping("/{cardId}/close")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Close a card", description = "Permanently closes a card. This action is irreversible.")
    public CardResponse closeCard(@PathVariable UUID cardId) {
        return CardResponse.from(cardService.closeCard(cardId));
    }

    // ─── Financial Operations ─────────────────────────────────────────────────

    @PostMapping("/{cardId}/spend")
    @Operation(
            summary = "Spend from a card",
            description = "Deducts the specified amount from the card balance. " +
                    "If funds are insufficient, the transaction is recorded as DECLINED (not an error). " +
                    "Supports idempotent retries via the Idempotency-Key header.",
            parameters = {
                    @Parameter(name = IDEMPOTENCY_KEY_HEADER, description = "Client-generated unique key for idempotent retries",
                            example = "txn-uuid-4321", schema = @Schema(type = "string"))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "Transaction processed (check status field for SUCCESSFUL or DECLINED)"),
                    @ApiResponse(responseCode = "400", description = "Validation error"),
                    @ApiResponse(responseCode = "404", description = "Card not found"),
                    @ApiResponse(responseCode = "422", description = "Card is not ACTIVE")
            }
    )
    public TransactionResponse spend(
            @PathVariable UUID cardId,
            @Valid @RequestBody MoneyOperationRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {

        Transaction tx = cardService.spend(cardId, request.amount(), idempotencyKey, request.description());
        return TransactionResponse.from(tx);
    }

    @PostMapping("/{cardId}/top-up")
    @Operation(
            summary = "Top up a card",
            description = "Adds funds to an ACTIVE card. " +
                    "Supports idempotent retries via the Idempotency-Key header.",
            parameters = {
                    @Parameter(name = IDEMPOTENCY_KEY_HEADER, description = "Client-generated unique key for idempotent retries",
                            example = "topup-uuid-1234", schema = @Schema(type = "string"))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "Funds added successfully"),
                    @ApiResponse(responseCode = "400", description = "Validation error"),
                    @ApiResponse(responseCode = "404", description = "Card not found"),
                    @ApiResponse(responseCode = "422", description = "Card is not ACTIVE")
            }
    )
    public TransactionResponse topUp(
            @PathVariable UUID cardId,
            @Valid @RequestBody MoneyOperationRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {

        Transaction tx = cardService.topUp(cardId, request.amount(), idempotencyKey, request.description());
        return TransactionResponse.from(tx);
    }

    @GetMapping("/{cardId}/transactions")
    @Operation(
            summary = "Retrieve transaction history",
            description = "Returns all transactions for the card in descending chronological order.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Transaction list (may be empty)"),
                    @ApiResponse(responseCode = "404", description = "Card not found")
            }
    )
    public List<TransactionResponse> getTransactions(@PathVariable UUID cardId) {
        return cardService.getTransactionHistory(cardId).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
