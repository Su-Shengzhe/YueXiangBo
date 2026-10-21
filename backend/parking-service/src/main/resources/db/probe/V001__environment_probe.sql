-- Environment verification only; production business tables are not created here.
CREATE TABLE env_probe (
  id VARCHAR(36) PRIMARY KEY,
  label VARCHAR(80) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE env_probe_message (
  id VARCHAR(36) PRIMARY KEY,
  received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
