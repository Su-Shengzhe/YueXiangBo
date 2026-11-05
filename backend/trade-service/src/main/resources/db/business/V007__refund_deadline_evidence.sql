ALTER TABLE trade_order ADD COLUMN completed_at DATETIME(3) NULL;
ALTER TABLE refund_application ADD COLUMN evidence_text VARCHAR(2000) NOT NULL DEFAULT '';
ALTER TABLE refund_application ADD COLUMN facts_hash CHAR(64) NULL;
ALTER TABLE refund_application ADD COLUMN previous_application_id BIGINT NULL;
ALTER TABLE refund_application ADD CONSTRAINT uq_refund_facts UNIQUE(reservation_id,facts_hash);
ALTER TABLE refund_application ADD CONSTRAINT fk_refund_previous FOREIGN KEY(previous_application_id) REFERENCES refund_application(id);
