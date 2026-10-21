# 学习版客服架构与运行验收

本轮以用户给定的架构为目标，选择在本项目内实现学习版知识库、物流和售后模块，人工节点由客服/管理员审批。不是对接外部企业系统，不会产生真实快递查询、退款或支付结果。

## 一次请求经过哪里

```text
Vue 店铺客服页面
  → Java ChatController（认证、CSRF、请求校验）
  → ConversationOrchestrator / ConversationRouter
     ├─ KNOWLEDGE → KnowledgeService → 本地版本化文档 → 引用式知识答复
     └─ Agent → 允许的工具 / 参数校验
         ├─ 订单：复用既有 ChatService / OrderQueryService
         ├─ LogisticsTool → 归属校验后读取 support_shipment_event
         └─ AfterSaleTool → AfterSaleService → 待审核售后单 + 审计
                                              ↓
                        客服/管理员 → 人工审批页 → Java 再校验
                                              ↓
                             APPROVED / REJECTED + 审计
                                  ↓ APPROVED
                          持久化执行任务 → 模拟退款回执
                                              ↓
                                 买家发送「我的售后」查看结果
```

订单旧入口和 DTO 保持兼容；新增编排不是重写所有订单代码。Java 方法之间是 JVM 内调用；前端/后端、模型 HTTPS、JDBC/MySQL 分别跨网络边界。

## 实现范围与明确限制

- Router 是服务器掌控的确定性路由，不让模型自行将读请求升级为写操作。显式固定指令「申请售后」优先，原因中含“政策/规则”也不会被误分流。
- 物流/售后 Agent 每次最多规划并执行一个允许的工具。启用 DeepSeek 时使用官方 `tools/tool_choice/tool_calls` 协议；关闭时使用离线规则规划器。模型只能返回 `orderNo`，不能提供买家、店铺、金额、SQL、权限或审批结果。
- Java 校验订单号证据、歧义、当前店铺、可信买家身份与订单归属。出现多个不同显式 SF 单号时要求重新选择，不能让模型任挑一笔。
- RAG 当前采用版本化 Markdown、关键词/中文二元检索、原文抽取和逐源引用。无命中会拒答。这是可解释的检索/引用学习基线，不是向量数据库或让模型自由生成企业政策的系统。
- 知识文档是本项目服务说明，不包含用户未提供的真实企业退款政策。文档路径为 `src/main/resources/knowledge`，更新时同步版本与测试；当前都属于公开的学习说明，不包含私有租户知识。
- 物流只读本项目实际录入的事件；无事件明确说暂无录入，不从 SHIPPED 猜运输轨迹。当前无物流录入后台，业务数据需受控导入；仅测试容器预置演示轨迹。
- 售后创建/审核申请；批准后在同一事务创建持久化任务，后台模拟退款并记录进度、金额和模拟回执。`APPROVED` 是人工结果，`SUCCEEDED` 是模拟结果；不修改订单金额/状态，不调用支付渠道。执行、重试与恢复细节见 [售后执行说明](after-sale-execution.md)。
- 一笔归属订单仅创建一张售后单。重试或更改原因重复发送会返回原单，不覆盖原原因；当前不支持拒绝后自动创建新单、部分退款、多次售后或撤销审批。
- 普通聊天不持久保存。售后申请与人工决定存入 MySQL，可跨页面/账号重新查询；没有后台自动通知或自动刷新聊天，买家点击「我的售后」获取最终结果。

## 可直接操作的流程

1. 以买家账号进入店铺，例如 `?shopId=100`，连接后选订单。
2. 「服务规则」或发送「售后审批规则」：展示知识来源和版本。
3. 「查物流」：按已选订单读取本项目物流事件。
4. 「申请售后」只预填命令，不自动提交。补充原因后发送，例如 `申请售后 商品破损`；未选订单可明确发送 `申请售后 SF10001 商品破损`。
5. Java 检查订单属于当前买家和店铺，且不是 CREATED/CANCELLED，再创建 `PENDING_REVIEW`。没有订单号则返回候选；没有原因不会写入。
6. 普通买家点击「人工审核」会被服务器拒绝，不能通过改前端按钮绕过。
7. 断开并以独立的客服/管理员账号连接，打开「人工审核」，输入备注，人工选择「确认批准」或「拒绝」。不能审批与自己同 buyerId 的申请。
8. 买家重新连接发送「我的售后」：看到持久化状态和审核意见。该消息的店铺与买家范围仍由后端限制。

## 首次在你的本地业务库启用前

功能代码使用隔离测试验证；旧账号需要明确追加新表权限。日常库另行导入的演示数据见 [本地测试数据说明](local-demo-data.md)。

1. 保留原 `local` profile、数据源和 Flyway 迁移账号配置。由本地应用启动时让迁移账号按既有机制执行 **V4、V5**，不改旧版本迁移。
2. V4 建业务表；V5 新增执行任务与模拟回执表。迁移本身不插入演示订单，不自动执行历史审批。
3. 原 `supportflow_app` 历史只有 SELECT，售后写入会失败。数据库管理员核对目标环境后，手动执行 [最小增量授权 SQL](../config/assistant-runtime-grants.sql)。此脚本不是 Flyway 迁移，不会自动执行；只给售后表 INSERT/UPDATE、审计表 INSERT，不给订单写权限或 DELETE/DDL。
4. 在原本被忽略的本地安全配置 `supportflow.security.local.accounts` 中**追加**一个独立客服账号。参考 [审核账号模板](../config/reviewer-account.example.yml)，替换用户名、独立 buyerId 和密码哈希，保留现有买家账号。
5. 审核权限使用枚举 `AFTER_SALE_REVIEW`；完整审核账号还需 `ORDER_READ` 做审批前业务复核。本学习版此权限是明确的全平台待审队列权限，不是按店铺分配的客服权限；生产系统需要进一步建立员工组织/店铺授权。
6. 凭据留在 `.local`，不得提交 Git；浏览器连接时填原始登录密码，不是数据库密码或密码哈希。前端/API继续沿用 Basic + CSRF，仅用于本地开发，不能暴露到公网。

数据库要求 MySQL 8.0.22+（本项目测试/本机版本 8.0.45）。售后事务以 `FOR SHARE` 读取并锁定当前订单，避免审核采用旧状态；这个版本起只需要订单 SELECT 权限。[MySQL 官方锁定读说明](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)

## 状态、一致性和审计

- 售后状态使用枚举 `PENDING_REVIEW → APPROVED / REJECTED`，没有模型可调用的 approve/refund 工具。
- 服务端根据已授权订单生成唯一幂等摘要。申请用持排他键锁的 UPSERT 串行化，再比较本次 UUID 判断是否首次创建，避免多路重复键共享锁升级死锁；不会用 SQL 受影响行数猜首次创建。
- 订单共享锁、售后单与审计写入都处于同一事务。审核用 `version + PENDING_REVIEW` 条件更新，重复或竞争审批返回 409，不写第二份审核审计。
- 申请提交与审核动作写入独立审计表；不记录密码、API Key 或模型原始响应。
- 不提供订单写权限；真实支付、真实快递、部分退款、多租户员工权限、向量检索/私有文档 ACL、后台通知不在这个学习版闭环内，不应在面试中宣称已经实现。

## 新增 HTTP 接口

| 接口 | 范围与条件 |
| --- | --- |
| POST `/api/v1/shops/{shopId}/chat/messages` | 兼容原聊天 DTO，新增编排/知识/物流/售后；认证 + CSRF |
| GET `/api/v1/shops/{shopId}/after-sales` | 当前认证买家的当前店铺申请，最近最多50笔 |
| GET `/api/v1/reviews/after-sales` | `AFTER_SALE_REVIEW`，全平台最早50笔待审 |
| POST `/api/v1/reviews/after-sales/{uuid}/decision` | 人工 `{decision: APPROVE或REJECT, note: 审核意见}`；独立权限 + CSRF + 禁止自审 |
| GET `/api/v1/reviews/after-sales/history` | 管理员最近50条审批与处理记录 |
| POST `/api/v1/reviews/after-sales/{uuid}/execution/retry` | 管理员提前重试临时失败任务；权限、CSRF、禁止本人重试、累计次数限制 |

## 复现验收

根目录 `mvn -o verify`：单元、安全/数据库/迁移/并发集成测试。前端 `npm.cmd run check`、`npm.cmd run build`、`npm.cmd run test:e2e`：类型、状态流、构建、模拟接口浏览器测试。

真正的浏览器/Java/MySQL闭环使用 `FrontendTestServer`。它创建一次性 MySQL、执行 V1–V5、开启模拟执行 Worker、强制关闭 DeepSeek、不加载 `.local`，只监听 `127.0.0.1:18081`。启动方式见 [前端运行说明](../frontend/README.md)。公开测试账号为 `frontend-buyer`、`frontend-other`、`frontend-reviewer`，密码均为源码中的测试专用 `Frontend-Test-Only!`，只能用于该临时实例。不要将它们复制到本地真实配置。

DeepSeek function calling 的协议已由 mock HTTP 单测验证，没有为这轮验收消耗真实模型额度。[DeepSeek 官方工具调用文档](https://api-docs.deepseek.com/guides/tool_calls/)

### 2026-10-21 验收快照

- 后端 `mvn -o verify`：173 项单元测试、115 项集成测试，全部通过，构建成功。
- 前端类型检查、生产构建及 66 项 API/状态单元测试通过。
- Edge 浏览器：18 项独立模拟接口回归、6 项真实 Java + 一次性 MySQL 验收通过，包括知识引用、跨店物流隔离、售后申请、买家权限拦截、独立管理员批准/拒绝与买家查询结果。
- 并发/回滚场景包含四路重复申请、双管理员竞争、审计写入失败回滚、审批等待订单取消事务后拒绝旧状态。测试数仅表示当前用例的通过情况，不代表生产环境或所有情况均已覆盖。
