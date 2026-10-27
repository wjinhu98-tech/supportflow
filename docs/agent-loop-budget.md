# 只读多轮 Agent 与运行预算

V14 增加共享模型熔断和持久化的只读步骤重试，详见[Agent 故障治理](agent-resilience.md)。下文预算与 Checkpoint 规则继续生效；失败后的新模型尝试，无论自动重试还是显式恢复，都需要重新预留额度。旧运行的自动重试保持关闭。

V13 起，新建的物流咨询使用 `workflowVersion=2`。每次工具执行后，模型会收到已保存的工具结果，再选择下一步；Java 校验动作、参数、归属、是否重复以及是否已经具备回答依据。最终回答由 Java 拼接真实查询结果，模型不能提供退款决定或编造业务事实。

V12 的 `workflowVersion=1` 运行继续由原三阶段执行器恢复。迁移不会把旧运行改成新协议、清空已执行步骤或补发模型请求。原有普通查单、知识检索和人工审批接口保持各自的业务边界。

## 一次请求如何流动

```text
ConversationService → ConversationContextEngine → AssistantAgent
  ↓
ReadOnlyAgentRuntime 创建 V2 运行并领取租约
  ↓
PLAN：先持久化本次模型调用的 Token / 估算费用预留
  ↓
AgentDecisionPlanner 读取问题 + 已完成工具结果，返回一个动作
  ↓
Java 校验：工具白名单、原订单号、前置事实、是否重复
  ├─ get_order → 重新校验归属 → OrderTool → 保存公开订单事实 → PLAN
  ├─ get_logistics → 重新校验归属 → LogisticsTool → 保存物流结果 → PLAN
  ├─ finish → Java 从已执行结果组织回复 → COMPLETE
  └─ clarify → 缺少或存在多单歧义时请求明确订单 → COMPLETE
```

例如“SF10001 发货了吗，快递到哪了”：

1. 模型请求 `get_order`，Java 返回经过归属校验的订单结果。
2. 订单为 `SHIPPED / COMPLETED` 时，模型可请求 `get_logistics`；还未发货则应结束并展示订单事实。
3. 物流结果返回后，模型请求 `finish`，Java 返回订单状态、金额和已录入物流信息。

已发货分支正常保存五个步骤：`PLAN → ORDER_LOOKUP → PLAN → LOGISTICS_LOOKUP → PLAN`。待发货分支正常保存三个步骤，缺少订单时可以直接澄清。它们使用同一个循环，不是给不同问题分别写一段固定调用顺序。

DeepSeek 适配器把已完成观察重新构造成匹配的 `assistant.tool_calls` 与 `tool` 消息，保留调用 ID；不会把工具文本提升成 system 指令。模型只能返回四种函数之一，函数参数只能包含 `orderNo`。模型给出的自由文本、身份、权限、金额决定或私有思考均不作为业务结果保存。[官方 Tool Calls 说明](https://api-docs.deepseek.com/guides/tool_calls/)

关闭 DeepSeek 时，由规则决策器在每轮读取同样的观察结果并选择动作；它用于离线运行和测试，模型 Token 与费用均为零。

## 类与职责

| 类                              | 输入与返回                                          | 作用                                                         |
| ------------------------------- | --------------------------------------------------- | ------------------------------------------------------------ |
| `AgentDecisionPlanner`          | `AgentDecisionInput → AgentDecisionResult`          | 一轮模型决策端口；返回动作及供应商 usage                     |
| `DeepSeekAgentDecisionPlanner`  | 问题、受限观察及单次额度 → 严格解析的调用           | 真实 DeepSeek 适配；与已有识别器共用并发限制、超时和响应上限 |
| `RuleBasedAgentDecisionPlanner` | 同一决策输入 → 零费用决定                           | 关闭模型时的确定性替代                                       |
| `AgentLoopExecutor`             | 当前认证身份、已领取运行 → 新 Checkpoint 或完成结果 | 推进循环，执行只读工具，拦截重复工具和非法顺序               |
| `AgentLoopState`                | 公开事实观察列表与待执行决定                        | 恢复工具步骤时直接使用保存的决定，不重新付费规划             |
| `AgentBudget`                   | 固定额度、费率与累计消耗 → 新的不可变预算           | 预留、usage 校验和已知结果结算；金额使用 `BigDecimal`        |
| `JdbcAgentRunStore`             | 四重归属范围、代次、步骤及预算                      | 在短事务中原子登记步骤意图、预算变更与 Checkpoint            |

`AgentAction`、步骤状态、计量状态及故障码都使用枚举。动作映射通过显式分支，不从模型文本反射类名或方法名。

## 模型预算怎样生效

默认设置位于 `application.yml`：

```yaml
supportflow:
  agent:
    runtime:
      max-steps: 8
      total-timeout-seconds: 90
      max-total-tokens: 60000
      max-cost-usd: 0.05
      input-token-allowance: 16384
      output-token-allowance: 512
      pricing-model: deepseek-flash
      input-usd-per-million: 0.30
      output-usd-per-million: 1.20
```

这是**单次 V2 AgentRun 的预算**，不会统计其他无状态识别或旧售后规划接口的费用，也不是供应商账户余额控制。模型价格模型名必须与当前使用的模型一致；更换模型须同步配置对应费率。

每轮实际发模型请求之前，数据库先预留 `inputAllowance + outputAllowance`，并按配置费率预留估算美元费用。两项预留投影任何一项超过剩余额度，都在请求发出前结束运行。规则决策器不预留付费额度，但步骤和期限限制仍生效。

适配器限制完整请求的 UTF-8 字节数并预留协议余量，限制输出 `max_tokens`，以保守额度控制请求。这个方法不声称本地能精确复制供应商 tokenizer。模型结果必须有合法整数的 `prompt_tokens / completion_tokens / total_tokens`，总和必须一致且不超过预留范围。[官方 API usage 字段](https://api-docs.deepseek.com/api/create-chat-completion/)

结构与业务决策均有效、Checkpoint 成功保存后，只结算供应商报告的实际 Token，释放本次未使用的预留。原子提交同时更新步骤结果、预算和下一阶段；重复提交、旧 generation 或已完成步骤不能再退款式释放预算。

超时、连接断开、无法解析的响应、缺少 usage、非法决定或保存失败，都可能无法确认最终计费。此时保留整份预留，不把“不知道”记为零。显式恢复新模型请求要再预留一份，原来的预留和原始总额度不会被重置。恢复已保存的工具决定不再调用模型。

公开进度中的 `budget` 分开显示：

- `chargedTokens`：已结算使用量加尚未确认的预留量。
- `reportedTokens`：已随成功 Checkpoint 结算的供应商报告量。
- `unconfirmedTokens`：仍保留的未确认额度；不等于已确认实际消费。
- `estimatedCostUsd / costLimitUsd / currency=USD`：按本轮冻结费率计算的估算额度。

每个步骤还记录 `NONE / RESERVED / SETTLED` 计量状态及预留量、报告量和估算费用。故障恢复后可以辨别“零费用规则步骤”和“供应商结果不明但已预留的请求”。

默认费率取 2026-10-27 官方页面中 `deepseek-flash` 的缓存未命中、高峰输入和输出价格，忽略缓存优惠。实际价格、时段及计费规则可能变化，因此这里控制的是配置费率下的估算费用，不能保证供应商最终账单绝不超过这个数字。费率会在运行创建时冻结，恢复不能改用较便宜的新费率清空旧额度。[官方价格说明](https://api-docs.deepseek.com/quick_start/pricing/)

## 校验和恢复边界

- 模型提议的订单号必须来自原请求、服务端解析输入或明确选中的订单。第一次绑定后不能换单。
- 没有订单观察时不能查物流；已经完成的工具不能再次调用；已发货却没有物流结果时不能提前声称完成物流查询。
- 退款、审批、支付、SQL 和任意工具名不在此运行器白名单内。已有退款审批链不变。
- 工具执行前重新查询当前买家与店铺归属。保存的观察结果不能替代当前授权。
- 单次循环的每个模型、工具尝试都占用步数，失败与恢复不补额度；总期限仍在每个阶段开始与结果提交时检查。
- 每次模型决定先落库，再执行其选择的工具；每次工具结果保存后再调用模型。进程中断后不会重跑已完成工具。
- run generation 与会话 processing token 继续分别保护步骤保存和最终消息保存。后续新轮次已存在时，不能补写旧运行覆盖当前话题。
- 旧消息使用相同 `clientMessageId` 失败后仍返回冲突，不会把“重新发送”变成自动重放。恢复继续使用原显式 GET / POST 接口。

进度入口：

```text
GET  /api/v1/shops/{shopId}/chat/conversations/{conversationId}/runs/{clientMessageId}
POST /api/v1/shops/{shopId}/chat/conversations/{conversationId}/runs/{clientMessageId}/resume
```

GET 不执行模型或工具；POST 不接收覆盖原参数的新消息、订单号、额度或审批意见。完成结果仍是原轮查询快照；恢复不自动把它更新成新的实时事实或重新续期话题。

## 迁移与验证

[V13](../src/main/resources/db/migration/V13__agent_loop_budget.sql)只扩展已存在的两张运行表，沿用 V12 已授予的表级 `SELECT / INSERT / UPDATE`，不需要新表授权。旧运行保留版本 1，新运行显式写入版本 2，旧迁移文件不修改。

回归覆盖：

- `DeepSeekAgentDecisionPlannerTest`：工具消息关联、只读白名单、严格 JSON、usage 校验、请求大小及脱敏。
- `AgentBudgetTest`：请求前预留、精确十进制估算、独立 Token / 费用上限和零费用规则路径。
- `AgentLoopPersistenceIT`：真实 MySQL 中的多轮观察反馈、分支、预算累积、重复工具、伪造订单、超额度、迟到结果、重复结算及 V12 升级恢复。
- `AgentRuntimeApiIT`：原聊天协议、进度、显式恢复、跨店跨买家隔离和最终消息去重。

所有自动化模型响应使用本机模拟器或规则决策器，不需要真实 Key，也不把日常业务库用作故障测试库。当前仍不包含通用写工具恢复、真实支付、任意工具执行或供应商账户级预算控制；V14 的熔断与重试边界见上方说明。

2026-10-27 验证结果：完整后端回归通过 463 个单元测试和 285 个集成测试；随后补强观察结果回传模型前的当前归属检查，新增一例撤销归属测试，并复测相关 22 个集成测试，全部通过。真实 Java / MySQL 浏览器验证通过知识与跨店物流隔离、会话重连、CSRF 与本人订单选择三个用例。待发货订单直接返回订单事实，不再进入物流查询。

本地库与 Docker 实验库均已升级到 V13。实验环境 15 项 HTTP 检查通过，包括五步运行记录、重复提交与已完成运行恢复不增加执行步骤；该检查创建一轮实验会话，业务写入 0 次、付费模型调用 0 次。日常实例只做只读健康、知识与授权检查，没有用真实 Key 进行付费调用验收。
