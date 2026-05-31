package com.virtual.card.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Utility for recording operation latency via Micrometer {@link Timer}.
 *
 * <p>Usage examples:
 * <pre>
 *   // Time a void operation
 *   MetricsUtil.time(registry, "card.spend", () -> cardService.spend(...));
 *
 *   // Time and return a value
 *   Transaction tx = MetricsUtil.timeAndReturn(registry, "card.topup", () -> cardService.topUp(...));
 * </pre>
 *
 * <p>Timers are exposed at {@code /actuator/prometheus} with {@code _seconds_count},
 * {@code _seconds_sum}, and {@code _seconds_max} suffixes, and percentiles at
 * p50, p95, p99.
 */
public final class MetricsUtil {

    private static final Logger log = LoggerFactory.getLogger(MetricsUtil.class);

    private MetricsUtil() {}

    /**
     * Times a void operation and records it under the given metric name.
     * Exceptions are re-thrown; the timer still records the duration.
     */
    public static void time(MeterRegistry registry, String metricName, String... tags) {
        // No-op overload — use the Runnable variant below
    }

    public static void time(MeterRegistry registry, String metricName, Runnable operation, String... tags) {
        buildTimer(registry, metricName, tags).record(operation);
    }

    /**
     * Times an operation that returns a value.
     * Exceptions are re-thrown; the timer still records the duration.
     */
    public static <T> T timeAndReturn(MeterRegistry registry, String metricName, Supplier<T> operation, String... tags) {
        return buildTimer(registry, metricName, tags).record(operation);
    }

    /**
     * Times a checked operation (e.g. one that throws a checked exception).
     */
    public static <T> T timeChecked(MeterRegistry registry, String metricName, Callable<T> operation, String... tags) {
        try {
            return buildTimer(registry, metricName, tags).recordCallable(operation);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Records a simple gauge-style snapshot — useful for logging current balance
     * or queue depth at a point in time.
     */
    public static void recordGauge(MeterRegistry registry, String metricName, double value, String... tags) {
        if (tags.length % 2 != 0) {
            log.warn("MetricsUtil.recordGauge: tags must be key-value pairs, got odd count for metric '{}'", metricName);
            return;
        }
        io.micrometer.core.instrument.Gauge.builder(metricName, () -> value)
                .tags(tags)
                .register(registry);
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private static Timer buildTimer(MeterRegistry registry, String metricName, String[] tags) {
        if (tags.length % 2 != 0) {
            log.warn("MetricsUtil: tags must be key-value pairs, got odd count for metric '{}'", metricName);
        }
        return Timer.builder(metricName)
                .tags(tags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
}
