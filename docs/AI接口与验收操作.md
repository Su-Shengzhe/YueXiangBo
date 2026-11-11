# AI接口与验收操作

业务入口统一为 `/api/v1`。登录后使用 Bearer token；模型凭据仅在服务端 `deploy/secrets/<credentialRef>` 文件中保存，属性名为 `api-key`。不要提交密钥文件。

## 正式会话

| 方法与路径 | 输入及行为 |
| --- | --- |
| POST `/ai/sessions` | `{}`；返回 `sessionId`、`sessionVersion` |
| POST `/ai/sessions/{id}/messages` | 请求头 `Idempotency-Key`；正文 `text`（最多500字）、`expectedSessionVersion`、可选 `context.reservationId`、`newTask` |
| GET `/ai/sessions/{id}/messages` | `page`、`size`；返回分页消息及 `sessionVersion`、`sessionStatus`、`activeRequest` |
| DELETE `/ai/sessions/{id}` | 关闭本人非处理中的会话 |

消息结果包括 `requestId`、`sessionVersion`、`status`、`answer`、`cards`、`citations`；账单解释包含 `bill`。状态为 ANSWERED、CLARIFY、FALLBACK、REFUSED、FAILED；处理中幂等重试返回 HTTP 202 和 PROCESSING。接口均使用现有业务响应封装。

网络中断时先读历史或用原请求键、原正文重试，不能换键重复调用。会话有所有权校验、版本校验、5轮任务限制、后台租约恢复及30天清理。模型只允许 searchAvailableSpaces、retrieveParkingRules、getMyOrderBill 三个只读工具，不执行预约、付款或退款。

## 管理

| 权限 | 接口 |
| --- | --- |
| MODEL_MANAGE（平台管理员） | GET `/admin/ai-gateway`；POST `/providers`、`/publish`、`/rollback`、`/budget`；PATCH `/providers/{id}/status`；POST `/calls/{id}/reconcile`（后续路径均追加在 `/admin/ai-gateway` 后） |
| KNOWLEDGE_MANAGE | GET/POST `/admin/knowledge`；PATCH `/{id}`；POST `/{id}/publish`、`/{id}/disable` |
| AI_LOG_READ | GET `/admin/ai-call-logs?page=1&size=20&status=RESERVED`（status可省略） |
| ACCOUNT_AUTHORIZE | PUT `/admin/ai-gateway/access/{id}`；正文 `version` 和 `permissions`（仅三个AI权限） |

配置发布/回退、知识编辑/发布/停用和预算控制必须携带当前 `version`。供应商配置不可覆盖，新增版本后发布；单次消息后续模型调用复用捕获的配置，活动指针变更无需重启业务。当前适配 OPENAI_CHAT 兼容协议；其他协议需新增适配器。

预算以微元计量，1元=1,000,000微元。上限50元，提醒40元。请求前在数据库事务中预留，返回usage后按配置价格结算；未知结果保留预留，禁止自动重试。402暂停新调用；管理员可凭供应商账单核对未知记录，核对出现实际收费时自动暂停。普通200响应无法证明免费，实际扣费未核对时为未知，不能视为零。

## 可重复验收

1. `./scripts/Build-Backend.ps1`：完整后端构建及测试。
2. `./scripts/Stop-Business.ps1` 后 `./scripts/Start-Business.ps1`：加载已验证构建并应用MySQL迁移，保留数据卷。
3. `./scripts/Test-AiAcceptance.ps1`：真实业务服务中的权限、会话所有权、知识生命周期、用量持久化；不调用供应商。
4. `./scripts/Test-AiAcceptance.ps1 -LiveModel`：额外发布已授权Agnes配置、验证暂停限额、真实规则/车位/账单工具及幂等恢复。会创建测试知识条款后停用，创建未付款预约后取消，关闭测试会话。输出脱敏证据到 `.runtime/business/ai/<时间>/results.json`。
5. `./scripts/Test-BusinessBrowser.ps1 -TestFilter 'AI management'`：真实管理页面与会话刷新；脚本自动启动临时前端，运行前不要占用5174端口。

以上是功能冒烟入口，不等同于计划中的100条固定评测、第二真实供应商切换或M3完整结算后退款验收。
