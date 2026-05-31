package com.virtual.card.domain.card;

import com.virtual.card.domain.transaction.Transaction;
import com.virtual.card.domain.transaction.TransactionRepository;
import com.virtual.card.domain.transaction.TransactionStatus;
import com.virtual.card.domain.transaction.TransactionType;
import com.virtual.card.exception.CardNotFoundException;
import com.virtual.card.exception.CardNotActiveException;
import com.virtual.card.infrastructure.metrics.CardMetrics;
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
    @Mock
    TransactionRepository transactionRepository;
    @Mock CardMetrics metrics;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks CardService cardService;

    private UUID cardId;
    private Card activeCard;

    @BeforeEach
    void setUp() {
        cardId = UUID.randomUUID();
        activeCard = new Card(cardId, "Jane Smith", new BigDecimal("100.00"),
                CardStatus.ACTIVE, LocalDateTime.now().plusYears(3),
                LocalDateTime.now(), LocalDateTime.now());
    }

    // ─── createCard ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createCard")
    class CreateCard {

        @Test
        @DisplayName("creates card with given name and initial balance")
        void createsCard() {
            when(cardRepository.create(anyString(), any(), any())).thenReturn(activeCard);

            Card result = cardService.createCard("Jane Smith", new BigDecimal("100.00"),
                    LocalDateTime.now().plusYears(3));

            assertThat(result.cardholderName()).isEqualTo("Jane Smith");
            assertThat(result.balance()).isEqualByComparingTo("100.00");
            verify(metrics).incrementCardCreated();
        }
    }

    // ─── spend ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("spend")
    class Spend {

        @Test
        @DisplayName("deducts amount from balance on success")
        void deductsBalance() {
            Transaction successTx = buildTransaction(TransactionStatus.SUCCESSFUL, "50.00");
            when(cardRepository.findByIdForUpdate(cardId)).thenReturn(Optional.of(activeCard));
            when(cardRepository.updateBalance(eq(cardId), any())).thenReturn(activeCard);
            when(transactionRepository.create(any(), any(), any(), any(), any(), any())).thenReturn(successTx);

            Transaction result = cardService.spend(cardId, new BigDecimal("50.00"), null, null);

            assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESSFUL);

            // Verify balance update called with correct amount
            ArgumentCaptor<BigDecimal> balanceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(cardRepository).updateBalance(eq(cardId), balanceCaptor.capture());
            assertThat(balanceCaptor.getValue()).isEqualByComparingTo("50.00");

            verify(metrics).incrementSpendSuccess();
        }

        @Test
        @DisplayName("returns DECLINED transaction when funds insufficient")
        void declinesOnInsufficientFunds() {
            Card lowBalanceCard = cardWithBalance("10.00");
            Transaction declinedTx = buildTransaction(TransactionStatus.DECLINED, "50.00");

            when(cardRepository.findByIdForUpdate(cardId)).thenReturn(Optional.of(lowBalanceCard));
            when(transactionRepository.create(any(), any(), any(), eq(TransactionStatus.DECLINED), any(), any()))
                    .thenReturn(declinedTx);

            Transaction result = cardService.spend(cardId, new BigDecimal("50.00"), null, null);

            assertThat(result.status()).isEqualTo(TransactionStatus.DECLINED);
            verify(cardRepository, never()).updateBalance(any(), any());
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
            Card blockedCard = new Card(cardId, "Jane", new BigDecimal("100.00"),
                    CardStatus.BLOCKED, null, LocalDateTime.now(), LocalDateTime.now());
            when(cardRepository.findByIdForUpdate(cardId)).thenReturn(Optional.of(blockedCard));

            assertThatThrownBy(() -> cardService.spend(cardId, new BigDecimal("10.00"), null, null))
                    .isInstanceOf(CardNotActiveException.class);

            verify(transactionRepository, never()).create(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("returns existing transaction on duplicate idempotency key")
        void idempotentSpend() {
            String key = "idem-key-123";
            Transaction existingTx = buildTransaction(TransactionStatus.SUCCESSFUL, "50.00");
            when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existingTx));

            Transaction result = cardService.spend(cardId, new BigDecimal("50.00"), key, null);

            assertThat(result).isSameAs(existingTx);
            // Should NOT acquire lock or touch balance
            verify(cardRepository, never()).findByIdForUpdate(any());
            verify(cardRepository, never()).updateBalance(any(), any());
        }

        @Test
        @DisplayName("balance cannot go below zero — boundary condition")
        void balanceNeverGoesBelowZero() {
            Card exactCard = cardWithBalance("50.00");
            Transaction successTx = buildTransaction(TransactionStatus.SUCCESSFUL, "50.00");

            when(cardRepository.findByIdForUpdate(cardId)).thenReturn(Optional.of(exactCard));
            when(cardRepository.updateBalance(any(), any())).thenReturn(exactCard);
            when(transactionRepository.create(any(), any(), any(), any(), any(), any())).thenReturn(successTx);

            // Spending exactly the balance should succeed
            Transaction result = cardService.spend(cardId, new BigDecimal("50.00"), null, null);
            assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESSFUL);

            ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(cardRepository).updateBalance(any(), captor.capture());
            assertThat(captor.getValue()).isEqualByComparingTo("0.00");
        }
    }

    // ─── topUp ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("topUp")
    class TopUp {

        @Test
        @DisplayName("adds amount to balance")
        void addsToBalance() {
            Transaction successTx = buildTransaction(TransactionStatus.SUCCESSFUL, "200.00");
            when(cardRepository.findByIdForUpdate(cardId)).thenReturn(Optional.of(activeCard));
            when(cardRepository.updateBalance(eq(cardId), any())).thenReturn(activeCard);
            when(transactionRepository.create(any(), any(), any(), any(), any(), any())).thenReturn(successTx);

            Transaction result = cardService.topUp(cardId, new BigDecimal("200.00"), null, null);

            assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESSFUL);

            ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(cardRepository).updateBalance(eq(cardId), captor.capture());
            assertThat(captor.getValue()).isEqualByComparingTo("300.00"); // 100 + 200
            verify(metrics).incrementTopUpSuccess();
        }

        @Test
        @DisplayName("throws CardNotActiveException on closed card")
        void throwsWhenClosed() {
            Card closedCard = new Card(cardId, "Jane", new BigDecimal("100.00"),
                    CardStatus.CLOSED, null, LocalDateTime.now(), LocalDateTime.now());
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
            when(cardRepository.findById(cardId)).thenReturn(Optional.of(activeCard));
            when(transactionRepository.findByCardId(cardId)).thenReturn(List.of());

            List<Transaction> result = cardService.getTransactionHistory(cardId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("throws CardNotFoundException for unknown card")
        void throwsForUnknownCard() {
            when(cardRepository.findById(cardId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cardService.getTransactionHistory(cardId))
                    .isInstanceOf(CardNotFoundException.class);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Card cardWithBalance(String amount) {
        return new Card(cardId, "Jane Smith", new BigDecimal(amount),
                CardStatus.ACTIVE, LocalDateTime.now().plusYears(3),
                LocalDateTime.now(), LocalDateTime.now());
    }

    private Transaction buildTransaction(TransactionStatus status, String amount) {
        return new Transaction(UUID.randomUUID(), cardId, TransactionType.SPEND,
                new BigDecimal(amount), status, null, null, LocalDateTime.now());
    }
}
