# 售后审批后的模拟退款执行

本轮完成“申请 → 人工批准 → 持久化任务 → 模拟退款 → 买家查询结果”。审批结果与执行结果分开保存：`APPROVED` 表示人工批准，`SUCCEEDED` 表示本项目模拟处理完成。

## 数据如何流动

```text
买家提交申请 → AfterSaleService.submit → PENDING_REVIEW
客服/管理员批准 → AfterSaleService.decide
  → 同一事务：APPROVED + 审核审计 + PENDING执行任务 + 排队审计
  → AfterSaleExecutionScheduling 定时轮询
  → AfterSaleExecutionWorker → Processor.claimNext
  → PROCESSING + 第N次尝试 + 30秒租约 + 独立领取凭证
  → Processor.complete 再查审批、订单归属、状态、金额和币种
  → SimulatedRefundGateway 写入一份模拟退款回执
  → 同一事务：回执 + SUCCEEDED + 成功审计
  → 买家“售后进度”面板 / 聊天“我的售后处理到哪了”
```

`AfterSaleExecutionProcessor` 是事务边界，由独立的 Worker Bean 调用，避免同类内部调用绕过 Spring 事务代理。`AfterSaleExecutionRepository` 保存任务；`SimulatedRefundGateway` 实现 `RefundGateway` 接口，在已有事务内写回执。前端只消费公开进度 DTO，不传买家身份、处理金额、任务领取凭证或支付参数。

## 状态与重试

| 执行状态 | 含义 |
| --- | --- |
| PENDING | 已保存任务，等待后台领取 |
| PROCESSING | 工作线程持有有效领取凭证与租约 |
| SUCCEEDED | 模拟回执、执行结果与成功审计已提交 |
| FAILED | 上次执行失败；根据错误类别与剩余次数决定能否重试 |

临时失败按退避计划自动重试，间隔依次为2、4、8、16秒；管理员也可以请求提前排队。自动与手工重试共用最多5次尝试预算，手工操作不会清零次数。当前订单或审批条件不满足时停止自动重试。达到上限或永久业务失败后，前端显示原因并提示人工核查。

所有领取、租约和退避时间以数据库 UTC 时间为准。任务领取使用 `FOR UPDATE SKIP LOCKED`，让并发工作线程领取不同任务。服务退出后，已领取任务的租约过期即可由新线程接续；旧线程持有的过期 token 不能再提交成功或失败。最后一次尝试的租约过期时，任务进入不可重试的失败状态。

模拟回执以售后单 UUID 作为主键，只有一份。重复读取同一回执时必须核对金额与币种。当前模拟网关、任务完成和成功审计共用同一个 MySQL 事务：审计失败时回执与成功状态一起回滚。这个保证适用于本项目数据库模拟器；未来接外部支付时，需要支付方支持同一幂等键、退款查询与对账，不能把本地事务直接等同于外部支付的恰好一次执行。

## HTTP 与界面

- 买家：`GET /api/v1/shops/{shopId}/after-sales`，仅当前买家和店铺，最近50条。
- 管理员待审批：`GET /api/v1/reviews/after-sales`，保留原协议。
- 管理员审批/处理记录：`GET /api/v1/reviews/after-sales/history`，最近50条。
- 提前重试：`POST /api/v1/reviews/after-sales/{id}/execution/retry`，无须请求体；需要审核权限和 CSRF，禁止申请人本人操作。

`AfterSaleTicket.execution` 为公开进度对象，包含状态、金额、币种、尝试次数、下次执行时间、完成时间、模拟凭证和安全失败说明；不暴露内部订单/买家 ID 或领取 token。

前端顶部“售后进度”查看买家记录；“人工审核 → 处理进度”查看管理员记录并显式重试。失败的 POST 不会自动重放。聊天支持“我的售后”“查看售后进度”“我的售后处理到哪了”。各入口读同一份持久化结果。

## 在本机启用

1. 新增迁移 `V5__after_sale_execution.sql`，创建 `support_after_sale_execution` 和 `support_simulated_refund`；既有 V1–V4 保持原样。用已有迁移账号在应用启动时执行。
2. 数据库管理员在 V5 建表后，核对并执行 [增量权限脚本](../config/assistant-runtime-grants.sql)。运行账号需要任务表 INSERT/UPDATE、模拟回执表 INSERT；保留现有 SELECT 与售后/审计权限。回执表无需 UPDATE/DELETE，订单无需写权限。
3. 后端 `local` profile 已开启 `supportflow.after-sale.execution.enabled=true`，默认每2秒轮询、每批最多10笔。通用配置及隔离测试默认关闭定时调度，测试可以手动调用 Worker；浏览器临时后端单独开启。
4. 更新权限后重启后端。Vite 开发模式会热更新前端；若使用构建产物，重新构建部署。

本轮代码验证使用一次性 MySQL，不会自动给日常数据库授权或启动迁移。旧版本已审批但未创建执行任务的记录保持 `execution=null`，不会在升级时自动补执行。

## 验证

根目录 `mvn -o verify` 验证已有功能及新增 `AfterSaleExecutionIT`。新增18项集成用例覆盖审批和任务原子性、两个 Worker 并发只有一份回执、租约接续与旧 token、金额精度、临时失败退避、累计5次预算、手工重试权限/CSRF/禁止本人重试，以及任务、回执、审计失败回滚。

前端运行 `npm.cmd run check`、`npm.cmd run build`。浏览器验收使用 [前端说明](../frontend/README.md) 中的独立 `FrontendTestServer`，模拟批准后由真实 Worker 执行，管理员与买家页面读取同一模拟凭证；不使用你的日常业务实例。

2026-10-21 最终验收：后端174项单元测试、133项集成测试全部通过（`mvn -o verify` 构建成功）；前端91项单元测试、类型检查与生产构建通过；真实 Java/MySQL 浏览器7项及独立模拟接口浏览器18项通过。新增浏览器闭环验证管理员和买家显示相同的 `SIM-` 凭证、聊天读取同一结果，以及手机布局和跨店隔离。
