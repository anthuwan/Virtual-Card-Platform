package com.virtual.card.infrastructure.security;

import com.virtual.card.exception.CardNotFoundException;
import com.virtual.card.domain.card.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Enforces card ownership — a cardholder may only access their own cards.
 *
 * <p>The authenticated user's ID is extracted from the JWT {@code sub} claim
 * via {@link SecurityContextHolder}. This is compared to {@code card.ownerId}.
 *
 * <h2>Usage in CardService</h2>
 * <pre>
 *   cardSecurityService.assertOwnership(cardId); // throws 403 if not owner
 * </pre>
 *
 * <h2>Admin bypass</h2>
 * <p>Users with the {@code ROLE_ADMIN} authority skip the ownership check —
 * operations staff can access any card. Extend {@link #assertOwnership} to
 * check for the admin role before the ownership comparison.
 */
@Service
public class CardSecurityService {

    private static final Logger log = LoggerFactory.getLogger(CardSecurityService.class);

    private final CardRepository cardRepository;

    public CardSecurityService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    /**
     * Asserts that the currently authenticated user owns the given card.
     *
     * @param cardId the card to check ownership for
     * @throws CardNotFoundException  if the card does not exist
     * @throws AccessDeniedException  if the caller does not own the card
     */
    public void assertOwnership(UUID cardId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String authenticatedUserId = getAuthenticatedUserId();

        var card = cardRepository.findById(cardId)
                .orElseThrow(() -> new CardNotFoundException(cardId));

        if (auth != null && auth.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            log.debug("Admin access granted: cardId={}, userId={}", cardId, authenticatedUserId);
            return;
        }

        // Cards without an ownerId (legacy/system cards) are accessible to all authenticated users
        if (card.getOwnerId() == null) {
            log.debug("Card has no ownerId — skipping ownership check: cardId={}", cardId);
            return;
        }

        if (!card.getOwnerId().equals(authenticatedUserId)) {
            log.warn("Access denied — user '{}' attempted to access card owned by '{}': cardId={}",
                    authenticatedUserId, card.getOwnerId(), cardId);
            throw new AccessDeniedException("You do not have access to this card");
        }

        log.debug("Ownership verified: cardId={}, userId={}", cardId, authenticatedUserId);
    }

    /**
     * Returns the authenticated user's ID (JWT {@code sub} claim).
     */
    public String getAuthenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("No authenticated user found");
        }
        return auth.getName(); // set to jwt.getSubject() by JwtAuthenticationConverter
    }
}
