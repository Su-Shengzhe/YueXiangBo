CREATE TABLE ai_paid_budget (
 scope VARCHAR(40) PRIMARY KEY,
 limit_microyuan BIGINT NOT NULL,
 approved BOOLEAN NOT NULL DEFAULT FALSE
);
-- Explicit user authorization dated 2026-11-10; shared across all DeepSeek revisions/keys.
INSERT INTO ai_paid_budget VALUES('DEEPSEEK_ACCEPTANCE',5000000,TRUE);
ALTER TABLE ai_provider_revision ADD COLUMN paid_scope VARCHAR(40);
ALTER TABLE ai_call_reservation ADD COLUMN paid_scope VARCHAR(40);
CREATE INDEX ix_ai_paid_scope ON ai_call_reservation(paid_scope);
