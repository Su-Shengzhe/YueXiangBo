ALTER TABLE payment_attempt ADD COLUMN retry_of_id BIGINT NULL;
ALTER TABLE payment_attempt ADD CONSTRAINT uq_payment_retry_parent UNIQUE (retry_of_id);
ALTER TABLE payment_attempt ADD CONSTRAINT fk_payment_retry_parent FOREIGN KEY (retry_of_id) REFERENCES payment_attempt(id);
ALTER TABLE audit_log ADD COLUMN reason VARCHAR(500) NULL;
