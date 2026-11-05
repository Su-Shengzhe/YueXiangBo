CREATE TABLE refund_receipt_allocation (
 attempt_id BIGINT NOT NULL,
 receipt_id BIGINT NOT NULL,
 amount_fen BIGINT NOT NULL,
 PRIMARY KEY(attempt_id,receipt_id),
 FOREIGN KEY(attempt_id) REFERENCES payment_attempt(id),
 FOREIGN KEY(receipt_id) REFERENCES payment_attempt(id),
 CHECK(amount_fen>0)
);
INSERT INTO refund_receipt_allocation SELECT id,receipt_attempt_id,amount_fen FROM payment_attempt WHERE kind='REFUND' AND receipt_attempt_id IS NOT NULL;
