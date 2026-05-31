package com.virtual.card.infrastructure.jooq;

import com.virtual.card.domain.transaction.Transaction;
import com.virtual.card.domain.transaction.TransactionRepository;
import com.virtual.card.domain.transaction.TransactionStatus;
import com.virtual.card.domain.transaction.TransactionType;
import com.malta.card.infrastructure.jooq.generated.tables.records.TransactionsRecord;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.malta.card.infrastructure.jooq.generated.Tables.TRANSACTIONS;

/**
 * JOOQ-backed implementation of {@link TransactionRepository}.
 *
 * <p>Idempotency is enforced at two layers:
 * <ol>
 *   <li>The service layer checks for an existing record before acquiring a lock.</li>
 *   <li>A {@code UNIQUE} partial index on {@code idempotency_key} in the database
 *       provides a safety net against any gap between the check and the insert.</li>
 * </ol>
 */
@Repository
public class JooqTransactionRepository implements TransactionRepository {

    private static final Logger log = LoggerFactory.getLogger(JooqTransactionRepository.class);

    private final DSLContext dsl;

    public JooqTransactionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Transaction create(UUID cardId, TransactionType type, BigDecimal amount,
                              TransactionStatus status, String idempotencyKey, String description) {
        TransactionsRecord record = dsl.insertInto(TRANSACTIONS)
                .set(TRANSACTIONS.CARD_ID, cardId)
                .set(TRANSACTIONS.TYPE, type.name())
                .set(TRANSACTIONS.AMOUNT, amount)
                .set(TRANSACTIONS.STATUS, status.name())
                .set(TRANSACTIONS.IDEMPOTENCY_KEY, idempotencyKey)
                .set(TRANSACTIONS.DESCRIPTION, description)
                .returning()
                .fetchOne();

        assert record != null;
        log.debug("Inserted transaction: id={}, type={}, status={}", record.getId(), type, status);
        return toTransaction(record);
    }

    @Override
    public List<Transaction> findByCardId(UUID cardId) {
        return dsl.selectFrom(TRANSACTIONS)
                .where(TRANSACTIONS.CARD_ID.eq(cardId))
                .orderBy(TRANSACTIONS.CREATED_AT.desc())
                .fetch()
                .map(this::toTransaction);
    }

    @Override
    public Optional<Transaction> findByIdempotencyKey(String idempotencyKey) {
        return dsl.selectFrom(TRANSACTIONS)
                .where(TRANSACTIONS.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional()
                .map(this::toTransaction);
    }

    // --- Mapping ---

    private Transaction toTransaction(TransactionsRecord r) {
        return new Transaction(
                r.getId(),
                r.getCardId(),
                TransactionType.valueOf(r.getType()),
                r.getAmount(),
                TransactionStatus.valueOf(r.getStatus()),
                r.getIdempotencyKey(),
                r.getDescription(),
                r.getCreatedAt()
        );
    }
}
