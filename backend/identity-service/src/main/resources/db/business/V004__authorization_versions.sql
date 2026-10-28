ALTER TABLE app_user ADD COLUMN version INT NOT NULL DEFAULT 1;
CREATE TABLE community_authorization (
    community_id BIGINT PRIMARY KEY,
    version INT NOT NULL DEFAULT 0
);
INSERT INTO community_authorization(community_id,version)
SELECT DISTINCT community_id,1 FROM community_admin;
