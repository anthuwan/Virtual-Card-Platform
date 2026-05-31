package com.virtual.card.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for card lifecycle defaults.
 */
@ConfigurationProperties(prefix = "app.card")
public record CardExpirationProperties(
        int defaultExpiryMonths,
        String expiryCheckCron
) {}
