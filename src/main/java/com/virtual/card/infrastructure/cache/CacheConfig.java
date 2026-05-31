package com.virtual.card.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Cache configuration using Caffeine (in-process).
 *
 * <p>Cache names:
 * <ul>
 *   <li>{@code cards} — caches {@link com.virtual.card.domain.card.Card} by ID.
 *       TTL: 5 seconds, max 1000 entries.</li>
 * </ul>
 *
 * <h2>Switching to Redis</h2>
 * <p>To use Redis in a multi-instance deployment, replace this bean with:
 * <pre>
 *   spring:
 *     cache:
 *       type: redis
 *     data:
 *       redis:
 *         host: localhost
 *         port: 6379
 * </pre>
 * No application code changes needed — {@code @Cacheable}/{@code @CacheEvict}
 * annotations on {@link CardCacheService} work identically with Redis.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CARDS_CACHE = "cards";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(CARDS_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.SECONDS)  // short TTL — balance changes frequently
                .maximumSize(1000)                       // cap memory usage
                .recordStats());                         // exposes hit/miss stats via Micrometer
        return manager;
    }
}
