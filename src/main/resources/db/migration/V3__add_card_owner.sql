-- V3: Add owner_id to cards for JWT-based authorisation
-- owner_id maps to the authenticated user's subject claim (JWT 'sub')
-- Existing cards get a placeholder owner — in production this would be a real user ID

ALTER TABLE cards ADD COLUMN IF NOT EXISTS owner_id VARCHAR(255);

-- Index for ownership lookups — "fetch all cards for this user"
CREATE INDEX IF NOT EXISTS idx_cards_owner_id ON cards(owner_id);
