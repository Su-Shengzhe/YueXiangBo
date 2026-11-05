-- Bootstrap a single existing platform administrator; financial grants cannot edit this capability.
INSERT INTO account_permission(user_id,permission_code,granted)
SELECT MIN(u.id),'ACCOUNT_AUTHORIZE',TRUE FROM app_user u
JOIN user_role r ON r.user_id=u.id WHERE r.role_code='PLATFORM' AND u.enabled=TRUE
HAVING MIN(u.id) IS NOT NULL;
