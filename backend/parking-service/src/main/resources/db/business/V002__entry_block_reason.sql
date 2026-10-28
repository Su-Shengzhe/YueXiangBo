ALTER TABLE reservation_order ADD COLUMN entry_blocked_at DATETIME(3) NULL, ADD COLUMN close_reason VARCHAR(40) NULL;
