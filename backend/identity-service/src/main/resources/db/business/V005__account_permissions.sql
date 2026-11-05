CREATE TABLE account_permission (
 user_id BIGINT NOT NULL,
 permission_code VARCHAR(40) NOT NULL,
 granted BOOLEAN NOT NULL,
 PRIMARY KEY(user_id,permission_code),
 FOREIGN KEY(user_id) REFERENCES app_user(id)
);
