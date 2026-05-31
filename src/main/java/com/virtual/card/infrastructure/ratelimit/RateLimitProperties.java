package com.virtual.card.infrastructure.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externally configurable rate-limit parameters.
 * Values are read from {@code app.rate-limit.*} in application.yml.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        long capacity,
        long refillTokens,
        long refillSeconds
) {}
