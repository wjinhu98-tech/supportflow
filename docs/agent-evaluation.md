# Agent 固定场景评测

这组评测验证客服链路是否按标注选用能力、返回真实测试数据、隔离店铺与买家，并保留人工审核。版本为 `agent-scenarios-2026-10-28-v1`，共有 47 个手工合成场景。它们是可重复的工程回归集，不是线上用户样本或真实 DeepSeek 准确率测试。

## 实际执行了什么

每个场景先在 Testcontainers 的临时 MySQL 建立独立店铺、订单和知识条目，然后通过带真实 Spring Security 过滤器的 MockMvc 请求发送消息：

```text
固定问题 + 临时账号 + 当前店铺
  → Spring Security / CSRF
  → 会话与受控上下文
  → Router / Orchestrator
  → 订单、知识、物流或售后服务
  → Java 权限、归属、期限与审批校验
  → MySQL 结果 / Agent Checkpoint
  → 独立标签评分 → JSON + Markdown 报告 → 回归门槛
```

认证、路由、SQL、工具、会话与 Checkpoint 使用项目真实实现。订单表仅授予运行账号 SELECT，售后、会话与运行表使用显式表权限；建库与数据装载由临时容器管理员完成。每个样本有自己的店铺范围，不依靠样本执行顺序共享退款或会话状态。

普通物流使用 `RuleBasedAgentDecisionPlanner`；故障场景只替换规划器返回值，或在物流边界注入一次超时，继续执行真实 Java 校验及数据库状态转换。评测强制关闭 DeepSeek，并检查网络执行器没有装配。不读取 `.local` 或日常配置，不访问 8080、5173、18090 等日常服务，不修改 `supportflow_rebuild`，不需要追加数据库授权。

离线是指不调用外部模型或日常业务服务。首次运行 Maven 依赖和 `mysql:8.0.45` / Testcontainers 镜像仍需下载；完整场景执行需要 Docker Desktop。只有数据与评分器的契约测试不需要 Docker。

## 场景与独立标签

| 分类           | 覆盖的行为                                                                                     |
| -------------- | ---------------------------------------------------------------------------------------------- |
| ORDER          | 本人订单、不同状态与金额、订单列表，防止同名 FAQ 替代查单                                      |
| LOGISTICS      | 显式订单或已选订单、已发货、未发货不查物流、无录入事件不编造轨迹                               |
| KNOWLEDGE      | 当前店铺发布快照、另一店铺隔离、受控退款政策、无依据、草稿与带指令的资料过滤                   |
| CLARIFICATION  | 未选订单、多订单、没有上文的代词、规划器主动澄清                                               |
| SECURITY       | 跨店铺、其他买家、消息中的身份声明、商家账号、未认证、缺权限、CSRF，以及模型伪造参数和非法步骤 |
| HUMAN_APPROVAL | 打开原因表单、否定申请、期限内与过期、缺收货时间转审核、申请保持待审核、查询售后记录           |
| RESILIENCE     | 一次超时后成功、临时故障耗尽一次重试、无效响应与熔断状态不重试、usage 超出上限、只重试物流读取 |

[`scenarios.json`](../src/test/resources/agent-evaluation/scenarios.json) 存放问题、角色、逻辑店铺、已选订单、规划器夹具及独立期望。100/101 是样本中的逻辑店铺，运行时映射为该样本专用的两家临时店铺；不是日常演示店铺。身份来自测试认证，消息声称“我是买家201”不改变认证身份。

每条期望明确指定 HTTP 状态、响应类型、能力集合、必含/禁止文字、运行状态、步骤顺序、规划器调用次数、新增售后单数与资格快照。异常和校验拒绝也有自己的正确结果；返回 404/503 并不自动算成功。

[`baseline.json`](../src/test/resources/agent-evaluation/baseline.json) 冻结版本、规范化换行后的 SHA-256、全部必备场景 ID 和通过门槛。删除失败样本、改标签或混入另一版本都会失败。新增或修改标签时应解释业务规则、更新版本和基线并经过评审；不要仅依据当前输出修改答案。本轮初始标注中的“安全拒绝可恢复”已按现有契约修正为终止的 `FAILED`，攻击问题也明确指向需要验证的订单入口；没有删减失败场景。

## 一键运行

在仓库根目录，准备 Java 21、Maven、Node 20+ 和 Docker Desktop：

```powershell
.\scripts\run-agent-evaluation.ps1 -Offline
```

脚本先验证报告比较工具，再编译、执行评分器契约测试和 47 个场景。`-Offline` 仅让 Maven 使用已缓存依赖；首次下载依赖时省略它。Maven 不在 PATH 时可传 `-MavenCommand 'D:\develop\apache-maven-3.9.14\bin\mvn.cmd'`。命令不运行 `clean`、打包或重启服务，不覆盖正在运行的应用 JAR。

跨平台等价命令如下：

```text
node --test scripts/compare-agent-evaluations.test.mjs
mvn -B -ntp -Dtest=AgentEvaluationContractTest -Dit.test=AgentScenarioEvaluationIT test-compile surefire:test failsafe:integration-test failsafe:verify
```

第一行验证比较逻辑；第二行在临时 MySQL 执行完整场景。`AgentScenarioEvaluationIT` 在一个 JUnit 测试中遍历 47 条数据，所以 Maven 可能显示 `Tests run: 1`。真正的场景数量和通过数看 `AGENT_EVAL ... cases=47 passed=47` 及逐条报告。

仅检查数据集与评分器时运行 `mvn -o "-Dtest=AgentEvaluationContractTest" test`，这不代表 47 个业务场景已执行。

## 报告与指标

输出在被 Git 忽略的 `target/agent-evaluation/`。每轮有独立时间戳文件，另有 `latest.json` 和 `latest.md`；业务断言失败前也会写入逐条报告。编译或容器启动失败时可能没有新报告，应同时检查命令退出码与报告时间，不能把上次的文件当成本轮结果。

报告包含数据集版本与指纹、应用及评测器源码指纹、CI 提交号或 `working-tree`、模式、Java/OS、逐条结果和失败检查编号。不会保存请求头、密码、Key、完整回复或异常消息。配置外的真实凭据不会进入报告。

| 指标                 | 计算依据与边界                                                                                                                             |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| 完整场景符合预期     | 全部断言都通过的场景 / 全部场景；包含正常与预期拒绝，不单独称为用户任务成功率                                                              |
| 调用能力集合符合标注 | 实际观察到的能力集合与手工标签完全一致的场景 / 全部场景；不是模型意图识别准确率                                                            |
| 安全探针符合预期     | 安全样本的状态、调用、数据隔离、无意外写入等所有断言通过 / 安全样本数                                                                      |
| 来源一致性           | 来源 ID、版本、标题、内容与当前店铺发布快照或受控内置原文一致的引用数 / 引用数；不是开放式自然语言正确率                                   |
| P50 / P95            | 单线程 MockMvc POST，含认证、Java 和临时 MySQL；排除初始化、数据装载与报告生成。没有真实网络/模型时间，不作生产容量结论，不设硬性时间门槛  |
| Token / 费用         | 普通规则场景 usage 为 0；故障夹具手工返回 usage，记录实际持久化的 reported、charged 与配置费率估算。非零值都是模拟记账，不是 DeepSeek 账单 |
| 重试次数             | 真实持久化步骤中，同一 operation ID 的额外已启动尝试；不把没有开始的预定重试计入                                                           |

能力在订单仓储读取、知识发布快照读取、物流工具与售后工具边界记录，包含被拒绝的尝试和重复的归属核验；`capabilities` 是去重集合，`callTrace` 保留完整顺序。它不能替代模型每轮动作的统计。持久化 Agent 的真实动作另由 `run.phases`、`plannerCalls` 和 `retryAttempts` 验证。

所有场景同时检查订单字段未改变，售后单保持 `PENDING_REVIEW`，没有自动创建执行任务或模拟退款，预算未超限，响应不泄漏其他订单或内部字段。分母为 0 的指标显示 `N/A`，不会报告 100%。固定集要求 100% 是工程门槛，不是对未知问题的泛化保证。

## 两次运行如何比较

保留第一轮时间戳 JSON，再运行改动后的代码，用比较脚本读取两份报告：

```powershell
node scripts/compare-agent-evaluations.mjs target/agent-evaluation/evaluation-第一轮.json target/agent-evaluation/latest.json
```

把示例中的第一轮文件名替换为真实文件名。脚本重新计算逐条计数，要求同一模式、同一数据集指纹、同一场景 ID 与分类；比较通过情况、能力选择、安全检查、耗时和模拟 usage。退出码 0 为通过，1 为当前失败或新增退化，2 为不可比较/格式错误。不同 Java/OS 的耗时会注明不可直接比较；速度变化不能抵消正确性失败。

真实 DeepSeek 效果需要独立的、明确启用的评测模式、未参与调参的验收集以及预先设定的费用和调用上限。本轮入口只提供上述离线模式，不接收模型开关或自定义服务地址，也没有进行付费模型调用。

## 自动回归与本轮发现

[`agent-evaluation.yml`](../.github/workflows/agent-evaluation.yml) 在相关代码提交、PR 或手动触发时执行离线评测，失败保留报告，并将 Markdown 写入 Actions 任务摘要。只声明仓库读取权限，不配置模型或本地数据库凭据。首次在 GitHub 的实际执行结果应以该次 Actions 检查为准。

工作流动作按官方文档配置：[checkout](https://github.com/actions/checkout)、[setup-java](https://github.com/actions/setup-java)、[upload-artifact](https://github.com/actions/upload-artifact)。

本轮实际发现：受控上下文最后一次 FAQ 尝试未排除“查订单”，同名商家资料可能把订单列表替换为知识回复。现在订单主题保留业务路由；单元回归和完整 HTTP 场景中都有同名 FAQ 陷阱验证。无需新数据库迁移或授权。
