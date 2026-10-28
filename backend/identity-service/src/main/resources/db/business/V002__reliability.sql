ALTER TABLE outbox_event ADD COLUMN lease_token CHAR(36) NULL;
