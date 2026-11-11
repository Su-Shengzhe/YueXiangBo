ALTER TABLE reservation_order ADD COLUMN rule_snapshot TEXT;
-- Existing orders remain NULL: do not invent a historical rule snapshot.
