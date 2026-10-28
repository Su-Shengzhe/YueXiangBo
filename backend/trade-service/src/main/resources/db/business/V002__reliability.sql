ALTER TABLE outbox_event ADD COLUMN lease_token CHAR(36) NULL;
ALTER TABLE trade_order ADD COLUMN bill_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment_attempt
 ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
 ADD COLUMN next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 ADD COLUMN lease_until DATETIME(3) NULL,
 ADD COLUMN lease_token CHAR(36) NULL,
 ADD COLUMN last_error VARCHAR(80) NULL,
 ADD INDEX ix_retry(status,next_retry_at);
CREATE TABLE business_sequence(name VARCHAR(30) PRIMARY KEY,last_id BIGINT NOT NULL);
INSERT INTO business_sequence SELECT 'business',GREATEST(COALESCE(MAX(id),0),1000000) FROM payment_attempt;
