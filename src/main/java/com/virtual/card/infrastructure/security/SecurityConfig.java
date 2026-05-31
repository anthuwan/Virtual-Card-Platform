package com.virtual.card.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security configuration — JWT, CORS, and security headers.
 *
 * <h2>Authentication</h2>
 * <p>OAuth2 Resource Server — every request to {@code /api/v1/**} requires a valid
 * JWT Bearer token validated against the configured issuer's JWKS endpoint.
 *
 * <h2>CORS</h2>
 * <p>Allowed origins are externalised to {@code app.security.allowed-origins} in
 * {@code application.yml}. Defaults to {@code http://localhost:3000} for local dev.
 * In production set via environment variable: {@code APP_SECURITY_ALLOWED_ORIGINS}.
 *
 * <h2>Security Headers (OWASP)</h2>
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — prevents MIME sniffing</li>
 *   <li>{@code X-Frame-Options: DENY} — prevents clickjacking</li>
 *   <li>{@code Strict-Transport-Security} — enforces HTTPS</li>
 *   <li>{@code Cache-Control: no-store} — prevents caching sensitive responses</li>
 *   <li>{@code Content-Security-Policy} — restricts resource loading</li>
 *   <li>{@code Referrer-Policy: no-referrer} — prevents referrer leakage</li>
 *   <li>{@code Permissions-Policy} — disables unused browser features</li>
 * </ul>
 *
 * <h2>CSRF</h2>
 * <p>Disabled — stateless JWT API does not use session cookies, so CSRF does not apply.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${app.security.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // ── Stateless — no session, no CSRF ──────────────────────────────
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())

            // ── CORS ──────────────────────────────────────────────────────────
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // ── OWASP Security Headers ────────────────────────────────────────
            .headers(headers -> headers
                // Prevent MIME-type sniffing
                .contentTypeOptions(contentType -> {})
                // Prevent clickjacking
                .frameOptions(frame -> frame.deny())
                // Enforce HTTPS for 1 year, include subdomains
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(31536000)
                    .includeSubDomains(true))
                // No caching of sensitive responses
                .cacheControl(cache -> {})
                // Restrict resource loading — API only serves JSON, no scripts/images
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                // Prevent referrer leakage
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                // Disable unused browser features
                .permissionsPolicy(permissions -> permissions
                    .policy("camera=(), microphone=(), geolocation=(), payment=()"))
            )

            // ── Authorisation ─────────────────────────────────────────────────
            .authorizeHttpRequests(auth -> auth
                // Public — Swagger UI
                .requestMatchers(
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/api-docs",
                    "/api-docs/**",
                    "/v3/api-docs/**"
                ).permitAll()
                // Public — Actuator health (load balancer checks)
                .requestMatchers("/actuator/health").permitAll()
                // Metrics scraping — lock down in production
                .requestMatchers("/actuator/prometheus").permitAll()
                .requestMatchers("/actuator/**").authenticated()
                // All card API endpoints require a valid JWT
                .requestMatchers("/api/v1/**").authenticated()
                // Deny everything else
                .anyRequest().denyAll()
            )

            // ── JWT Bearer token validation ───────────────────────────────────
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtAuthenticationConverter()))
            );

        return http.build();
    }

    /**
     * CORS configuration.
     *
     * <p>Allowed origins are read from {@code app.security.allowed-origins}.
     * Only GET, POST, PATCH are allowed — no DELETE (cards are closed, not deleted).
     * {@code Authorization} and {@code Idempotency-Key} headers are explicitly exposed.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Allowed origins — set per environment
        config.setAllowedOrigins(allowedOrigins);

        // Only methods used by this API
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));

        // Headers the client is allowed to send
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Idempotency-Key"
        ));

        // Headers the browser is allowed to read from the response
        config.setExposedHeaders(List.of(
                "Location",       // returned on 201 Created
                "X-Request-Id"
        ));

        // Allow credentials (needed if using cookie-based token refresh)
        config.setAllowCredentials(true);

        // Cache preflight response for 1 hour
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", config);
        return source;
    }
}
