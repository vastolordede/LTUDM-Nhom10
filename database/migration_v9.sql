-- R76 PR 2: store each authenticated Peer identity's Ed25519 public key.
-- Existing peers remain valid database rows and register a key on their next login.
ALTER TABLE peers ADD COLUMN IF NOT EXISTS public_key TEXT NULL;
