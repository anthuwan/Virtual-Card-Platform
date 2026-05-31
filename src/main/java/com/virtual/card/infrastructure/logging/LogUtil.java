package com.virtual.card.infrastructure.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Structured logging utility wrapping SLF4J.
 *
 * <p>Provides:
 * <ul>
 *   <li>MDC (Mapped Diagnostic Context) helpers for attaching correlation IDs
 *       and card IDs to every log line within a scope</li>
 *   <li>Convenience methods for consistent log formats across the application</li>
 *   <li>Timed logging — logs entry/exit with elapsed millis</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   // Attach cardId to all logs within a block
 *   try (var ctx = LogUtil.withContext("cardId", cardId.toString())) {
 *       log.info("Processing spend");   // log will include cardId in MDC
 *   }
 *
 *   // Log and time an operation
 *   Transaction tx = LogUtil.timed(log, "spend", () -> cardService.spend(...));
 * </pre>
 */
public final class LogUtil {

    // MDC key constants — use these everywhere for consistency
    public static final String MDC_CARD_ID        = "cardId";
    public static final String MDC_TX_ID          = "txId";
    public static final String MDC_IDEMPOTENCY_KEY = "idempotencyKey";
    public static final String MDC_REQUEST_ID     = "requestId";

    private LogUtil() {}

    // ─── MDC Context ─────────────────────────────────────────────────────────

    /**
     * Sets a single MDC key for the duration of a try-with-resources block.
     *
     * <pre>
     *   try (var ctx = LogUtil.withContext("cardId", cardId.toString())) {
     *       // all log lines here include cardId
     *   }
     * </pre>
     */
    public static AutoCloseable withContext(String key, String value) {
        MDC.put(key, value);
        return () -> MDC.remove(key);
    }

    /**
     * Sets multiple MDC keys for the duration of a try-with-resources block.
     */
    public static AutoCloseable withContext(Map<String, String> context) {
        context.forEach(MDC::put);
        return () -> context.keySet().forEach(MDC::remove);
    }

    /**
     * Attaches a random correlation/request ID to MDC — useful at request entry points.
     */
    public static AutoCloseable withRequestId() {
        String requestId = UUID.randomUUID().toString();
        MDC.put(MDC_REQUEST_ID, requestId);
        return () -> MDC.remove(MDC_REQUEST_ID);
    }

    // ─── Timed Logging ───────────────────────────────────────────────────────

    /**
     * Executes an operation, logging start/end and elapsed time.
     *
     * <pre>
     *   Transaction tx = LogUtil.timed(log, "spend", () -> cardService.spend(...));
     * </pre>
     */
    public static <T> T timed(Logger log, String operation, Supplier<T> supplier) {
        log.debug("Starting operation: {}", operation);
        long start = System.currentTimeMillis();
        try {
            T result = supplier.get();
            log.debug("Completed operation: {} in {}ms", operation, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("Operation failed: {} after {}ms — {}", operation, System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    /**
     * Void variant of {@link #timed}.
     */
    public static void timed(Logger log, String operation, Runnable runnable) {
        timed(log, operation, () -> { runnable.run(); return null; });
    }

    // ─── Structured Event Logging ────────────────────────────────────────────

    /**
     * Logs a structured financial event — use for spend/topup audit trails.
     *
     * <pre>
     *   LogUtil.logEvent(log, "SPEND", "cardId", cardId, "amount", amount, "status", status);
     * </pre>
     */
    public static void logEvent(Logger log, String event, Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            log.warn("LogUtil.logEvent called with odd number of key-value pairs for event '{}'", event);
            return;
        }
        StringBuilder sb = new StringBuilder("EVENT=").append(event);
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            sb.append(" | ").append(keyValuePairs[i]).append("=").append(keyValuePairs[i + 1]);
        }
        log.info(sb.toString());
    }

    /**
     * Returns a logger for the given class — thin wrapper so callers
     * don't need to import LoggerFactory directly.
     */
    public static Logger getLogger(Class<?> clazz) {
        return LoggerFactory.getLogger(clazz);
    }
}
