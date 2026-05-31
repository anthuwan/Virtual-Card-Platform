package com.virtual.card.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Centralised Micrometer metrics for the virtual card platform.
 *
 * <p>Metrics exposed here are available at {@code /actuator/prometheus} and
 * {@code /actuator/metrics}. They feed observability dashboards (e.g. Grafana)
 * and alert rules.
 *
 * <p>Key counters and summaries:
 * <ul>
 *   <li>{@code card.created.total} — total cards issued</li>
 *   <li>{@code card.expired.total} — cards expired by the scheduler</li>
 *   <li>{@code transaction.spend.success.total} — successful spend operations</li>
 *   <li>{@code transaction.spend.declined.total} — declined spend operations</li>
 *   <li>{@code transaction.topup.success.total} — successful top-up operations</li>
 *   <li>{@code transaction.amount} — distribution of transaction amounts</li>
 * </ul>
 */
@Component
public class CardMetrics {

    private final Counter cardCreatedCounter;
    private final Counter cardExpiredCounter;
    private final Counter spendSuccessCounter;
    private final Counter spendDeclinedCounter;
    private final Counter topUpSuccessCounter;
    private final DistributionSummary transactionAmountSummary;

    public CardMetrics(MeterRegistry registry) {
        this.cardCreatedCounter = Counter.builder("card.created")
                .description("Total number of virtual cards created")
                .register(registry);

        this.cardExpiredCounter = Counter.builder("card.expired")
                .description("Total number of cards expired by the scheduler")
                .register(registry);

        this.spendSuccessCounter = Counter.builder("transaction.spend")
                .tag("outcome", "success")
                .description("Total successful spend transactions")
                .register(registry);

        this.spendDeclinedCounter = Counter.builder("transaction.spend")
                .tag("outcome", "declined")
                .description("Total declined spend transactions")
                .register(registry);

        this.topUpSuccessCounter = Counter.builder("transaction.topup")
                .tag("outcome", "success")
                .description("Total successful top-up transactions")
                .register(registry);

        this.transactionAmountSummary = DistributionSummary.builder("transaction.amount")
                .description("Distribution of transaction amounts in the system currency")
                .baseUnit("currency_units")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void incrementCardCreated()    { cardCreatedCounter.increment(); }
    public void incrementCardExpired()    { cardExpiredCounter.increment(); }
    public void incrementSpendSuccess()   { spendSuccessCounter.increment(); }
    public void incrementSpendDeclined()  { spendDeclinedCounter.increment(); }
    public void incrementTopUpSuccess()   { topUpSuccessCounter.increment(); }

    public void recordTransactionAmount(BigDecimal amount) {
        transactionAmountSummary.record(amount.doubleValue());
    }
}
