package com.virtual.card;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Virtual Card Issuance Platform.
 *
 * <p>Entry point for the Spring Boot application. Key design choices:
 * <ul>
 *   <li>JOOQ as the primary data access layer — type-safe SQL, explicit control over queries,
 *       pessimistic locking via SELECT FOR UPDATE for concurrency safety.</li>
 *   <li>Flyway for schema evolution — migrations are version-controlled alongside the code.</li>
 *   <li>Async Spring Events for non-critical audit trails — decouples the hot path from
 *       auditing concerns without introducing a message broker dependency.</li>
 *   <li>Bucket4j for in-process rate limiting — protects endpoints from abuse with
 *       negligible latency overhead.</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan
public class VirtualCardApplication {

    public static void main(String[] args) {
        SpringApplication.run(VirtualCardApplication.class, args);
    }
}
