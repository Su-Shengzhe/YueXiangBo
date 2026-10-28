CREATE TABLE parking_request_guard (
 actor_id BIGINT NOT NULL,
 operation VARCHAR(40) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 PRIMARY KEY(actor_id,operation,request_key)
);
CREATE TABLE parking_event (
 event_id VARCHAR(80) PRIMARY KEY,
 actor_id BIGINT NOT NULL,
 reservation_id BIGINT NOT NULL,
 event_type VARCHAR(20) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 result_json TEXT NOT NULL,
 created_at DATETIME(3) NOT NULL,
 INDEX ix_parking_event_order(reservation_id,created_at)
);
