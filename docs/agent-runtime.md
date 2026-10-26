# 只读 Agent 运行、步骤与恢复

本阶段把会话中的“查订单归属 → 查物流”接到持久化运行器。用户仍在聊天框发送问题，前端的消息协议保持兼容。运行器记录每一步执行到哪里、哪些结果已经保存，遇到中断后可由明确的恢复请求继续。

当前流程由 Java 固定推进，模型只负责一次受限的订单号规划。它尚未实现“模型读取工具结果，再自由选择下一个工具”的自主多轮循环。退款申请、审批和模拟退款执行继续走原有业务链，不进入本运行器。

总期限在领取、开始步骤和提交结果时检查，超期结果不能继续推进。它不是强制杀死 Java 线程的承诺：已经发出的数据库查询仍受 JDBC、连接及数据库自身的超时策略约束；模型 HTTP 调用继续使用已有独立超时。

## 先沿一条请求理解数据怎么流动

例如买家在店铺 100 咨询“SF10001 的物流到哪里了”。身份来自后端认证，店铺来自 URL，订单号来自本轮消息或明确选择。模型不能提供买家身份、替换店铺、授予权限或批准退款。

```text
前端发送 message + clientMessageId + 可选 orderNo
  ↓
ConversationController → ConversationService.send
  ↓ 保留本轮消息，取得会话 processingToken
ConversationContextEngine
  ↓ 解析本轮明确订单或受限的历史指代
ConversationOrchestrator → AssistantAgent
  ↓ 仅 LOGISTICS 会话路径进入持久化运行器
ReadOnlyAgentRuntime.execute
  ↓
PLAN：规划并校验订单号，保存下一阶段
  ↓
ORDER_LOOKUP：检查当前买家、店铺及订单归属，保存安全结果摘要
  ↓
LOGISTICS_LOOKUP：再次核验当前归属，读取已录入的物流记录
  ↓
COMPLETE：保存最终回复，run 标记 SUCCEEDED
  ↓
ConversationService 保存用户消息与客服回复
  ↓
原有 ConversationTurnResponse → 前端聊天气泡
```

`PLAN` 中的模型结果只是提议。工具名只能是服务端允许的 `LOGISTICS_LOOKUP`；订单号必须来自本轮解析后的输入，不能凭空生成。模型未给出订单号或存在多单歧义时，运行器保存澄清回复并结束，不猜测订单。

物流来自本项目录入的事件，尚未连接真实快递查询服务。没有记录时会明确说明，没有物流数据不代表模型可以补写轨迹。

## 几个对象分别是什么

| 对象 | 保存什么 | 用途 |
| --- | --- | --- |
| `AgentRunKey` | 会话 UUID、消息 UUID、认证买家与店铺范围 | 把一次运行绑定到原请求；UUID 本身不授予访问权限 |
| `AgentRunInput` | 原消息请求，以及服务端解析后的规划输入 | 恢复时使用同一份输入，不重新套用后来变化的会话话题 |
| `AgentRun` | 当前阶段、状态、原始期限、已用尝试次数、租约代次和已保存结果 | 表示这次任务目前进行到哪里 |
| `AgentStepView` | 阶段、尝试序号、稳定操作 ID、状态与时间 | 展示步骤进度，不公开参数和中间结果原文 |
| `AgentRunView` | 对外运行进度及可恢复标志，成功时包含本轮结果 | 供进度接口读取；不暴露内部买家 ID、原请求或异常内容 |

正常流程有三个可执行阶段。`COMPLETE` 是结束状态，不再调用工具。每次真正开始阶段都会占用一次尝试；恢复一个尚未完成的只读阶段，也会再占用一次，不能通过反复恢复刷新额度。

## 类的职责、输入与返回

| 类或方法 | 谁调用 | 传入什么 | 返回什么、调用谁 |
| --- | --- | --- | --- |
| `ConversationService.send` | 原有聊天 Controller | 当前认证身份、店铺、会话、消息请求 | 返回原有消息轮次；调用上下文引擎并持久化消息 |
| `AssistantAgent.execute(..., AgentRunInput)` | 编排器 | 服务器确定的路由及运行输入 | 物流路径调用运行器，其他业务保留现有路径 |
| `ReadOnlyAgentRuntime.execute` | `AssistantAgent` | 认证身份与 `AgentRunInput` | 创建、领取并推进运行，返回内部 `AgentRun` |
| `ReadOnlyAgentRuntime.claimResume` | 恢复协调服务 | 当前认证身份与原 `AgentRunKey` | 重新核验归属后领取运行；此时不执行工具 |
| `ReadOnlyAgentRuntime.resumeClaimed` | 恢复协调服务 | 当前身份与已领取运行 | 核对代次后继续尚未完成的阶段，或复用已完成结果 |
| `AgentRunStore` / `JdbcAgentRunStore` | 运行器 | 运行范围、代次及阶段变更 | 在短事务中领取租约、登记执行意图、保存结果；不调用模型或工具 |
| `AgentRunConversationService` | `AgentRunController` | 当前身份、店铺、会话与原消息 UUID | 协调 run 与消息轮次恢复，最终返回原 `ConversationTurnResponse` |
| `AgentRunController` | 客户端显式查询或恢复 | URL 中的店铺、会话、原消息 UUID | GET 返回安全进度；POST 恢复返回消息轮次 |

代码入口：

- [运行器](../src/main/java/dev/supportflow/assistant/runtime/ReadOnlyAgentRuntime.java)
- [运行存储](../src/main/java/dev/supportflow/assistant/runtime/store/JdbcAgentRunStore.java)
- [恢复协调](../src/main/java/dev/supportflow/chat/conversation/AgentRunConversationService.java)
- [恢复 API](../src/main/java/dev/supportflow/chat/conversation/AgentRunController.java)

## Checkpoint 与聊天上下文有什么区别

聊天上下文回答“用户上一句提到的是哪笔订单、哪份资料”。它用于理解“这单到哪了”等受限追问，不能证明订单当前仍归这个买家所有，也不是执行授权。

Checkpoint 回答“这次运行的规划是否完成、订单步骤是否保存、下一步该执行什么”。它用于执行恢复。已经成功落库的步骤不重新规划或再次调用相同工具步骤；进入后续工具或读取完成结果时，仍会单独查询当前归属。安全复核可能产生新的订单查询，它不等于重新执行已经完成的步骤。

例如订单步骤已经保存，物流查询暂时失败：运行保持在 `LOGISTICS_LOOKUP`。显式恢复会从物流阶段继续，不再次调用规划器，也不重做已完成的 `OrderTool` 步骤，但会由 `OrderQueryService` 重新检查当前订单归属后再查物流。

存储使用两张新表：

- `support_agent_run`：一条原消息最多对应一条运行，保存固定输入、阶段、预算、租约和最终结果。
- `support_agent_step`：保存各次尝试的执行意图、成功状态及有上限的安全结果。相同阶段恢复时操作 ID 保持稳定，尝试序号递增。

每一步执行前先保存 `STARTED` 意图，执行成功后在一个短事务内保存步骤结果并推进运行阶段。模型、网络与工具调用均在这些事务之外，避免长时间持有数据库锁。

## 预算、租约与 fencing

默认每次运行最多 **8 次步骤尝试**、**90 秒总期限**，配置前缀为 `supportflow.agent.runtime`。上限会在创建 run 时保存，恢复仍使用原额度和原期限；改变配置不会给已有运行重新计时。

```yaml
supportflow:
  agent:
    runtime:
      max-steps: 8
      total-timeout-seconds: 90
```

配置允许步骤尝试上限 1–32、总期限 1–100 秒。正常三阶段流程需要三次尝试；配置小于三次时，包含实际物流查询的运行会按预算终止。当前预算不包含 Token 或费用账本。

领取运行时获得 **20 秒租约**和递增的 `generation`。推进阶段会续租，但续租不能越过该运行创建时保存的总期限。执行前与结果保存时检查期限；这些检查不会强行终止任意 Java 线程，下游调用仍需要自己的超时机制。

例如旧执行者持有 generation=1，租约到期后恢复执行者领取 generation=2。旧执行者即使晚到，也不能用 generation=1 保存结果、推进阶段或覆盖新的运行状态，这个保护称为 fencing。

消息轮次还有独立的 `processingToken`，由 V12 增加到 `support_chat_turn`。恢复服务先领取 run，再保留消息轮次的新 token，最后执行已领取的 run。最终保存或失败标记都核对消息 token，避免旧请求把新执行者的结果覆盖掉。

达到步骤尝试上限或超过总期限的未完成运行不再启动新步骤。已经成功保存的最终结果可以用于补齐尚未完成的消息落库，因为这个过程不再调用工具，也不会补充运行预算。

## 显式恢复 API

以下接口沿用当前开发认证与 Spring Security CSRF 保护。身份必须是具备 `ORDER_READ` 权限的买家；服务器同时校验买家、店铺、会话和原消息归属。不能通过修改 URL 的 UUID 读取其他人的运行。

```http
GET /api/v1/shops/{shopId}/chat/conversations/{conversationId}/runs/{clientMessageId}
```

GET 只读取进度，不会自动继续执行。返回的 `status`、`phase`、`attempts`、`maxSteps`、`deadlineAt`、`steps` 用于理解执行情况，`resumable` 表示当前是否可以显式恢复。只有运行已成功时才展示本轮结果文本，中间工具快照不会作为公开结果返回。

```http
POST /api/v1/shops/{shopId}/chat/conversations/{conversationId}/runs/{clientMessageId}/resume
```

恢复 POST 不接收可覆盖的订单号、原消息、预算、买家身份或工具名称。它只使用服务端保存的原请求，带有效认证与新的 CSRF token 后提交。成功时返回既有 `ConversationTurnResponse`，不会增加一种新的聊天消息格式。

恢复条件包含运行状态与消息轮次状态：

- 模型或工具的可恢复故障会使 run 进入 `PAUSED`；原期限、剩余尝试和当前权限仍满足时，可以显式恢复。
- 进程中断留下的 `RUNNING` run，必须等它的 20 秒租约到期才可接管；未完成的只读阶段可以再执行一次。
- run 已成功，但最终消息事务明确失败、turn 已为 `FAILED` 时，可以直接补齐消息，不重新调用模型或工具。
- run 已成功，而 turn 仍为未过期的 `PROCESSING` 时，不能立即抢写消息。需等 turn 原有 **120 秒处理租约**到期；这期间 GET 的 `resumable=false`。
- 当前轮次已经成功保存时，进度查询的 `resumable=false`；重复恢复只返回原有完成结果，不新增消息。
- 未完成的旧 run 后面已有新的会话轮次时，不允许把旧回复插回当前话题，返回 409。
- 权限或归属校验失败、运行已过期或尝试次数耗尽时，不允许继续工具执行。

普通聊天发送仍遵守原规则：同一个 `clientMessageId` 已经失败，重新 POST 原消息会返回 **409**，不会悄悄重放。显式 `/resume` 是对已登记只读运行的受控恢复，不是重新执行整条聊天流水线。读取历史、连接账号和 GET 进度均不自动恢复任务。

恢复返回的是原运行保存的结果快照。恢复时重新核验归属，不代表重新查询了所有物流事件；因此恢复响应不把旧结果自动变成新的会话话题。需要最新轨迹时，应发送一条新的查询。

## 为什么退款不放进这里

这个运行器只调用订单与物流读取能力。即使模型提议 `AFTER_SALE_REQUEST` 或其他工具，运行器也会拒绝，不会通过反射找到并执行任意 Java 方法。

退款继续由用户填写原因、后端核验期限、商家人工审批，再交由原有持久化模拟执行任务处理。Checkpoint 只能保存“执行到哪里”，不能单独保证外部写操作只发生一次。若以后接入真实付款或退款渠道，还需要稳定的业务幂等键、渠道结果查询、结果不明时的核实与对账，不能把“超时”当成“没有退款”。

本阶段不声称已经实现通用写工具恢复、真实支付的 exactly-once、自主 LLM 多轮规划、Token/费用账本、通用人工审批暂停恢复或 Circuit Breaker。已有模型并发上限是并发隔离能力，不能等同于熔断器。

## 迁移和最小运行权限

[V12 迁移](../src/main/resources/db/migration/V12__agent_run_checkpoints.sql)新增两张运行表，并给消息轮次增加 `processing_token`。既有 V1–V11 不修改，不重写成功迁移校验值，也不插入业务数据。

本地业务库正常执行 V12 后，数据库管理员在正确的 `127.0.0.1:3306` 数据源执行 [agent-runtime-grants.sql](../config/agent-runtime-grants.sql) 的全部内容。它仅为 `supportflow_app@127.0.0.1` 增加两张运行表的 `SELECT / INSERT / UPDATE`，不授予 `DELETE`、DDL 或授权权限。原有会话表权限保持不变。

应用账号与迁移账号没有管理员权限时，不能自行给应用授权。未配置 root 凭证的日常环境，应由有权限的管理员执行明确脚本，不应把应用切换为 root 或扩大数据库全局权限。凭证继续留在被 Git 忽略的本地私密配置中。

## 回归证据从哪里看

2026-10-26 的全量后端回归为 **434 项单元测试、272 项集成测试通过**。步骤状态、故障码和暂停参数统一为枚举后，另外重跑相关 **41 项单元测试和 19 项真实 MySQL 集成测试**，全部通过。V12 一次性后端的物流与历史浏览器回归 2 项通过；独立实验部署的 15 组真实 HTTP 检查通过，创建 1 个会话轮次，业务变更请求和真实模型调用均为 0。

以下测试使用 Mock 或隔离的 Testcontainers MySQL，不需要真实模型 Key，不操作日常业务库；具体运行结果以本次构建报告为准：

- [ReadOnlyAgentRuntimeTest](../src/test/java/dev/supportflow/assistant/runtime/ReadOnlyAgentRuntimeTest.java)：正常阶段推进、恢复跳过已完成步骤、权限变化、错误工具、伪造订单号、无自动重试、预算以及过期执行者保护。
- [AgentRuntimePropertiesTest](../src/test/java/dev/supportflow/assistant/runtime/AgentRuntimePropertiesTest.java)：默认额度、可配置范围与未知配置项拒绝。
- [JdbcAgentRunStoreIT](../src/test/java/dev/supportflow/assistant/runtime/store/JdbcAgentRunStoreIT.java)：真实数据库中的唯一运行、原始输入绑定、步骤持久化、并发领取、租约代次、原始期限与尝试次数约束。
- [AgentRuntimeApiIT](../src/test/java/dev/supportflow/chat/api/AgentRuntimeApiIT.java)：真实安全过滤器与数据库下的运行进度、显式恢复、完成结果补齐、消息不重复和归属隔离。
- [ConversationApiIT](../src/test/java/dev/supportflow/chat/api/ConversationApiIT.java)：既有会话顺序、幂等、失败请求不重放与上下文边界继续成立。

这些测试验证的是当前受控流程和故障注入场景。是否具备跨机器调度、容器强制退出后的完整恢复时限及生产容量，仍需相应部署和故障演练，不能从单元测试结果推断。
