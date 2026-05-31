package com.virtual.card.infrastructure.jooq;

import com.virtual.card.domain.card.Card;
import com.virtual.card.domain.card.CardRepository;
import com.virtual.card.domain.card.CardStatus;
import com.malta.card.infrastructure.jooq.generated.tables.records.CardsRecord;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.malta.card.infrastructure.jooq.generated.Tables.CARDS;

/**
 * JOOQ-backed implementation of {@link CardRepository}.
 *
 * <p>Key design choices:
 * <ul>
 *   <li><b>SELECT FOR UPDATE</b> — {@link #findByIdForUpdate} acquires a pessimistic
 *       write lock on the card row. This is the primary mechanism ensuring that
 *       concurrent spend/topup operations on the same card are serialised at the
 *       database level, preventing race conditions without application-level locking.</li>
 *   <li><b>Explicit column mapping</b> — results are mapped manually to the domain
 *       record, keeping the domain free of JOOQ-generated type dependencies.</li>
 *   <li><b>No lazy loading</b> — unlike JPA, every query fetches exactly what is
 *       needed. There are no hidden N+1 risks.</li>
 * </ul>
 */
@Repository
public class JooqCardRepository implements CardRepository {

    private static final Logger log = LoggerFactory.getLogger(JooqCardRepository.class);

    private final DSLContext dsl;

    public JooqCardRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Card create(String cardholderName, BigDecimal initialBalance, LocalDateTime expiresAt) {
        CardsRecord record = dsl.insertInto(CARDS)
                .set(CARDS.CARDHOLDER_NAME, cardholderName)
                .set(CARDS.BALANCE, initialBalance)
                .set(CARDS.STATUS, CardStatus.ACTIVE.name())
                .set(CARDS.EXPIRES_AT, expiresAt)
                .returning()
                .fetchOne();

        assert record != null;
        log.debug("Inserted card: id={}", record.getId());
        return toCard(record);
    }

    @Override
    public Optional<Card> findById(UUID id) {
        return dsl.selectFrom(CARDS)
                .where(CARDS.ID.eq(id))
                .fetchOptional()
                .map(this::toCard);
    }

    @Override
    public Optional<Card> findByIdForUpdate(UUID id) {
        // SELECT ... FOR UPDATE — acquires a row-level exclusive lock.
        // The lock is held for the duration of the enclosing transaction,
        // ensuring atomicity of balance checks and updates.
        return dsl.selectFrom(CARDS)
                .where(CARDS.ID.eq(id))
                .forUpdate()
                .fetchOptional()
                .map(this::toCard);
    }

    @Override
    public Card updateBalance(UUID id, BigDecimal newBalance) {
        CardsRecord record = dsl.update(CARDS)
                .set(CARDS.BALANCE, newBalance)
                .set(CARDS.UPDATED_AT, LocalDateTime.now())
                .where(CARDS.ID.eq(id))
                .returning()
                .fetchOne();

        assert record != null;
        log.debug("Updated balance: cardId={}, newBalance={}", id, newBalance);
        return toCard(record);
    }

    @Override
    public Card updateStatus(UUID id, CardStatus status) {
        CardsRecord record = dsl.update(CARDS)
                .set(CARDS.STATUS, status.name())
                .set(CARDS.UPDATED_AT, LocalDateTime.now())
                .where(CARDS.ID.eq(id))
                .returning()
                .fetchOne();

        assert record != null;
        log.debug("Updated status: cardId={}, status={}", id, status);
        return toCard(record);
    }

    @Override
    public List<Card> findByStatusAndExpiresAtBefore(CardStatus status, LocalDateTime threshold) {
        return dsl.selectFrom(CARDS)
                .where(CARDS.STATUS.eq(status.name())
                        .and(CARDS.EXPIRES_AT.isNotNull())
                        .and(CARDS.EXPIRES_AT.lt(threshold)))
                .fetch()
                .map(this::toCard);
    }

    // --- Mapping ---

    private Card toCard(CardsRecord r) {
        return new Card(
                r.getId(),
                r.getCardholderName(),
                r.getBalance(),
                CardStatus.valueOf(r.getStatus()),
                r.getExpiresAt(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
