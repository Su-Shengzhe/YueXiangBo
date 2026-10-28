ALTER TABLE reservation_order
 ADD expiry_retry_at DATETIME(3) NULL,
 ADD expiry_attempts INT NOT NULL DEFAULT 0,
 ADD expiry_last_error VARCHAR(100) NULL,
 ADD INDEX ix_expiry_payment(status,pay_deadline,expiry_retry_at),
 ADD INDEX ix_expiry_arrival(status,start_at,expiry_retry_at);
