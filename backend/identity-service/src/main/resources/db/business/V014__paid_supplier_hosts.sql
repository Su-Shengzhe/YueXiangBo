ALTER TABLE ai_paid_budget ADD COLUMN supplier_host VARCHAR(253);
ALTER TABLE ai_paid_budget ADD COLUMN min_input_price BIGINT NOT NULL DEFAULT 1;
ALTER TABLE ai_paid_budget ADD COLUMN min_output_price BIGINT NOT NULL DEFAULT 1;
ALTER TABLE ai_paid_budget ADD COLUMN disable_thinking BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE ai_paid_budget SET supplier_host='api.deepseek.com',min_input_price=2000000,min_output_price=8000000,disable_thinking=TRUE WHERE scope='DEEPSEEK_ACCEPTANCE';
CREATE UNIQUE INDEX ix_paid_host ON ai_paid_budget(supplier_host);
ALTER TABLE ai_provider_revision ADD COLUMN disable_thinking BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE ai_provider_revision SET disable_thinking=TRUE WHERE paid_scope='DEEPSEEK_ACCEPTANCE';
