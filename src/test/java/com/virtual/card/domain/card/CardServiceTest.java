package com.virtual.card.domain.card;

import com.virtual.card.domain.transaction.Transaction;
import com.virtual.card.domain.transaction.TransactionRepository;
import com.virtual.card.domain.transaction.TransactionStatus;
import com.virtual.card.domain.transaction.TransactionType;
import com.virtual.card.exception.CardNotFoundException;
import com.virtual.card.exception.CardNotActiveException;
import com.virtual.card.infrastructure.async.CardEventProcessor;
import com.virtual.card.infrastructure.cache.CardCacheService;
import com.virtual.card.infrastructure.fraud.FraudCheckService;
import com.virtual.card.infrastructure.metrics.CardMetrics;
import com.virtual.card.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CardService")
class CardServiceTest {

    @Mock CardRepository cardRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock CardMetrics metrics;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock CardCacheService cardCacheService;
    @Mock FraudCheckService fraudCheckService;
    @Mock CardEventProcessor cardEventProcessor;
    @Mock OutboxService outboxService;

    @InjectMocks CardService cardService;

    private UUID cardId;
    private Card activeCard;

    @BeforeEach
    void setUp() {
        cardId = UUID.randomUUID();
        activeCard = new Card("Jane Smith", new BigDecimal("100.00"),
                CardStatus.ACTIVE, LocalDateTime.now().plusYears(3));
    }

    // ─── createCard ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createCard")
    class CreateCard {

        @Test
        @DisplayName("saves card and records initial load transaction")
        void createsCard() {
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

            Card result = cardService.createCard("Jane Smith", new BigDecimal("100.00"),
                    LocalDateTime.now().plusYears(3), "user-123");

            assertThat(result.getCardholderName()).isEqualTo("Jane Smith");
            assertThat(result.getBalance()).isEqualByComparingTo("100.00");
            verify(cardRepository).save(any(Card.class));
            verify(transactionRepository).save(any(Transaction.class)); // initial load
            verify(metrics).incrementCardCreated();
        }

        @Test
        @DisplayName("does not record initial transaction for zero balance")
        void noTransactionForZeroBalance() {
            when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

            cardService.createCard("Jane Smith", BigDecimal.ZERO, LocalDateTime.now().plusYears(3), "user-123");

            verify(transactionRepository, never()).save(any());
        }
    }

    // ─── spend ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("spend")
    class Spend {

        @Test
        @DisplayName("deducts amount from balance on success")
        void deductsBalance() {
            when(cardRepository.findByIdForUpdate(cardId)).thenReturn(Optional.of(activeCard));
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Transaction result = cardService.spend(cardId, new BigDecimal("50.00"), null, null);

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
            assertThat(result.getType()).isEqualTo(TransactionType.SPEND);
            assertThat(activeCard.getBalance()).isEqualByComparingTo("50.00"); // dirty-check update
            verify(metrics).incrementSpendSuccess();
        }

        @Test
        @DisplayName("returns DECLINED transaction when funds insufficient")
        void declinesOnInsufficientFunds() {
            Card lowCard = new Card("Jane", new BigDecimal("10.00"),
                    CardStatus.ACTIVE, LocalDateTime.now().plusYears(3));
            when(cardRepository.findByIdForUpdate(cardId)).thenReturn(Optional.of(lowCard));
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Transaction result = cardService.spend(cardId, new BigDecimal("50.00"), null, null);

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.DECLINED);
            assertThat(lowCard.getBalance()).isEqualByComparingTo("10.00"); // balance unchanged
            verify(metrics).incrementSpendDeclined();
        }

        @Test
        @DisplayName("throws CardNotFoundException when card does not exist")
        void throwsWhenCardNotFound() {
            when(cardRepository.findByIdForUpdate(cardId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.spend(cardId, new BigDecimal("10.00"), null, null))
                    .isInstanceOf(CardNotFoundException.class);
        }

        @Test
        @DisplayName("throws CardNotActiveException when card is blocked")
        void throwsWhenCardBlocked() {
            Card blockedCard = new Card("Jane", new BigDecimal("100.00"),
                    CardStatus.BLOCKED, null);
            when(cardRepository.findByIdForUpdate(cardId)).thenReturn(Optional.of(blockedCard));

            assertThatThrownBy(() -> cardService.spend(cardId, new BigDecimal("10.00"), null, null))
                    .isInstanceOf(CardNotActiveException.class);

            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("returns existing transaction on duplicate idempotency key")
        void idempotentSpend() {
            String key = "idem-key-123";
            Transaction existingTx = buildTransaction(TransactionStatus.SUCCESSFUL, "50.00");
            when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existingTx));

            Transaction result = cardService.spend(cardId, new BigDecimal("50.00"), key, null);

            assertThat(result).isSameAs(existingTx);
            verify(cardRepository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("balance cannot go below zero — boundary condition")
        void balanceNeverGoesBelowZero() {
            Card exactCard = new Card("Jane Smith", new BigDecimal("50.00"),
                    CardStatus.ACTIVE, LocalDateTime.now().plusYears(3));
            when(cardRepository.findByIdForUpdate(cardId)).thenReturn(Optional.of(exactCard));
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Transaction result = cardService.spend(cardId, new BigDecimal("50.00"), null, null);

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
            assertThat(exactCard.getBalance()).isEqualByComparingTo("0.00");
        }
    }

    // ─── topUp ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("topUp")
    class TopUp {

        @Test
        @DisplayName("adds amount to balance")
        void addsToBalance() {
            when(cardRepository.findByIdForUpdate(cardId)).thenReturn(Optional.of(activeCard));
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Transaction result = cardService.topUp(cardId, new BigDecimal("200.00"), null, null);

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
            assertThat(activeCard.getBalance()).isEqualByComparingTo("300.00"); // 100 + 200
            verify(metrics).incrementTopUpSuccess();
        }

        @Test
        @DisplayName("throws CardNotActiveException on closed card")
        void throwsWhenClosed() {
            Card closedCard = new Card("Jane", new BigDecimal("100.00"),
                    CardStatus.CLOSED, null);
            when(cardRepository.findByIdForUpdate(cardId)).thenReturn(Optional.of(closedCard));

            assertThatThrownBy(() -> cardService.topUp(cardId, new BigDecimal("10.00"), null, null))
                    .isInstanceOf(CardNotActiveException.class);
        }

        @Test
        @DisplayName("returns existing transaction on duplicate idempotency key")
        void idempotentTopUp() {
            String key = "topup-idem-456";
            Transaction existingTx = buildTransaction(TransactionStatus.SUCCESSFUL, "200.00");
            when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existingTx));

            Transaction result = cardService.topUp(cardId, new BigDecimal("200.00"), key, null);

            assertThat(result).isSameAs(existingTx);
            verify(cardRepository, never()).findByIdForUpdate(any());
        }
    }

    // ─── getTransactionHistory ────────────────────────────────────────────────

    @Nested
    @DisplayName("getTransactionHistory")
    class GetTransactionHistory {

        @Test
        @DisplayName("returns transactions for existing card")
        void returnsHistory() {
            when(cardRepository.existsById(cardId)).thenReturn(true);
            when(cardCacheService.getCard(cardId)).thenReturn(activeCard);
            when(transactionRepository.findByCardId(cardId)).thenReturn(List.of());

            List<Transaction> result = cardService.getTransactionHistory(cardId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("throws CardNotFoundException for unknown card")
        void throwsForUnknownCard() {
            when(cardRepository.existsById(cardId)).thenReturn(false);
            when(cardCacheService.getCard(cardId)).thenThrow(new CardNotFoundException(cardId));

            assertThatThrownBy(() -> cardService.getTransactionHistory(cardId))
                    .isInstanceOf(CardNotFoundException.class);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Transaction buildTransaction(TransactionStatus status, String amount) {
        Card card = new Card("Jane Smith", new BigDecimal(amount),
                CardStatus.ACTIVE, LocalDateTime.now().plusYears(3));
        return new Transaction(card, TransactionType.SPEND, new BigDecimal(amount), status, null, null);
    }
}
