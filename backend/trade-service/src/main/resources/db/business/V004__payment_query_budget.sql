ALTER TABLE payment_attempt ADD COLUMN query_count INT NOT NULL DEFAULT 0;
UPDATE payment_attempt SET query_count=LEAST(5,GREATEST(0,retry_count-1));
