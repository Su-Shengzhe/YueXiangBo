ALTER TABLE audit_log ADD COLUMN detail_json JSON NULL;
ALTER TABLE trade_order ADD COLUMN owner_id BIGINT NULL;
ALTER TABLE trade_order ADD COLUMN platform_basis_points INT NOT NULL DEFAULT 1000;
ALTER TABLE trade_order ADD COLUMN settlement_status VARCHAR(20) NOT NULL DEFAULT 'UNSETTLED';
CREATE TABLE settlement_batch(id BIGINT PRIMARY KEY,actor_id BIGINT NOT NULL,created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3));
CREATE TABLE settlement_item(id BIGINT PRIMARY KEY,batch_id BIGINT NOT NULL,reservation_id BIGINT NOT NULL UNIQUE,owner_id BIGINT NOT NULL,status VARCHAR(20) NOT NULL,active_attempt_id BIGINT NULL,requested_fen BIGINT NOT NULL,platform_amount_fen BIGINT NOT NULL,basis_bill_version BIGINT NOT NULL);
CREATE TABLE settlement_attempt(id BIGINT PRIMARY KEY,item_id BIGINT NOT NULL,reservation_id BIGINT NOT NULL,requested_fen BIGINT NOT NULL,platform_amount_fen BIGINT NOT NULL,basis_bill_version BIGINT NOT NULL,payout_no VARCHAR(80) NOT NULL UNIQUE,status VARCHAR(20) NOT NULL,dispatch_count INT NOT NULL DEFAULT 0,query_count INT NOT NULL DEFAULT 0,lease_token VARCHAR(36),lease_until DATETIME(3),next_retry_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),last_error VARCHAR(80),created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),INDEX ix_settlement_work(status,next_retry_at));
CREATE TABLE settlement_adjustment(refund_attempt_id BIGINT PRIMARY KEY,item_id BIGINT NOT NULL,owner_id BIGINT NOT NULL,owner_recovery_fen BIGINT NOT NULL,platform_reversal_fen BIGINT NOT NULL,status VARCHAR(24) NOT NULL DEFAULT 'PENDING_RECOVERY',created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3));
CREATE TABLE settlement_hold(reservation_id BIGINT NOT NULL,reason VARCHAR(40) NOT NULL,evidence VARCHAR(500) NOT NULL,PRIMARY KEY(reservation_id,reason));
