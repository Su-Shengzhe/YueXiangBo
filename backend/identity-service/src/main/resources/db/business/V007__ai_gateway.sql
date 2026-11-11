CREATE TABLE ai_provider_revision (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 provider_name VARCHAR(80) NOT NULL,
 protocol VARCHAR(40) NOT NULL,
 base_url VARCHAR(500) NOT NULL,
 model_name VARCHAR(128) NOT NULL,
 credential_ref VARCHAR(80) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_by BIGINT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE ai_route_state (
 id INT PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 provider_revision_id BIGINT,
 previous_revision_id BIGINT
);
INSERT INTO ai_route_state(id,version) VALUES(1,0);
CREATE TABLE ai_route_history (
 version BIGINT PRIMARY KEY,
 provider_revision_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE ai_budget (
 id INT PRIMARY KEY,
 limit_microyuan BIGINT NOT NULL,
 warning_microyuan BIGINT NOT NULL,
 spent_microyuan BIGINT NOT NULL DEFAULT 0,
 reserved_microyuan BIGINT NOT NULL DEFAULT 0
);
INSERT INTO ai_budget(id,limit_microyuan,warning_microyuan) VALUES(1,50000000,40000000);
CREATE TABLE ai_call_reservation (
 request_id VARCHAR(64) PRIMARY KEY,
 route_version BIGINT NOT NULL,
 reserved_microyuan BIGINT NOT NULL,
 charged_microyuan BIGINT,
 status VARCHAR(20) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
