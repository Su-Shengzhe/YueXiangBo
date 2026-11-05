CREATE TABLE refund_application (
 id BIGINT PRIMARY KEY,
 reservation_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 amount_fen BIGINT NOT NULL,
 reason VARCHAR(500) NOT NULL,
 status VARCHAR(24) NOT NULL,
 decision_by BIGINT,
 decision_note VARCHAR(500),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(reservation_id) REFERENCES trade_order(reservation_id),
 CHECK(amount_fen>0),
 INDEX ix_refund_application(reservation_id,status)
);
ALTER TABLE payment_attempt ADD COLUMN application_id BIGINT NULL;
ALTER TABLE payment_attempt ADD COLUMN receipt_attempt_id BIGINT NULL;
ALTER TABLE payment_attempt ADD CONSTRAINT fk_refund_application FOREIGN KEY(application_id) REFERENCES refund_application(id);
ALTER TABLE payment_attempt ADD CONSTRAINT fk_refund_receipt FOREIGN KEY(receipt_attempt_id) REFERENCES payment_attempt(id);
