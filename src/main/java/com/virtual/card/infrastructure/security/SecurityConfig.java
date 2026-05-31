package com.virtual.card.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration — JWT Bearer token authentication.
 *
 * <p>This service acts as an OAuth2 Resource Server. Every request to
 * {@code /api/v1/**} must carry a valid JWT in the {@code Authorization: Bearer <token>}
 * header. The token is validated against the configured issuer's public key (JWKS endpoint).
 *
 * <h2>Token validation</h2>
 * <p>Configure the issuer URI in {@code application.yml}:
 * <pre>
 *   spring.security.oauth2.resourceserver.jwt.issuer-uri: https://your-idp.com/realms/virtual-card
 * </pre>
 * Spring Boot auto-fetches the JWKS from {@code {issuer-uri}/.well-known/openid-configuration}.
 *
 * <h2>Authorisation</h2>
 * <p>Card ownership is enforced at the service layer via {@link CardSecurityService}.
 * The JWT {@code sub} claim is extracted and compared to {@code card.ownerId}.
 *
 * <h2>Public endpoints</h2>
 * <ul>
 *   <li>Swagger UI + OpenAPI docs — for development exploration</li>
 *   <li>Actuator health — for load balancer health checks</li>
 *   <li>Actuator prometheus — for metrics scraping (lock down in production)</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Stateless API — no session, no CSRF needed
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())

            .authorizeHttpRequests(auth -> auth
                // Public — Swagger UI
                .requestMatchers(
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/api-docs",
                    "/api-docs/**",
                    "/v3/api-docs/**"
                ).permitAll()

                // Public — Actuator (restrict prometheus in production)
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/prometheus").permitAll()
                .requestMatchers("/actuator/**").authenticated()

                // All card API endpoints require a valid JWT
                .requestMatchers("/api/v1/**").authenticated()

                // Deny everything else
                .anyRequest().denyAll()
            )

            // JWT Bearer token validation
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtAuthenticationConverter()))
            );

        return http.build();
    }
}
