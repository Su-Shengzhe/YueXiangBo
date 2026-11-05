CREATE TABLE channel_call (
 id CHAR(36) PRIMARY KEY,
 channel_no VARCHAR(80) NOT NULL,
 operation VARCHAR(12) NOT NULL,
 state VARCHAR(24) NOT NULL,
 outcome VARCHAR(24),
 error_code VARCHAR(80),
 started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 finished_at DATETIME(3),
 INDEX ix_channel_call(channel_no,started_at),
 INDEX ix_channel_call_state(state,started_at)
);
