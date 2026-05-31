package com.virtual.card.infrastructure.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servlet filter implementing per-IP rate limiting using Bucket4j token-bucket algorithm.
 *
 * <p>Each client IP address gets its own token bucket. When a bucket is exhausted,
 * the request is rejected with {@code 429 Too Many Requests} and a
 * {@code Retry-After} header indicating when tokens will refill.
 *
 * <h2>Trade-offs</h2>
 * <ul>
 *   <li><b>In-process storage</b> — buckets are stored in a {@link ConcurrentHashMap}.
 *       This is simple and fast but does not share state across multiple instances.
 *       For a distributed deployment, replace the map with a Redis-backed bucket store
 *       (Bucket4j has first-class Redis/Hazelcast integration).</li>
 *   <li><b>IP-based keying</b> — works for most cases but can be circumvented via
 *       proxies or IPv6 rotation. In production, key on authenticated user ID or
 *       API key instead.</li>
 * </ul>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final RateLimitProperties properties;

    public RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String clientIp = resolveClientIp(request);
        Bucket bucket = buckets.computeIfAbsent(clientIp, this::newBucket);

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded for IP: {}", clientIp);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(properties.refillSeconds()));
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"error":"TOO_MANY_REQUESTS","message":"Rate limit exceeded. Please retry after %d seconds."}
                    """.formatted(properties.refillSeconds()));
        }
    }

    private Bucket newBucket(String clientIp) {
        Refill refill = Refill.intervally(properties.refillTokens(),
                Duration.ofSeconds(properties.refillSeconds()));
        Bandwidth limit = Bandwidth.classic(properties.capacity(), refill);
        return Bucket.builder().addLimit(limit).build();
    }

    private String resolveClientIp(HttpServletRequest request) {
        // Honour standard proxy headers for accurate IP resolution behind load balancers
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
