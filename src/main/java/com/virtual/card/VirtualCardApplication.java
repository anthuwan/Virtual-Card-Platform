package com.virtual.card;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Virtual Card Issuance Platform.
 *
 * <p>Entry point for the Spring Boot application. Key design choices:
 * <ul>
 *   <li>Spring Data JPA — repository abstraction with pessimistic locking
 *       ({@code SELECT ... FOR UPDATE}) for concurrency safety.</li>
 *   <li>Optimistic locking ({@code @Version}) + {@code @Retryable} — defence-in-depth
 *       against concurrent updates; retries transparently on conflict.</li>
 *   <li>Transactional Outbox — events written to DB in same transaction as business data,
 *       guaranteeing at-least-once Kafka delivery.</li>
 *   <li>Resilience4j Circuit Breaker — prevents cascading failure when fraud service is slow.</li>
 *   <li>Flyway for schema evolution — migrations version-controlled alongside code.</li>
 *   <li>Async Spring Events — non-critical audit trails off the hot path.</li>
 *   <li>Bucket4j in-process rate limiting — protects endpoints from abuse.</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableRetry
@ConfigurationPropertiesScan
public class VirtualCardApplication {

    public static void main(String[] args) {
        SpringApplication.run(VirtualCardApplication.class, args);
    }
}
