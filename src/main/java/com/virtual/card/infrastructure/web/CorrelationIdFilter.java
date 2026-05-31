package com.virtual.card.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that stamps every request with a correlation / request ID.
 *
 * <h2>What it does</h2>
 * <ol>
 *   <li>Reads the {@code X-Request-Id} header from the incoming request.
 *       If absent, generates a new random UUID.</li>
 *   <li>Puts the value into SLF4J MDC as {@code requestId} — all log lines
 *       emitted during the request automatically include it.</li>
 *   <li>Echoes the value back in the {@code X-Request-Id} response header
 *       so clients can correlate their own logs.</li>
 *   <li>Always clears the MDC key after the response is committed, preventing
 *       thread-pool contamination in pooled servlet containers.</li>
 * </ol>
 *
 * <p>Order is set to {@code -100} so this runs before security and rate-limit
 * filters — all downstream log lines carry the correlation ID.
 */
@Component
@Order(-100)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
