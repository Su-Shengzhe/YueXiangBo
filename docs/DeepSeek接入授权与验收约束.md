# DeepSeek接入授权与验收约束

确认日期：2026-11-10。

用户明确授权DeepSeek首次兼容性与供应商切换验收发生实际费用，累计上限人民币5元，同时计入既有阶段50元总预算。该授权不扩大其他供应商的付费权限，不等同于DeepSeek免费资格。

- 接口：https://api.deepseek.com/chat/completions
- 网关Base URL：https://api.deepseek.com
- 模型：deepseek-flash
- 凭据位置：deploy/secrets/deepseek.properties，属性api-key；密钥不得进入代码、文档或聊天。
- 本次检查时凭据文件尚不存在，未发起DeepSeek调用。

调用前必须补齐付费授权与独立5元额度的持久化控制，并与阶段总预算原子预留。当前代码主要使用freeAccessApproved/free_access_confirmed表示免费资格，不能将其设为true来冒充本次付费授权。配置预检查产生的调用同样计费；超时或结果未知的调用保留预留，不能因切换路由或重启重置预算。

实际价格须在接入时核对官方价目，并保存价格版本及折算依据。若没有可验证的人民币计费或保守费用上界，不执行付费调用。业务兼容性检查与Agnes→DeepSeek→Agnes切换均须保存证据；供应商账单仍是实际扣费核对依据。
