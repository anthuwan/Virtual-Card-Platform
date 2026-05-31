package com.virtual.card.domain.card;

import com.virtual.card.domain.transaction.Transaction;
import com.virtual.card.domain.transaction.TransactionRepository;
import com.virtual.card.domain.transaction.TransactionStatus;
import com.virtual.card.domain.transaction.TransactionType;
import com.virtual.card.exception.CardNotFoundException;
import com.virtual.card.exception.CardNotActiveException;
import com.virtual.card.infrastructure.audit.TransactionAuditEvent;
import com.virtual.card.infrastructure.metrics.CardMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Core application service orchestrating card lifecycle and financial operations.
 *
 * <h2>Concurrency Strategy</h2>
 * <p>Spend and top-up both call {@link CardRepository#findByIdForUpdate} which issues
 * a {@code SELECT ... FOR UPDATE} at the database level. This ensures that concurrent
 * requests for the same card are serialised — only one transaction holds the row lock
 * at a time, preventing lost updates and double-spend conditions. The JOOQ repository
 * implementation runs within a Spring-managed transaction, so the lock is released at
 * commit/rollback.
 *
 * <h2>Idempotency</h2>
 * <p>Callers may include an {@code Idempotency-Key} header. If a transaction with that
 * key already exists, the original result is returned without re-processing. This allows
 * clients to safely retry on network failures. Both successful and declined transactions
 * are eligible for idempotent replay.
 *
 * <h2>Balance Invariant</h2>
 * <p>The service enforces balance ≥ 0 at the application layer. The database schema
 * also has a {@code CHECK (balance >= 0)} constraint as a defence-in-depth measure.
 */
@Service
@Transactional
public class CardService {

    private static final Logger log = LoggerFactory.getLogger(CardService.class);

    private final CardRepository cardRepository;
    private final TransactionRepository transactionRepository;
    private final CardMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;

    public CardService(CardRepository cardRepository,
                       TransactionRepository transactionRepository,
                       CardMetrics metrics,
                       ApplicationEventPublisher eventPublisher) {
        this.cardRepository = cardRepository;
        this.transactionRepository = transactionRepository;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Issues a new virtual card with the given cardholder name and initial balance.
     * Cards default to ACTIVE status and expire after the configured period.
     */
    public Card createCard(String cardholderName, BigDecimal initialBalance, LocalDateTime expiresAt) {
        log.info("Creating card for cardholder='{}', initialBalance={}", cardholderName, initialBalance);

        Card card = cardRepository.create(cardholderName, initialBalance, expiresAt);

        // Record initial load as a transaction for complete audit trail
        if (initialBalance.compareTo(BigDecimal.ZERO) > 0) {
            transactionRepository.create(
                    card.id(), TransactionType.TOP_UP, initialBalance,
                    TransactionStatus.SUCCESSFUL, null, "Initial card load");
        }

        metrics.incrementCardCreated();
        log.info("Card created: id={}, cardholder='{}'", card.id(), cardholderName);
        return card;
    }

    /**
     * Retrieves current card details. Throws {@link CardNotFoundException} if not found.
     */
    @Transactional(readOnly = true)
    public Card getCard(UUID cardId) {
        log.debug("Fetching card: id={}", cardId);
        return cardRepository.findById(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));
    }

    /**
     * Deducts {@code amount} from the card balance.
     *
     * <p>Declined transactions (insufficient funds, inactive card) are persisted for
     * audit purposes but do not alter the card balance.
     *
     * @param cardId         target card
     * @param amount         positive amount to deduct
     * @param idempotencyKey optional deduplication key
     * @param description    optional human-readable reason
     * @return the resulting transaction (may be DECLINED)
     */
    public Transaction spend(UUID cardId, BigDecimal amount, String idempotencyKey, String description) {
        log.info("Spend request: cardId={}, amount={}, idempotencyKey={}", cardId, amount, idempotencyKey);

        // --- Idempotency check (before acquiring row lock for performance) ---
        if (idempotencyKey != null) {
            var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent spend replay: key={}, txId={}", idempotencyKey, existing.get().id());
                return existing.get();
            }
        }

        // --- Acquire pessimistic lock to serialise concurrent spend/topup on same card ---
        Card card = cardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));

        // --- Card must be ACTIVE ---
        if (!card.isOperational()) {
            log.warn("Spend declined — card not active: cardId={}, status={}", cardId, card.status());
            throw new CardNotActiveException(cardId, card.status());
        }

        // --- Insufficient funds → persist DECLINED transaction (never throw) ---
        if (card.balance().compareTo(amount) < 0) {
            log.warn("Spend declined — insufficient funds: cardId={}, available={}, requested={}",
                    cardId, card.balance(), amount);
            Transaction declined = transactionRepository.create(
                    cardId, TransactionType.SPEND, amount,
                    TransactionStatus.DECLINED, idempotencyKey, description);

            metrics.incrementSpendDeclined();
            eventPublisher.publishEvent(new TransactionAuditEvent(this, declined));
            return declined;
        }

        // --- Deduct balance and record success ---
        BigDecimal newBalance = card.balance().subtract(amount);
        cardRepository.updateBalance(cardId, newBalance);

        Transaction tx = transactionRepository.create(
                cardId, TransactionType.SPEND, amount,
                TransactionStatus.SUCCESSFUL, idempotencyKey, description);

        metrics.incrementSpendSuccess();
        metrics.recordTransactionAmount(amount);
        eventPublisher.publishEvent(new TransactionAuditEvent(this, tx));

        log.info("Spend successful: cardId={}, amount={}, newBalance={}, txId={}",
                cardId, amount, newBalance, tx.id());
        return tx;
    }

    /**
     * Adds {@code amount} to the card balance.
     *
     * @param cardId         target card
     * @param amount         positive amount to add
     * @param idempotencyKey optional deduplication key
     * @param description    optional human-readable reason
     * @return the resulting transaction
     */
    public Transaction topUp(UUID cardId, BigDecimal amount, String idempotencyKey, String description) {
        log.info("TopUp request: cardId={}, amount={}, idempotencyKey={}", cardId, amount, idempotencyKey);

        // --- Idempotency check ---
        if (idempotencyKey != null) {
            var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent topUp replay: key={}, txId={}", idempotencyKey, existing.get().id());
                return existing.get();
            }
        }

        // --- Acquire pessimistic lock ---
        Card card = cardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));

        // --- Card must be ACTIVE ---
        if (!card.isOperational()) {
            log.warn("TopUp rejected — card not active: cardId={}, status={}", cardId, card.status());
            throw new CardNotActiveException(cardId, card.status());
        }

        // --- Credit balance ---
        BigDecimal newBalance = card.balance().add(amount);
        cardRepository.updateBalance(cardId, newBalance);

        Transaction tx = transactionRepository.create(
                cardId, TransactionType.TOP_UP, amount,
                TransactionStatus.SUCCESSFUL, idempotencyKey, description);

        metrics.incrementTopUpSuccess();
        metrics.recordTransactionAmount(amount);
        eventPublisher.publishEvent(new TransactionAuditEvent(this, tx));

        log.info("TopUp successful: cardId={}, amount={}, newBalance={}, txId={}",
                cardId, amount, newBalance, tx.id());
        return tx;
    }

    /**
     * Returns all transactions for a card in descending chronological order.
     */
    @Transactional(readOnly = true)
    public List<Transaction> getTransactionHistory(UUID cardId) {
        log.debug("Fetching transaction history: cardId={}", cardId);
        // Ensure card exists before fetching transactions
        if (cardRepository.findById(cardId).isEmpty()) {
            throw new CardNotFoundException(cardId);
        }
        return transactionRepository.findByCardId(cardId);
    }

    /**
     * Transitions a card to BLOCKED status. Only ACTIVE cards can be blocked.
     */
    public Card blockCard(UUID cardId) {
        log.info("Blocking card: id={}", cardId);
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));

        if (card.status() != CardStatus.ACTIVE) {
            throw new CardNotActiveException(cardId, card.status());
        }
        return cardRepository.updateStatus(cardId, CardStatus.BLOCKED);
    }

    /**
     * Permanently closes a card. Irreversible.
     */
    public Card closeCard(UUID cardId) {
        log.info("Closing card: id={}", cardId);
        cardRepository.findById(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));
        return cardRepository.updateStatus(cardId, CardStatus.CLOSED);
    }

    /**
     * Marks cards as EXPIRED. Called by the scheduler; not exposed via REST.
     */
    public void expireCards(LocalDateTime threshold) {
        List<Card> expiring = cardRepository.findByStatusAndExpiresAtBefore(CardStatus.ACTIVE, threshold);
        expiring.forEach(card -> {
            cardRepository.updateStatus(card.id(), CardStatus.EXPIRED);
            log.info("Card expired: id={}, expiredAt={}", card.id(), card.expiresAt());
            metrics.incrementCardExpired();
        });
        if (!expiring.isEmpty()) {
            log.info("Expired {} cards", expiring.size());
        }
    }
}
