ALTER TABLE ai_provider_revision ADD COLUMN checked_at DATETIME(3);
ALTER TABLE ai_provider_revision ADD COLUMN checked_fingerprint VARCHAR(64);
