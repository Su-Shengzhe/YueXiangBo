CREATE TABLE ai_session(id VARCHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,version BIGINT NOT NULL DEFAULT 0,status VARCHAR(20) NOT NULL DEFAULT 'READY',intent VARCHAR(40) NOT NULL DEFAULT '',slots_json TEXT NOT NULL,turns INT NOT NULL DEFAULT 0,active_request_id VARCHAR(36),processing_token VARCHAR(36),lease_until DATETIME(3),created_at DATETIME(3) NOT NULL,last_active_at DATETIME(3) NOT NULL,INDEX ix_ai_owner(user_id,last_active_at),INDEX ix_ai_lease(status,lease_until));
CREATE TABLE ai_message(request_id VARCHAR(36) PRIMARY KEY,session_id VARCHAR(36) NOT NULL,user_id BIGINT NOT NULL,request_key VARCHAR(80) NOT NULL,request_hash CHAR(64) NOT NULL,status VARCHAR(20) NOT NULL,route_version BIGINT,result_json TEXT,created_at DATETIME(3) NOT NULL,finished_at DATETIME(3),UNIQUE(session_id,request_key),INDEX ix_ai_message(session_id,created_at));
CREATE TABLE knowledge_rule(rule_id VARCHAR(64) NOT NULL,community_id BIGINT NOT NULL,PRIMARY KEY(rule_id,community_id));
CREATE TABLE knowledge_entry(id BIGINT AUTO_INCREMENT PRIMARY KEY,rule_id VARCHAR(64) NOT NULL,community_id BIGINT NOT NULL,rule_version INT NOT NULL,title VARCHAR(128) NOT NULL,topic VARCHAR(40) NOT NULL,keywords VARCHAR(500) NOT NULL,content TEXT NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',effective_at DATETIME(3) NOT NULL,expires_at DATETIME(3),version BIGINT NOT NULL DEFAULT 0,UNIQUE(rule_id,community_id,rule_version));
INSERT INTO knowledge_rule VALUES('BILLING',0),('CANCEL',0),('REFUND',0),('ARRIVAL',0),('BOOKING',0);
INSERT INTO knowledge_entry(rule_id,community_id,rule_version,title,topic,keywords,content,status,effective_at) VALUES
('BILLING',0,1,'停车费用规则','BILLING','计费 费用 收费 超时 提前 离场 价格','预约费与超时费分别按30分钟单位向上取整，提前离场不退预约费。无免费宽限、优惠券和跨日封顶。价格以订单下单快照为准。','PUBLISHED','2026-10-16 00:00:00'),
('CANCEL',0,1,'预约取消规则','CANCEL','取消 退订 退款 开始前','司机可在预约开始前取消。未付款订单关闭并释放；已付款订单申请全额可退余额退款。取消不等于退款成功，退款结果另行展示。','PUBLISHED','2026-10-16 00:00:00'),
('REFUND',0,1,'费用争议与退款规则','REFUND','退款 争议 七天 7天 驳回 证据','用户可在订单完成或关闭后7天内提出费用争议，达到截止即不受理。驳回后在期限内补充新证据并关联原申请。结果未知的退款不算成功退款。','PUBLISHED','2026-10-16 00:00:00'),
('ARRIVAL',0,1,'入场时间规则','ARRIVAL','入场 迟到 到场 未到 占用','仅已确认订单可入场，允许在预约开始至开始后15分钟之前入场。达到截止仍未入场则按原因关闭并申请退款，退款状态单独展示。','PUBLISHED','2026-10-16 00:00:00'),
('BOOKING',0,1,'预约时段规则','BOOKING','预约 时段 时间 时长 提前','预约起止按15分钟对齐，时长30分钟至24小时，开始时间至少晚于当前15分钟，且须被同一个共享窗口覆盖。查询结果不锁定库存，提交时重新校验。','PUBLISHED','2026-10-16 00:00:00');
ALTER TABLE ai_call_reservation ADD COLUMN input_tokens BIGINT;
ALTER TABLE ai_call_reservation ADD COLUMN output_tokens BIGINT;
ALTER TABLE ai_call_reservation ADD COLUMN actual_charge_microyuan BIGINT;
ALTER TABLE ai_call_reservation ADD COLUMN price_version VARCHAR(64) NOT NULL DEFAULT 'agnes-list-usd-fx8-v1';
ALTER TABLE ai_call_reservation ADD COLUMN detail VARCHAR(100);
ALTER TABLE ai_budget ADD COLUMN paused BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_budget ADD COLUMN free_access_confirmed BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE ai_budget ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
-- Accounted one authorized pre-integration compatibility probe: 559 input + 71 output tokens.
INSERT INTO ai_call_reservation(request_id,route_version,reserved_microyuan,charged_microyuan,status,input_tokens,output_tokens,actual_charge_microyuan,detail) VALUES('agnes-compatibility-probe-20261106',0,10000,309,'SETTLED',559,71,0,'Pre-integration tool compatibility probe');
UPDATE ai_budget SET spent_microyuan=spent_microyuan+309 WHERE id=1;
