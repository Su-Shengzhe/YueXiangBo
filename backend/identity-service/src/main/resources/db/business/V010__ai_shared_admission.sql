CREATE TABLE ai_provider_admission (
 account_key VARCHAR(64) PRIMARY KEY,
 cooldown_until DATETIME(3),
 rpm_limit INT NOT NULL DEFAULT 6,
 tpm_limit BIGINT NOT NULL DEFAULT 120000,
 concurrent_limit INT NOT NULL DEFAULT 1
);
ALTER TABLE ai_call_reservation ADD COLUMN account_key VARCHAR(64);
ALTER TABLE ai_call_reservation ADD COLUMN lease_until DATETIME(3);
ALTER TABLE ai_call_reservation ADD COLUMN token_reservation BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_ai_admission_calls ON ai_call_reservation(account_key,created_at);
