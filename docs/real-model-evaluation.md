# 可选的真实模型评测

现有 `AgentScenarioEvaluationIT` 验证真实 Java 接口、权限和数据库行为，模型由确定性替身提供。本工具补充 DeepSeek **规划器契约评测**：用真实模型检查工具选择、订单参数和严格响应解析。它不代表完整 Agent、多轮业务链路、知识问答或生产流量的准确率。

默认不调用模型。普通 Maven 单元测试、集成测试、应用启动、部署和没有参数的本工具都不会触发付费评测。仓库没有预先填好的“真实模型通过率”；需要实际执行后查看新报告。

## 输入和评判

`RealModelEvaluationTool.fixtures()` 内置 8 个合成场景：已选订单、明确订单号、缺少订单、待发货订单结束、已发货订单查物流、已有物流结果结束、问题中的指令注入、工具观察中的指令注入。固定的期望结果独立于模型回答。

工具只发送这些合成问题和合成订单/物流观察；不启动 Spring、不连接数据库、不读取 `.local/secrets.yml`、不加载商家知识文件、不执行订单、物流、售后工具。请求复用正式 `DeepSeekAgentDecisionPlanner`、严格解析器和 `DeepSeekCallExecutor`，目标地址固定为正式适配器的官方端点。

一个场景通过，需要模型动作和订单参数同时符合期望。格式错误、越界 usage、超时、熔断或模型不可用计为错误；预算、调用次数、总期限耗尽的未执行场景标记 `SKIPPED`。这些分母、错误和跳过都在报告中保留，不能把跳过当作通过。

## 手动执行

先完成正常打包。下面的命令使用打包后的 Boot 类加载器，仅运行评测入口：

```powershell
# DEEPSEEK_API_KEY 从你自己的本机进程环境注入，不要写进命令历史或 Git。
# DEEPSEEK_MODEL 应与拟评测的已配置模型一致。
# 以下两项填写你核对过的每百万输入/输出 token 美元估算单价；工具不查询价格。
$env:SUPPORTFLOW_EVAL_INPUT_USD_PER_MILLION = '<输入估算单价>'
$env:SUPPORTFLOW_EVAL_OUTPUT_USD_PER_MILLION = '<输出估算单价>'

java '-Dloader.main=dev.supportflow.assistant.evaluation.RealModelEvaluationTool' `
  -cp target\supportflow-backend-0.1.0-SNAPSHOT.jar `
  org.springframework.boot.loader.launch.PropertiesLauncher `
  --allow-remote --max-calls=8 --max-tokens=70000 --max-cost-usd=0.05 --deadline-seconds=60
```

`--allow-remote` 是本次执行的显式开关；环境变量必须包含 `DEEPSEEK_API_KEY` 以及两个正数估算单价。密钥不接受命令行参数。缺少单价、无效单价、重复参数或越界预算都会在创建 HTTP 客户端前拒绝执行。

硬上限：最多 12 次规划尝试、100000 个累计保留 token、1 美元估算预算、60 秒模型评测期限；默认分别为 8、70000、0.05、60。每次输入上限 8192、输出上限 256、HTTP 超时最多 8 秒，并使用剩余总期限进一步缩短。没有自动重试。

每次尝试前按输入/输出上限保留 token 和估算金额；成功且 usage 合法时结算为报告的 usage，未知或失败结果保留全部预留量。实际 HTTP 请求数不会超过规划尝试数；熔断拒绝也可能占用保守预留量。此金额是**按所填单价估算的调用边界**，不承诺供应商最终账单；应先核对该模型的计费规则。

独立 CLI 在当前线程临时安装合成测试身份，仍经过正式的用户模型配额，结束后恢复之前的安全上下文。业务接口不会因此获得匿名调用模型的通道。

## 报告和复现

报告以新 UUID 文件名写入 `target/real-model-evaluation/`，不覆盖既有结果。包含模型、合成集版本和 SHA-256、动作/参数是否匹配、固定错误分类、延迟、usage、保守记账 token 和估算费用，不记录问题正文、订单号、模型原文、凭据或密钥。

退出码：`0` 为全部通过或未开启；`1` 为已运行场景出现失败；`2` 为启动/配置/报告写入失败；`3` 为没有失败但存在预算或期限跳过。判断是否真的运行，须同时看 `attemptedCalls`、`passed`、`failed`、`skipped` 和 `completionReason`。

`RealModelEvaluationToolTest` 使用假 HTTP 响应验证开关、硬上限、保守记账、期限、隐私和身份恢复。测试结果只能证明评测工具行为，不能充当真实 DeepSeek 的评测成绩。
