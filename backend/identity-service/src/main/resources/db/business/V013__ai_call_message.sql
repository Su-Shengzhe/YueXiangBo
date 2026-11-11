ALTER TABLE ai_call_reservation ADD COLUMN message_id VARCHAR(36);
ALTER TABLE ai_call_reservation ADD COLUMN call_purpose VARCHAR(32);
CREATE INDEX idx_ai_call_message ON ai_call_reservation(message_id,call_purpose);
