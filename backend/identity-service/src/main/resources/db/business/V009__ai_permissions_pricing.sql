ALTER TABLE ai_provider_revision ADD COLUMN input_price_microyuan_per_million BIGINT NOT NULL DEFAULT 400000;
ALTER TABLE ai_provider_revision ADD COLUMN output_price_microyuan_per_million BIGINT NOT NULL DEFAULT 1200000;
ALTER TABLE ai_provider_revision ADD COLUMN price_version VARCHAR(80) NOT NULL DEFAULT 'agnes-list-usd-fx8-v1';
ALTER TABLE ai_provider_revision ADD COLUMN free_access_approved BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE ai_provider_revision SET free_access_approved=TRUE WHERE base_url='https://apihub.agnes-ai.com/v1' AND model_name='agnes-2.5-flash';
INSERT INTO account_permission(user_id,permission_code,granted) SELECT user_id,'MODEL_MANAGE',TRUE FROM account_permission WHERE permission_code='ACCOUNT_AUTHORIZE' AND granted=TRUE;
INSERT INTO account_permission(user_id,permission_code,granted) SELECT user_id,'KNOWLEDGE_MANAGE',TRUE FROM account_permission WHERE permission_code='ACCOUNT_AUTHORIZE' AND granted=TRUE;
INSERT INTO account_permission(user_id,permission_code,granted) SELECT user_id,'AI_LOG_READ',TRUE FROM account_permission WHERE permission_code='ACCOUNT_AUTHORIZE' AND granted=TRUE;
