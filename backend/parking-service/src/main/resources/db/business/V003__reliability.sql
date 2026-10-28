ALTER TABLE outbox_event ADD COLUMN lease_token CHAR(36) NULL;
CREATE TABLE business_sequence(name VARCHAR(30) PRIMARY KEY,last_id BIGINT NOT NULL);
INSERT INTO business_sequence SELECT 'business',GREATEST(COALESCE(MAX(id),0),1000000) FROM reservation_order;
