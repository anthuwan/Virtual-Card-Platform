package com.virtual.card.infrastructure.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Converts a validated JWT into a Spring Security {@link JwtAuthenticationToken}.
 *
 * <p>Extracts:
 * <ul>
 *   <li>{@code sub} claim — used as the principal name (owner ID for card access checks)</li>
 *   <li>{@code scope} / {@code roles} claims — converted to {@code GrantedAuthority} instances</li>
 * </ul>
 *
 * <p>To retrieve the authenticated user's ID anywhere in the application:
 * <pre>
 *   String userId = SecurityContextHolder.getContext()
 *       .getAuthentication().getName(); // returns JWT 'sub' claim
 * </pre>
 */
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter authoritiesConverter =
            new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var authorities = authoritiesConverter.convert(jwt);
        // getName() returns jwt.getSubject() — the user's unique ID from the identity provider
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
