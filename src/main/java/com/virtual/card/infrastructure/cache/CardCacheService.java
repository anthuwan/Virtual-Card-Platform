package com.virtual.card.infrastructure.cache;

import com.virtual.card.domain.card.Card;
import com.virtual.card.domain.card.CardRepository;
import com.virtual.card.exception.CardNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Cache layer for {@link Card} reads.
 *
 * <p>Sits between {@link com.virtual.card.domain.card.CardService} and
 * {@link CardRepository} for read operations. Write operations (spend, topUp,
 * block, close) evict the cache entry so the next read fetches fresh data from DB.
 *
 * <p>Cache: {@code cards} (Caffeine, TTL 5s, max 1000 entries).
 * See {@link CacheConfig} for configuration details and Redis migration notes.
 *
 * <h2>Cache strategy</h2>
 * <pre>
 *   getCard(id)   → @Cacheable  — return cached value or load from DB and cache
 *   refreshCard() → @CachePut   — update cache after a write (no extra DB read)
 *   evictCard(id) → @CacheEvict — remove stale entry after mutation
 * </pre>
 */
@Service
public class CardCacheService {

    private static final Logger log = LoggerFactory.getLogger(CardCacheService.class);

    private final CardRepository cardRepository;

    public CardCacheService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Returns the card from cache if present; otherwise loads from DB, caches, and returns.
     * Cache key: cardId.
     */
    @Cacheable(value = CacheConfig.CARDS_CACHE, key = "#cardId")
    public Card getCard(UUID cardId) {
        log.debug("Cache miss — loading card from DB: id={}", cardId);
        return cardRepository.findById(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));
    }

    /**
     * Puts the given card into the cache. Call this after a write operation
     * to keep the cache warm without a redundant DB round-trip.
     * Cache key: card.id.
     */
    @CachePut(value = CacheConfig.CARDS_CACHE, key = "#card.id")
    public Card refreshCard(Card card) {
        log.debug("Cache updated: id={}, status={}, balance={}", card.getId(), card.getStatus(), card.getBalance());
        return card;
    }

    /**
     * Evicts the card from the cache. Call this when the card state changes
     * and you don't have the updated entity to put back (e.g. after a delete).
     */
    @CacheEvict(value = CacheConfig.CARDS_CACHE, key = "#cardId")
    public void evictCard(UUID cardId) {
        log.debug("Cache evicted: id={}", cardId);
    }

    /**
     * Evicts all entries from the cards cache.
     * Use sparingly — e.g. after a bulk expiration job.
     */
    @CacheEvict(value = CacheConfig.CARDS_CACHE, allEntries = true)
    public void evictAll() {
        log.info("Cards cache cleared (all entries evicted)");
    }
}
