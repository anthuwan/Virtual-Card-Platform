package com.virtual.card.integration;

import com.virtual.card.domain.card.Card;
import com.virtual.card.domain.card.CardService;
import com.virtual.card.domain.transaction.Transaction;
import com.virtual.card.domain.transaction.TransactionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests verifying that balance integrity is maintained under
 * parallel spend and top-up operations.
 *
 * <p>These tests use a real PostgreSQL container to validate that our
 * SELECT FOR UPDATE locking strategy prevents race conditions that an
 * in-memory or mock setup would not reveal.
 *
 * <h2>Test Scenarios</h2>
 * <ul>
 *   <li>50 concurrent spends of £10 on a card with £300 balance — only
 *       30 should succeed, 20 should be declined. Final balance: £0.</li>
 *   <li>Mixed concurrent spends and top-ups — final balance must equal
 *       the sum of successful operations.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("Concurrency Tests")
class ConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("virtualcard_concurrency")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    CardService cardService;

    @Test
    @DisplayName("50 concurrent spends of £10 on a £300 card — exactly 30 succeed, balance ends at 0")
    void concurrentSpends_balanceNeverGoesNegative() throws InterruptedException {
        // Arrange
        Card card = cardService.createCard("Concurrent Test User", new BigDecimal("300.00"),
                LocalDateTime.now().plusYears(1));

        int threads = 50;
        BigDecimal spendAmount = new BigDecimal("10.00");
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger declinedCount = new AtomicInteger(0);

        // Act — all threads start simultaneously
        for (int i = 0; i < threads; i++) {
            int threadIdx = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // hold until all threads are ready
                    Transaction tx = cardService.spend(card.getId(), spendAmount,
                            "concurrent-spend-" + threadIdx, null);
                    if (tx.getStatus() == TransactionStatus.SUCCESSFUL) {
                        successCount.incrementAndGet();
                    } else {
                        declinedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Should not happen — declined transactions return DECLINED, not exceptions
                    System.err.println("Unexpected error in concurrent spend: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Release all threads at once
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert
        Card finalCard = cardService.getCard(card.getId());

        assertThat(successCount.get()).isEqualTo(30)
                .as("Exactly 30 out of 50 spends of £10 should succeed on a £300 balance");
        assertThat(declinedCount.get()).isEqualTo(20)
                .as("Exactly 20 should be declined due to insufficient funds");
        assertThat(finalCard.getBalance()).isEqualByComparingTo("0.00")
                .as("Balance must be exactly 0 after 30 successful spends of £10");
    }

    @Test
    @DisplayName("concurrent spends and top-ups maintain balance integrity")
    void mixedConcurrentOperations_balanceIntegrity() throws InterruptedException {
        // Start with £100
        Card card = cardService.createCard("Mixed Concurrency User", new BigDecimal("100.00"),
                LocalDateTime.now().plusYears(1));

        int spendThreads = 20;
        int topUpThreads = 10;
        int totalThreads = spendThreads + topUpThreads;
        BigDecimal spendAmount = new BigDecimal("5.00");
        BigDecimal topUpAmount = new BigDecimal("10.00");

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger spendSuccess = new AtomicInteger(0);
        AtomicInteger topUpSuccess = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();

        for (int i = 0; i < spendThreads; i++) {
            int idx = i;
            tasks.add(() -> {
                try {
                    startLatch.await();
                    Transaction tx = cardService.spend(card.getId(), spendAmount, "mix-spend-" + idx, null);
                    if (tx.getStatus() == TransactionStatus.SUCCESSFUL) spendSuccess.incrementAndGet();
                } finally { doneLatch.countDown(); }
                return null;
            });
        }

        for (int i = 0; i < topUpThreads; i++) {
            int idx = i;
            tasks.add(() -> {
                try {
                    startLatch.await();
                    Transaction tx = cardService.topUp(card.getId(), topUpAmount, "mix-topup-" + idx, null);
                    if (tx.getStatus() == TransactionStatus.SUCCESSFUL) topUpSuccess.incrementAndGet();
                } finally { doneLatch.countDown(); }
                return null;
            });
        }

        tasks.forEach(executor::submit);
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Final balance = 100 + (topUpSuccess * 10) - (spendSuccess * 5)
        Card finalCard = cardService.getCard(card.getId());
        BigDecimal expectedBalance = new BigDecimal("100.00")
                .add(new BigDecimal(topUpSuccess.get()).multiply(topUpAmount))
                .subtract(new BigDecimal(spendSuccess.get()).multiply(spendAmount));

        assertThat(finalCard.getBalance())
                .isEqualByComparingTo(expectedBalance)
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }
}
