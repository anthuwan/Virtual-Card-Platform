package com.virtual.card.domain.card;

import com.virtual.card.domain.transaction.Transaction;
import com.virtual.card.domain.transaction.TransactionRepository;
import com.virtual.card.domain.transaction.TransactionStatus;
import com.virtual.card.domain.transaction.TransactionType;
import com.virtual.card.exception.CardNotFoundException;
import com.virtual.card.exception.CardNotActiveException;
import com.virtual.card.infrastructure.async.CardEventProcessor;
import com.virtual.card.infrastructure.audit.TransactionAuditEvent;
import com.virtual.card.infrastructure.cache.CardCacheService;
import com.virtual.card.infrastructure.fraud.FraudCheckService;
import com.virtual.card.infrastructure.metrics.CardMetrics;
import com.virtual.card.infrastructure.outbox.OutboxService;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
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
 * <p>Spend and top-up both call {@link CardRepository#findByIdForUpdate} which uses
 * {@code @Lock(PESSIMISTIC_WRITE)} — translating to {@code SELECT ... FOR UPDATE}
 * in PostgreSQL. This serialises concurrent requests for the same card at the
 * database level, preventing lost updates and double-spend conditions.
 *
 * <h2>Idempotency</h2>
 * <p>Callers may include an {@code Idempotency-Key} header. If a transaction with that
 * key already exists, the original result is returned without re-processing — safe
 * for client retries on network failures.
 *
 * <h2>Balance Invariant</h2>
 * <p>Balance ≥ 0 is enforced at the service layer. The DB schema also has a
 * {@code CHECK (balance >= 0)} constraint as defence-in-depth.
 */
@Service
@Transactional
public class CardService {

    private static final Logger log = LoggerFactory.getLogger(CardService.class);

    private final CardRepository cardRepository;
    private final TransactionRepository transactionRepository;
    private final CardMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;
    private final CardCacheService cardCacheService;
    private final FraudCheckService fraudCheckService;
    private final CardEventProcessor cardEventProcessor;
    private final OutboxService outboxService;

    public CardService(CardRepository cardRepository,
                       TransactionRepository transactionRepository,
                       CardMetrics metrics,
                       ApplicationEventPublisher eventPublisher,
                       CardCacheService cardCacheService,
                       FraudCheckService fraudCheckService,
                       CardEventProcessor cardEventProcessor,
                       OutboxService outboxService) {
        this.cardRepository = cardRepository;
        this.transactionRepository = transactionRepository;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.cardCacheService = cardCacheService;
        this.fraudCheckService = fraudCheckService;
        this.cardEventProcessor = cardEventProcessor;
        this.outboxService = outboxService;
    }

    /**
     * Issues a new virtual card with the given cardholder name and initial balance.
     */
    public Card createCard(String cardholderName, BigDecimal initialBalance, LocalDateTime expiresAt, String ownerId) {
        log.info("Creating card for cardholder='{}', initialBalance={}, ownerId={}", cardholderName, initialBalance, ownerId);

        Card card = new Card(cardholderName, initialBalance, CardStatus.ACTIVE, expiresAt, ownerId);
        cardRepository.save(card);

        // Record initial load as a transaction for a complete audit trail
        if (initialBalance.compareTo(BigDecimal.ZERO) > 0) {
            Transaction initialLoad = new Transaction(
                    card, TransactionType.TOP_UP, initialBalance,
                    TransactionStatus.SUCCESSFUL, null, "Initial card load");
            transactionRepository.save(initialLoad);
        }

        metrics.incrementCardCreated();
        cardEventProcessor.processCardCreatedEvent(card); // async: Kafka + webhook
        log.info("Card created: id={}, cardholder='{}'", card.getId(), cardholderName);
        return card;
    }

    /**
     * Retrieves current card details. Returns from cache if available; falls back to DB.
     * Throws {@link CardNotFoundException} if not found.
     */
    @Transactional(readOnly = true)
    public Card getCard(UUID cardId) {
        log.debug("Fetching card: id={}", cardId);
        return cardCacheService.getCard(cardId);
    }

    /**
     * Returns all cards owned by the given user, ordered newest-first.
     * Not cached — the list changes on every card creation/status change.
     */
    @Transactional(readOnly = true)
    public List<Card> listCardsByOwner(String ownerId) {
        log.debug("Listing cards for owner: {}", ownerId);
        return cardRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    /**
     * Deducts {@code amount} from the card balance.
     *
     * <p>If funds are insufficient, the transaction is recorded as DECLINED — not thrown
     * as an error. Declined transactions are persisted for audit and idempotency purposes.
     *
     * <p>{@code @Retryable} transparently retries up to 3 times on
     * {@link OptimisticLockException} — which occurs when two concurrent requests
     * read the same {@code @Version} and one loses the race. Exponential backoff
     * (50ms, 100ms) reduces contention on retry.
     */
    @Retryable(
        retryFor = OptimisticLockException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 2)
    )
    public Transaction spend(UUID cardId, BigDecimal amount, String idempotencyKey, String description) {
        log.info("Spend request: cardId={}, amount={}, idempotencyKey={}", cardId, amount, idempotencyKey);

        // Idempotency check — fast path before acquiring lock
        if (idempotencyKey != null) {
            var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent spend replay: key={}, txId={}", idempotencyKey, existing.get().getId());
                return existing.get();
            }
        }

        // Acquire pessimistic write lock — serialises concurrent spends on same card
        Card card = cardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));

        // Card must be ACTIVE
        if (!card.isOperational()) {
            log.warn("Spend rejected — card not active: cardId={}, status={}", cardId, card.getStatus());
            throw new CardNotActiveException(cardId, card.getStatus());
        }

        // Fraud check — placeholder always returns false
        if (fraudCheckService.isSuspicious(cardId, amount) || fraudCheckService.isVelocityBreached(cardId)) {
            log.warn("Spend blocked — fraud suspected: cardId={}, amount={}", cardId, amount);
            Transaction fraudDeclined = new Transaction(
                    card, TransactionType.SPEND, amount,
                    TransactionStatus.DECLINED, idempotencyKey, "Fraud suspected");
            transactionRepository.save(fraudDeclined);
            metrics.incrementSpendDeclined();
            cardEventProcessor.processSpendEvent(fraudDeclined); // async: notify + webhook
            return fraudDeclined;
        }

        // Insufficient funds → record DECLINED, return (no exception)
        if (card.getBalance().compareTo(amount) < 0) {
            log.warn("Spend declined — insufficient funds: cardId={}, available={}, requested={}",
                    cardId, card.getBalance(), amount);
            Transaction declined = new Transaction(
                    card, TransactionType.SPEND, amount,
                    TransactionStatus.DECLINED, idempotencyKey, description);
            transactionRepository.save(declined);

            metrics.incrementSpendDeclined();
            eventPublisher.publishEvent(new TransactionAuditEvent(this, declined));
            cardEventProcessor.processSpendEvent(declined); // async: notify + webhook
            return declined;
        }

        // Deduct balance — JPA dirty checking auto-saves on transaction commit
        BigDecimal newBalance = card.getBalance().subtract(amount);
        card.setBalance(newBalance);

        Transaction tx = new Transaction(
                card, TransactionType.SPEND, amount,
                TransactionStatus.SUCCESSFUL, idempotencyKey, description);
        transactionRepository.save(tx);

        metrics.incrementSpendSuccess();
        metrics.recordTransactionAmount(amount);
        eventPublisher.publishEvent(new TransactionAuditEvent(this, tx));
        cardCacheService.refreshCard(card); // update cache with new balance
        // Outbox: write event in same DB transaction — guarantees at-least-once Kafka delivery
        outboxService.saveEvent("TRANSACTION", tx.getId(), "card.spend.successful",
                String.format("{\"cardId\":\"%s\",\"amount\":\"%s\",\"txId\":\"%s\"}", cardId, amount, tx.getId()));
        cardEventProcessor.processSpendEvent(tx); // async: notify + webhook

        log.info("Spend successful: cardId={}, amount={}, newBalance={}, txId={}",
                cardId, amount, newBalance, tx.getId());
        return tx;
    }

    /**
     * Adds {@code amount} to the card balance.
     *
     * <p>{@code @Retryable} handles {@link OptimisticLockException} transparently —
     * same strategy as {@link #spend}.
     */
    @Retryable(
        retryFor = OptimisticLockException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 2)
    )
    public Transaction topUp(UUID cardId, BigDecimal amount, String idempotencyKey, String description) {
        log.info("TopUp request: cardId={}, amount={}, idempotencyKey={}", cardId, amount, idempotencyKey);

        // Idempotency check
        if (idempotencyKey != null) {
            var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent topUp replay: key={}, txId={}", idempotencyKey, existing.get().getId());
                return existing.get();
            }
        }

        // Acquire pessimistic write lock
        Card card = cardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));

        // Card must be ACTIVE
        if (!card.isOperational()) {
            log.warn("TopUp rejected — card not active: cardId={}, status={}", cardId, card.getStatus());
            throw new CardNotActiveException(cardId, card.getStatus());
        }

        // Credit balance — JPA dirty checking auto-saves on commit
        BigDecimal newBalance = card.getBalance().add(amount);
        card.setBalance(newBalance);

        Transaction tx = new Transaction(
                card, TransactionType.TOP_UP, amount,
                TransactionStatus.SUCCESSFUL, idempotencyKey, description);
        transactionRepository.save(tx);

        metrics.incrementTopUpSuccess();
        metrics.recordTransactionAmount(amount);
        eventPublisher.publishEvent(new TransactionAuditEvent(this, tx));
        cardCacheService.refreshCard(card); // update cache with new balance
        // Outbox: write event in same DB transaction — guarantees at-least-once Kafka delivery
        outboxService.saveEvent("TRANSACTION", tx.getId(), "card.topup.successful",
                String.format("{\"cardId\":\"%s\",\"amount\":\"%s\",\"txId\":\"%s\"}", cardId, amount, tx.getId()));
        cardEventProcessor.processTopUpEvent(tx); // async: notify + webhook

        log.info("TopUp successful: cardId={}, amount={}, newBalance={}, txId={}",
                cardId, amount, newBalance, tx.getId());
        return tx;
    }

    /**
     * Returns all transactions for a card in descending chronological order.
     */
    @Transactional(readOnly = true)
    public List<Transaction> getTransactionHistory(UUID cardId) {
        log.debug("Fetching transaction history: cardId={}", cardId);
        if (!cardRepository.existsById(cardId)) {
            throw new CardNotFoundException(cardId);
        }
        return transactionRepository.findByCardId(cardId);
    }

    /**
     * Blocks an ACTIVE card. Spend and top-up are disabled while blocked.
     */
    public Card blockCard(UUID cardId) {
        log.info("Blocking card: id={}", cardId);
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));
        if (card.getStatus() != CardStatus.ACTIVE) {
            throw new CardNotActiveException(cardId, card.getStatus());
        }
        card.setStatus(CardStatus.BLOCKED);
        Card saved = cardRepository.save(card);
        cardCacheService.refreshCard(saved); // update cache with BLOCKED status
        cardEventProcessor.processCardStatusEvent(saved); // async: Kafka + notify + webhook
        return saved;
    }

    /**
     * Permanently closes a card. Irreversible.
     */
    public Card closeCard(UUID cardId) {
        log.info("Closing card: id={}", cardId);
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));
        card.setStatus(CardStatus.CLOSED);
        Card saved = cardRepository.save(card);
        cardCacheService.refreshCard(saved); // update cache with CLOSED status
        cardEventProcessor.processCardStatusEvent(saved); // async: Kafka + webhook
        return saved;
    }

    /**
     * Marks expired cards as EXPIRED. Called by the scheduler.
     */
    public void expireCards(LocalDateTime threshold) {
        List<Card> expiring = cardRepository.findByStatusAndExpiresAtBefore(CardStatus.ACTIVE, threshold);
        expiring.forEach(card -> {
            card.setStatus(CardStatus.EXPIRED);
            cardRepository.save(card);
            cardCacheService.evictCard(card.getId()); // remove expired card from cache
            cardEventProcessor.processCardStatusEvent(card); // async: notify + webhook
            log.info("Card expired: id={}, expiredAt={}", card.getId(), card.getExpiresAt());
            metrics.incrementCardExpired();
        });
        if (!expiring.isEmpty()) {
            log.info("Expired {} cards", expiring.size());
        }
    }
}
