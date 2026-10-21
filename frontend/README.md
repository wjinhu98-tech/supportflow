# SupportFlow 前端

Vue 3 + TypeScript + Vite 的店铺客服工作台，支持订单咨询、知识检索、学习版物流、售后人工审批与模拟退款进度。与根目录的 Spring Boot 后端配套运行；默认访问真实后端，不内置订单、不偷偷切换到 mock 数据。

## 本地启动

环境：Node.js 22.12+（本次使用 24.15）、npm。**首次启用本轮售后功能，先看[迁移、最小写权限与审核账号](../docs/assistant-architecture.md#首次在你的本地业务库启用前)**。旧应用账号只有 SELECT，不能直接完成售后写入；本轮没有替你修改日常业务库或真实账号。

用 IDEA 启动根项目 `SupportFlowApplication`，保持已有 `local` profile 与数据源/迁移账号配置。后端端口默认为 8080；启动时按原有 Flyway 配置执行尚未应用的迁移（模拟退款新增 V5）。启用执行任务前，需要按架构说明补齐应用账号对执行记录和模拟渠道记录的最小权限。

在 PowerShell 中另开终端：

```powershell
cd D:\project\supportflow-rebuild\frontend
npm.cmd ci
npm.cmd run dev
```

浏览器打开 <http://127.0.0.1:5173/?shopId=100>。点击「连接账号」，输入**后端已配置的本地安全账号和原始登录密码**，不是数据库密码、密码哈希或 DeepSeek API Key。本工程不会替你创建用户或向业务库写订单。无订单时会显示空状态。

浏览器地址中的 `shopId` 是当前店铺上下文。接入真正的店铺页面后，应由店铺入口构造这个链接；工作台里的「切换店铺」是现阶段的开发入口，不是要求用户在聊天中告诉模型店铺 ID。当前后端没有店铺目录/商品目录接口，前端不会编造店铺名称、商品、销量或物流轨迹。

默认 `/api` 会由 Vite 代理到 `http://127.0.0.1:8080`。如果后端端口不同，在本目录创建被忽略的 `.env.local`，只填服务器代理地址：

```dotenv
API_PROXY_TARGET=http://127.0.0.1:8080
```

修改后重启 Vite。也可以使用 `$env:API_PROXY_TARGET='http://127.0.0.1:8080'` 后启动。不要在任何 `VITE_*` 变量、前端源码或浏览器存储中放模型 Key 或密码。真实 DeepSeek Key 仍只由后端读取 `.local/deepseek-secrets.yml`。

## 已实现的操作

- 当前店铺本人订单：自动加载、刷新、游标翻页、状态/金额/时间展示。
- 选择订单并咨询；后续消息携带所选 `orderNo`，切店或取消选择后移除。
- 自然语言提问、明确查单指令、回复中的可选订单卡片、订单详情。
- 「服务规则」返回版本化文档及来源；「查物流」返回本项目实际录入的物流事件，无事件不会编造轨迹。
- 「申请售后」只预填消息，补原因发送后创建待审核申请；「我的售后」读取持久化结果。
- 独立客服/管理员在「人工审核」中批准或拒绝，强制备注、权限校验、禁止自审和重复审批。
- 买家点击顶部「售后进度」，读取本人当前店铺的申请、审批意见、模拟处理金额、进度及凭证；点击「刷新进度」读取最新结果。
- 管理员在「人工审核 → 处理进度」中查看最近 50 条审批记录及执行结果；仅对后端标记可重试的失败任务提供「重试模拟退款」，点击后显式提交一次。
- 连接/断开开发账号，密码只保留在内存；刷新后需重新连接。
- 错误提示、加载状态、重复发送防护、切换店铺/断开后的迟到响应隔离。
- 桌面与移动端布局、键盘操作、中文输入法 Enter 防误发。

固定指令「我的订单」「查订单 SF10001」「咨询此订单」不依赖模型。自然语言查单需后端启用 DeepSeek；新增物流/售后在关闭模型时可使用受限规则规划器。知识检索是本地文档检索与引用式抽取，不是任意知识问答。物流未接真实快递接口；审批通过不等于已退款。当前不支持流式聊天，对话只保留在页面内存中，售后与审计持久化到 MySQL。

## 接口与安全边界

1. 用户点击连接，`ApiClient` 使用 UTF-8 HTTP Basic 请求 `GET /api/v1/security/csrf`。
2. 浏览器保存后端的 HttpOnly 会话 Cookie；API 层只在内存中保存 Basic 凭证。当前后端认证可能轮换 CSRF token，因此每次 POST 前重新获取，不长期缓存。
3. 加载订单通过 `POST /api/v1/shops/{shopId}/chat/messages` 发送 `{"message":"我的订单"}`，继续翻页发送 `我的订单 <原样游标>`。
4. 咨询通过同一路径发送 `{"message":"咨询此订单","orderNo":"SF10001"}`，同时发送认证头、CSRF 请求头及同源 Cookie。
5. 页面按响应的 `TEXT / ORDER_DETAILS / ORDER_SELECTION` 展示文本或订单卡片。买家身份和订单归属始终由后端验证，前端不传 `buyerId/customerId`。
6. 审核页 GET `/api/v1/reviews/after-sales` 读取最多50条待审，POST `/api/v1/reviews/after-sales/{uuid}/decision` 提交人工决定，同样保留 Basic、Cookie 与新 CSRF；不会自动重试决定。
7. 买家进度读取 GET `/api/v1/shops/{shopId}/after-sales`；管理员进度读取 GET `/api/v1/reviews/after-sales/history`。金额来自后端公开 DTO，前端不能修改退款金额。
8. 管理员手动重试使用 POST `/api/v1/reviews/after-sales/{uuid}/execution/retry`，不提交金额，保留权限与 CSRF 校验，并与聊天、审核读写共用请求队列。浏览器不会自动重放该请求。

所有请求使用同源 `/api`，开发代理保留 Cookie、认证与 CSRF，不关闭安全过滤器，不添加通配 CORS。API 层串行执行「获取新 Token → POST」请求对，避免订单列表和聊天并发造成会话轮换竞态。403 可能是权限不足，也可能是 CSRF 失效；前端不会盲目自动重放 POST。401 会清空账号、订单、选中项和聊天显示。

这是**本地开发联调的认证界面**，不是生产登录系统。尚无注册、登录令牌、找回密码或权限管理页面。生产部署应使用 HTTPS 和正式认证方案，并让反向代理将 `/api` 转发给后端；`dist` 是静态资源，Vite 开发代理不会自动随它部署。不要把携带 Basic 凭证的开发页面暴露到公网。

当前 JSON 数值 ID/游标只接受 JavaScript 可精确表示的安全整数。若未来数据库 ID 可能超过 `Number.MAX_SAFE_INTEGER`，应统一把后端这些 DTO 字段改为十进制字符串，再同步前端协议，不能悄悄四舍五入。

## 代码沿什么顺序看

```text
点击「咨询此订单」
  → components/OrderCard.vue 发出 select 事件
  → App.vue 调用 selectOrder(order)
  → composables/useSupportDesk.ts 更新选中订单，构造消息
  → api/client.ts 附加 Basic + CSRF + Cookie，发起 HTTP
  → Java ChatController → ConversationOrchestrator / Router
      ├─ 知识问题 → 本地知识检索 → 带版本与来源的答复
      └─ Agent → 订单 / 物流 / 售后工具 → Java 归属与业务校验 → MySQL
  → ChatResponse JSON
  → useSupportDesk 更新 messages/selectedOrder
  → Vue 自动更新聊天气泡和订单详情
```

`types.ts` 对应公开 DTO；`api/client.ts` 只管网络和协议；`useSupportDesk.ts` 组织页面状态和操作；组件只展示和响应用户事件。取消旧请求和检查上下文用于避免「旧店铺的结果跑到新店铺」。没有为单页工作台额外引入全局状态框架。

售后写入后停在 `PENDING_REVIEW`，模型不能自行批准。`ReviewPanel.vue` 展示待审数据，由独立客服/管理员显式提交决定；Java 再次校验后写状态与审计，并为新批准的申请创建持久化执行任务。后台执行学习版模拟退款，临时失败按退避时间重试，总执行次数最多 5 次。

`AfterSalesPanel.vue` 展示买家售后记录；`ExecutionProgress.vue` 复用在买家和管理员视图，分别显示审批结果与执行状态。历史已批准但未创建任务的申请不会自动补执行。页面通过显式刷新获取最新结果；买家也可重新发送「我的售后」查询，不会自动推送到旧聊天。所有“成功”和凭证均指模拟处理，不代表真实资金到账。完整边界与类职责见[架构说明](../docs/assistant-architecture.md)。

## 检查与构建

```powershell
npm.cmd run check      # TypeScript + API/状态流单元测试
npm.cmd run build      # TypeScript + 生产打包，输出 dist/
npm.cmd run test:e2e   # 真实浏览器 + 独立 HTTP mock，仅公开假数据
```

浏览器测试默认使用本机 Microsoft Edge，不读取你的浏览器资料或登录态。其他环境可设置 `$env:PLAYWRIGHT_CHANNEL='chromium'` 并先安装 Playwright Chromium。测试会独占 5173 和 mock 18080；请先停止手动启动的 Vite。mock 只由测试命令启动，不是正常开发模式的后备数据。

真实 Java 后端浏览器验证使用 `src/test/java/dev/supportflow/frontend/FrontendTestServer.java`：它只加载测试配置，在一次性 Testcontainers MySQL 中执行 V1–V5 并创建公开假买家/审核账号；强制关闭 DeepSeek。普通应用运行不会加载这个类。测试覆盖买家申请、权限拦截、独立管理员批准/拒绝、管理员和买家查看同一模拟执行凭证，以及知识与跨店物流隔离。

从仓库根目录先执行 `mvn -o verify`（首次依赖未缓存可去掉 `-o`），生成测试类和报告。然后在同一根目录打开单独的 PowerShell 终端：

```powershell
$report = [xml](Get-Content -Raw target\surefire-reports\TEST-dev.supportflow.chat.infrastructure.deepseek.DeepSeekOrderIntentRecognizerTest.xml)
$testClasspath = ($report.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' } | Select-Object -First 1).value
& 'D:\develop\jdk\bin\java.exe' -cp $testClasspath dev.supportflow.frontend.FrontendTestServer
```

JDK 路径按本机安装调整，或用 PATH 中的 `java`。等待 `FRONTEND_TEST_BACKEND_READY` 后，在前端目录另开终端：

```powershell
$env:E2E_BACKEND_URL='http://127.0.0.1:18081'
npm.cmd run test:e2e
Remove-Item Env:E2E_BACKEND_URL
```

测试使用 launcher 中公开的假账号，不使用你的日常账号。完成后 Ctrl+C 停止测试后端，临时容器随其关闭。不要将 `E2E_BACKEND_URL` 指向日常业务实例。

参考：[Vue TypeScript](https://vuejs.org/guide/typescript/overview)、[Vite](https://vite.dev/guide/)、[Playwright Web Server](https://playwright.dev/docs/test-webserver)。
