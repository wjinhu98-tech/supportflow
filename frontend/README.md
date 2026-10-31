# SupportFlow 前端

Vue 3 + TypeScript + Vite 的店铺客服工作台，支持订单咨询、知识检索、人工客服接待、学习版物流、售后人工审批与模拟退款进度。与根目录的 Spring Boot 后端配套运行；默认访问真实后端，不内置订单、不偷偷切换到 mock 数据。

## 本地启动

环境：Node.js 22.12+（本次使用 24.15）、npm。**新环境先看[迁移、最小写权限与审核账号](../docs/assistant-architecture.md#首次在你的本地业务库启用前)**，迁移数据库后由管理员授予最小运行权限。账号继续使用已有私密配置；不要把测试账号复制到日常配置。

用 IDEA 启动根项目 `SupportFlowApplication`，保持已有 `local` profile 与数据源/迁移账号配置。后端端口默认为 8080；启动时按原有 Flyway 配置执行尚未应用的迁移（模拟退款使用 V5，收货时间与售后期限核验记录使用 V6，确认收货审计使用 V7）。启用执行任务前，需要按架构说明补齐应用账号对执行记录和模拟渠道记录的最小权限。启用确认收货前，还需按[确认收货说明](../docs/order-receipt-confirmation.md)配置运行时数据库权限和账号权限。

聊天记录采用[每店一个固定窗口](../docs/shop-chat-windows.md)（V15）：连接账号、刷新或切回店铺都会恢复当前买家在该店铺的记录，并汇总以前分散的咨询。页面不再提供“新会话”，右上角可主动删除当前店铺对话，确认后才提交；订单、售后和其他店铺记录不变。历史咨询工具栏继续隐藏，较早消息仍可向上加载。凭证不持久保存，刷新后需要重新连接。原会话协议和快照机制见[会话说明](../docs/conversation-history.md)，已有会话表授权可继续使用。

V9起用户与商家采用独立入口：用户端仍是 `/?shopId=100`，商家管理端为 `/merchant`。商家账号必须配置 `MERCHANT_STAFF` 类型并有数据库员工和店铺授权；仅拥有审核权限不代表能查看全平台申请。没有授权时不会自动展示店铺。部署配置和最小权限见[商家管理端说明](../docs/merchant-workspace.md)。

V10/V11增加店铺知识管理。在商家端选择“知识库”，可保存FAQ草稿、发布、下线并查看历史快照与操作记录。员工须有独立 `KNOWLEDGE_MANAGE` 权限；知识专职账号不会调用售后接口。修改已发布文章先保留旧发布内容，重新发布才改变买家回答。遇到版本冲突或网络结果不明时，重新读取只更新服务器版本并保留本地输入，确认后再显式保存，不自动重放写操作。详见[知识管理说明](../docs/shop-knowledge-management.md)。

知识回答使用结构化 `knowledge` 区分有依据、无依据和资料不可用。用户端只展示回答正文，不展示来源卡片、资料标题、版本或原文展开入口；来源编号、版本和完整内容快照仍随响应保留，并保存到聊天记录的 `response_json`，用于审计与回溯。回复不拼接来源元信息，保留必要业务限定；长文本不再依赖“展开来源”补充条件，规范公开参考资料使用已核验摘要。旧历史没有 `knowledge` 时仍可阅读；细节与可复现评测见[知识质量说明](../docs/knowledge-quality-and-interview.md)，后续本地语义检索见[检索说明](../docs/semantic-rag.md)。

业务无关问题统一回复“对不起，我无法回复您的问题。”。明确的回答机制、天气、编程等无关问题在引用旧订单或资料前拦截；普通问候、商品咨询、缺少订单信息的追问仍走原流程。知识无匹配、资料读取故障不等同于业务无关，继续使用各自提示。历史消息原文不改写，新消息按当前规则处理。

服务端仍返回有效期 30 分钟的受控话题引用，但用户端不再展示话题提示条、来源数量或“话题清除待发送”说明。内部引用只帮助解析受限追问，不代表订单现状、资料仍有效或操作权限。历史恢复不自动选择历史订单或打开退款表单。新追问由后端重新核验当前买家与店铺、读取实时订单或当前发布资料，旧来源原文保留在历史快照中用于审计，不在用户端展开。实现、调用链和面试取舍见[受控话题说明](../docs/conversation-context.md)。本次复用已有会话响应 JSON，不需要新增迁移或授权。

取消选中订单不会立即向服务器发送请求，而是在下一条消息中携带 `resetContext=true`；只有该轮成功保存后才在服务器生效，失败或结果不明时保留内部待发送标记。页面不显示清除待发送提示，也不提前声称服务端已清除。发送前刷新页面、切换会话再返回，服务器仍可能保留旧话题。账户、店铺和新会话不会共用这一标记。过期话题不继续自动携带旧选单，用户主动重选订单仍会重新查询。

在 PowerShell 中另开终端：

```powershell
cd D:\project\supportflow-rebuild\frontend
npm.cmd ci
npm.cmd run dev
```

浏览器打开 <http://127.0.0.1:5173/?shopId=100>。点击「连接账号」，输入**后端已配置的本地安全账号和原始登录密码**，不是数据库密码、密码哈希或 DeepSeek API Key。本工程不会替你创建用户或向业务库写订单。无订单时会显示空状态。

浏览器地址中的 `shopId` 是当前店铺上下文。接入真正的店铺页面后，应由店铺入口构造这个链接；工作台里的「切换店铺」是现阶段的开发入口，不是要求用户在聊天中告诉模型店铺 ID。当前没有面向买家的公开店铺/商品目录接口；商家端的授权店铺元数据来自数据库，前端不会编造店铺名称、商品、销量或物流轨迹。

默认 `/api` 会由 Vite 代理到 `http://127.0.0.1:8080`。如果后端端口不同，在本目录创建被忽略的 `.env.local`，只填服务器代理地址：

```dotenv
API_PROXY_TARGET=http://127.0.0.1:8080
```

修改后重启 Vite。也可以使用 `$env:API_PROXY_TARGET='http://127.0.0.1:8080'` 后启动。不要在任何 `VITE_*` 变量、前端源码或浏览器存储中放模型 Key 或密码。真实 DeepSeek Key 仍只由后端读取 `.local/deepseek-secrets.yml`。

## 已实现的操作

买家点击「联系人工客服」才会进入等待接待；等待和接待期间，输入框只发送人工消息。商家须有独立 `CHAT_SUPPORT` 权限且获得当前店铺授权，才会显示「人工接待」模块；只有成功接待本次咨询的客服可以回复和结束服务。知识管理、售后审核权限不会自动授予接待权限。取消等待或结束人工服务后恢复智能客服，并清除转人工前的订单选择与话题引用。消息均按纯文本显示，人工沟通不能直接触发退款等业务动作。迁移、最小数据库权限与接口见[转人工客服说明](../docs/human-support.md)。

人工记录与智能回复按时间共同展示；重新连接或切回店铺会读取该店铺记录，较早人工消息单独分页加载，刷新不会丢弃已加载的历史。可见页面每 4 秒读取等待队列或当前人工咨询，隐藏页面、离开对应模块、换店、换号和退出时停止轮询或丢弃旧结果；已结束的咨询停止轮询。写操作只由用户显式触发，超时后保留待核实消息及原消息编号，刷新只读取记录，手动重试复用同一编号。人工服务未结束时不能删除当前店铺对话。

会话中的物流查询已接入观察驱动的持久化运行器（V13/V14）：模型读取工具结果后决定下一步，Java 校验归属与工具顺序，并限制整轮步数、期限、Token 和估算费用。新运行的只读步骤遇到白名单临时故障可自动重试一次，次数和规则持久化；失败后仍可显式恢复，但不自动重发原消息。模型连续失败时共享熔断器暂停调用并以单请求探测恢复，详见[故障治理说明](../docs/agent-resilience.md)。聊天提交等待上限为105秒，覆盖后端最多100秒的运行期限；普通接口仍为25秒。当前聊天页保持简洁，不新增运行管理面板；查询进度和恢复 API 见[多轮 Agent 说明](../docs/agent-loop-budget.md)。已完成 V12 两张运行表授权的环境升级 V13/V14 不需要额外授权；新环境仍需先执行[运行表授权脚本](../config/agent-runtime-grants.sql)。

- 「会话」与「订单」独立切换。默认只显示聊天，订单列表和详情放在订单页；点击「咨询此订单」自动回到会话并重新查询订单。普通切换保留草稿和消息，不自动发送消息。
- 订单页展示当前买家在全部店铺的订单，支持刷新和游标翻页；不同店铺的同号订单分别保留。每张卡标明所属店铺，点击「咨询此订单」自动进入该店铺会话，并重新核验归属和最新状态。
- 选择订单并咨询；后续消息可携带本轮明确选中的 `orderNo`。切店、取消选择、话题过期或转为其他话题后移除旧选单；从历史话题引用恢复时不会自动选单。
- 已发货且未记录收货时间的本人订单可点击「确认收货」，二次确认已经收到商品后提交。后台记录收货时间、订单变为已完成，并返回售后申请截止时间；列表、详情和聊天订单卡片同步更新。历史已完成但缺少收货时间的订单不会出现补填入口。
- 自然语言提问、明确查单指令、回复中的可选订单卡片、订单详情。
- 「服务规则」直接回答规则问题，资料版本与来源留在结构化快照中用于审计；「查物流」返回本项目实际录入的物流事件，无事件不会编造轨迹。
- 「申请退款」或发送「将该订单退款」会打开退款表单。选择本人订单后，后台核验售后期限；原因分为「个人原因」「商铺原因」及「其他」，前两类选择具体原因，其他需填写说明。点击确认后才创建待审核申请，打开、选择、填写、取消均不会提交。
- 售后申请期限为确认收货后 15 天；缺少或异常收货时间转客服人工核实。过期订单禁止新提交，浏览器不计算或伪造收货时间；后端提交时再次核验。已在期限内提交的申请不会因为等待审核而失效。
- 「我的售后」读取持久化结果；旧指令「申请售后 <原因>」仍可使用，并经过相同的后台校验。
- 独立客服/管理员在「人工审核」中批准或拒绝，强制备注、权限校验、禁止自审和重复审批。
- 买家在「订单」页点击顶部「店铺 N 售后进度」，读取本人该店铺的申请、审批意见、模拟处理金额、进度及凭证；点击「刷新进度」读取最新结果。
- 管理员在「人工审核 → 处理进度」中查看最近 50 条审批记录及执行结果；仅对后端标记可重试的失败任务提供「重试模拟退款」，点击后显式提交一次。
- 登录/退出账号，密码仅用于一次登录；后续请求使用服务端会话，刷新后需重新登录。
- 错误提示、加载状态、重复发送防护、切换店铺/断开后的迟到响应隔离。
- 桌面与移动端布局、键盘操作、中文输入法 Enter 防误发。

固定指令「我的订单」「查订单 SF10001」「咨询此订单」不依赖模型。自然语言查单需后端启用 DeepSeek；新增物流/售后在关闭模型时可使用受限规则规划器。知识检索是本地文档检索与引用式抽取，不是任意知识问答。物流未接真实快递接口；审批通过不等于已退款。当前不支持流式聊天；浏览器登录后使用 HttpOnly 会话 Cookie，对话历史、售后与审计持久化到 MySQL。

## 接口与安全边界

1. 用户点击连接，`ApiClient` 的显式 `session` 模式先匿名请求 `GET /api/v1/security/csrf`，再用此校验信息 POST `/api/v1/security/login`，JSON 只提交本次输入的用户名和密码。登录返回 204 后重新读取 CSRF，再 GET `/api/v1/security/me` 核对账号类型与能力；买家、商家入口不互相接纳账号。
2. 浏览器保存后端的 HttpOnly 会话 Cookie；后续业务请求不携带 Basic 凭证，密码、令牌均不写入浏览器存储。每次写操作重新获取 CSRF，不长期缓存。登录后携带 `X-Session-Account` 作为账号一致性检查；它不能建立身份或授予权限，服务端仍以会话认证为准。
3. 订单页使用 GET `/api/v1/orders?afterId=0&size=20` 读取认证买家的跨店订单，不传买家 ID。账号列表及游标与店铺聊天、退款候选分开保存；换店不清空账号列表，换号或退出立即清空并丢弃迟到响应。
4. 店内聊天的「我的订单」和退款候选仍只查询当前店铺；候选接口为 GET `/api/v1/shops/{shopId}/orders?afterId=0&size=20`。点击账号订单先切换到订单所属店铺并恢复会话／人工状态，再发起该店订单咨询；人工服务期间只 GET 核验订单，不自动发送智能或人工消息。智能聊天使用 POST `/api/v1/shops/{shopId}/chat/conversations/{id}/messages`，发送 `clientMessageId`、`message`、可选 `orderNo` 和可选 `resetContext`（缺省为 `false`），同时发送 CSRF 请求头及同源 Cookie。无状态聊天接口仅保留兼容用途，不保存会话话题。
5. 页面按响应的 `TEXT / ORDER_DETAILS / ORDER_SELECTION / AFTER_SALE_FORM` 展示文本、订单卡片或退款原因表单。表单指令最多携带一笔已验证归属的订单；空列表时必须先选订单。买家身份和订单归属始终由后端验证，前端不传 `buyerId/customerId`。
6. 商家页 GET `/api/v1/merchant/shops/{shopId}/after-sales` 读取当前授权店铺最多50条待审，POST 同前缀 `/{uuid}/decision` 提交人工决定；保留会话 Cookie 与新 CSRF，不自动重试决定。
7. 买家进度读取 GET `/api/v1/shops/{shopId}/after-sales`；商家进度读取 GET `/api/v1/merchant/shops/{shopId}/after-sales/history`。金额来自后端公开 DTO，前端不能修改退款金额。
8. 商家手动重试使用 POST `/api/v1/merchant/shops/{shopId}/after-sales/{uuid}/execution/retry`，不提交金额，保留店铺授权与 CSRF 校验。浏览器不会自动重放该请求，原全平台 `/api/v1/reviews/**` 已关闭。
9. 退款表单通过 GET `/api/v1/shops/{shopId}/orders/{orderNo}/after-sale-eligibility` 读取期限核验，确认时 POST 同一订单的 `/after-sales`，请求体仅包含 `reason`。同样使用新 CSRF；确认前不会创建申请，失败也不会自动重放。切店、关闭或断开后，旧检查结果不会覆盖新表单。
10. 确认收货使用 POST `/api/v1/shops/{shopId}/orders/{orderNo}/receipt-confirmation`，不发送请求体、时间、金额或买家身份。每次显式确认获取新 CSRF，并与其他请求共用队列；服务端要求订单读取及确认收货权限。返回 `order / alreadyConfirmed / eligibility`，页面只展示服务端时间与截止日期，不用浏览器时钟起算；重复请求保留原收货时间，网络失败不自动重放，提示先刷新核实。

所有请求使用同源 `/api`，开发代理保留 Cookie 与 CSRF，不关闭安全过滤器，不添加通配 CORS。API 层串行执行「获取新 Token → POST」请求对，避免订单列表和聊天并发造成会话轮换竞态。403 可能是权限不足，也可能是 CSRF 失效；前端不会盲目自动重放 POST。401 会清空账号、订单、选中项、聊天与商家店铺授权。

退出会先清空页面并取消旧请求，再获取新 CSRF、POST `/api/v1/security/logout` 使服务端会话失效；网络失败会提示退出结果未确认。下次登录等待前次退出完成，避免迟到的退出清除新账号。关闭登录框时也清理已经完成的登录交换。其他标签页切换账号或退出时，通过 `BroadcastChannel` 清空旧页面；服务端账号一致性检查继续防止旧标签页误用新账号的 Cookie。刷新页面不会自动沿用浏览器中的身份，必须显式重新登录。

`ApiClient` 默认的 Basic 构造模式只供现有直接 API 测试兼容；用户端和商家端均明确选择会话模式，登录失败不会退回 Basic。尚无自助注册、找回密码或权限管理页面。生产部署仍需 HTTPS、适当的会话期限与正式账号运维，并让反向代理将 `/api` 转发给后端；`dist` 是静态资源，Vite 开发代理不会自动随它部署。

当前 JSON 数值 ID/游标只接受 JavaScript 可精确表示的安全整数。若未来数据库 ID 可能超过 `Number.MAX_SAFE_INTEGER`，应统一把后端这些 DTO 字段改为十进制字符串，再同步前端协议，不能悄悄四舍五入。

商家知识库页会读取当前店铺的检索准备状态，展示已准备的资料段数；GET 状态接口不计算向量。只有具有知识管理权限的员工点击「准备检索索引」，才会携带新 CSRF 提交一次本地准备请求；重复点击由后端继续处理缺失项，页面不自动重试或调用外部模型。发布或下线后重新读取状态，切店、退出、身份失效后丢弃旧结果。尚未启用或暂不可用时仍保留基础检索；买家页不显示这些管理信息。

## 代码沿什么顺序看

```text
点击「咨询此订单」
  → components/OrderCard.vue 发出 select 事件
  → App.vue 切回会话并调用 selectOrder(order)
  → composables/useSupportDesk.ts 构造消息、显式订单与待发送 resetContext
  → api/client.ts 附加会话 Cookie + CSRF，发起 HTTP
  → Java ConversationController → ConversationService
      → JdbcConversationStore.reserve 校验会话归属，预留轮次并读取上次成功话题
      → ConversationContextEngine 解析本轮目标，必要时澄清或重新核验引用
      → ConversationOrchestrator / Router
      ├─ 知识问题 → 本地知识检索 → 回答正文 + 独立的版本化来源快照
      └─ Agent → 订单 / 物流 / 售后工具 → Java 归属与业务校验 → MySQL
      → JdbcConversationStore.succeed 原子保存消息、响应快照与话题引用
  → ConversationTurnResponse（双方消息，客服 response 内含 ChatResponse）
  → useSupportDesk 更新 messages、当前话题和本轮新鲜订单选择
  → Vue 更新聊天气泡与订单详情，不展示来源或话题提示条
```

`types.ts` 对应公开 DTO；`api/client.ts` 只管网络和协议；`useSupportDesk.ts` 组织页面状态和操作；组件只展示和响应用户事件。取消旧请求和检查上下文用于避免「旧店铺的结果跑到新店铺」。没有为单页工作台额外引入全局状态框架。

用户端不再挂载 `ConversationContextNotice.vue`。`ApiClient` 继续校验 `NONE / ORDER / KNOWLEDGE` 引用结构、来源唯一性与到期时间格式，兼容旧回复缺失或为空的 `context`。`useSupportDesk` 在成功响应后更新内部引用，切换会话时中止旧请求，失败时保留待发送清除标记；这些状态处理不能替代后端归属与业务校验。

`ReceiptConfirmationDialog.vue` 先解释确认收货的影响，用户第二次确认才发出操作事件。成功后保留结果面板展示后端记录的时间和售后期限。订单详情只为当前选中且有收货时间的订单按需读取期限，不对每张列表卡片单独发请求。切店或断开会丢弃迟到结果，确认过程中禁用关闭与重复点击。

售后写入后停在 `PENDING_REVIEW`，模型不能自行批准。`ReviewPanel.vue` 展示待审数据，由独立客服/管理员显式提交决定；Java 再次校验后写状态与审计，并为新批准的申请创建持久化执行任务。后台执行学习版模拟退款，临时失败按退避时间重试，总执行次数最多 5 次。

`AfterSalesPanel.vue` 展示买家售后记录；`ExecutionProgress.vue` 复用在买家和管理员视图，分别显示审批结果与执行状态。历史已批准但未创建任务的申请不会自动补执行。页面通过显式刷新获取最新结果；买家也可重新发送「我的售后」查询，不会自动推送到旧聊天。所有“成功”和凭证均指模拟处理，不代表真实资金到账。完整边界与类职责见[架构说明](../docs/assistant-architecture.md)。

## 检查与构建

```powershell
npm.cmd run check      # TypeScript + API/状态流单元测试
npm.cmd run build      # TypeScript + 生产打包，输出 dist/
npm.cmd run test:e2e -- --output=test-results/new-mock-run   # 使用尚不存在的输出目录
```

浏览器测试默认使用本机 Microsoft Edge，不读取你的浏览器资料或登录态。其他环境可设置 `$env:PLAYWRIGHT_CHANNEL='chromium'` 并先安装 Playwright Chromium。测试默认独占 5173 和 mock 18080；已有日常 Vite 时，可设置 `$env:E2E_FRONTEND_PORT='5174'` 使用独立测试前端，无需停止日常服务。mock 只由测试命令启动，不是正常开发模式的后备数据。每次传入新的、尚不存在的 `--output` 目录；不要复用旧测试结果目录，避免测试运行器自动清理既有文件。

真实Java后端浏览器验证使用 `src/test/java/dev/supportflow/frontend/FrontendTestServer.java`：它只加载测试配置，在一次性Testcontainers MySQL中执行所有当前迁移并创建公开假买家/商家账号；强制关闭DeepSeek。普通应用运行不会加载这个类。测试覆盖会话、订单、退款与确认收货、独立商家审批、跨店隔离，以及知识草稿/发布/下线、旧发布快照、审计、知识/审核专职账号、409冲突保留输入和丢失发布响应不重放；人工接待用例覆盖买家请求、专职客服接待与回复、结束后恢复智能客服。`SF10002`为期限内订单，`PAGE-001`为过期订单，`SF10001`缺少收货时间；这些测试日期只写入临时容器。

确认收货浏览器用例使用独立的已发货订单 `SFRECEIPT01`，不会改变其他测试依赖的 `SF10001`。店铺 100 的买家共有 24 笔订单，第一页 20 笔，加载更多后可见新订单。用例验证打开或取消不提交、明确确认后状态和时间同步、后台截止时间恰为收货后 15 天、重复请求不刷新日期，以及刷新页面后仍可读取期限；`PAGE-021` 为已完成但缺少收货时间的历史订单，不提供确认按钮。运行后生成 `test-results/receipt-confirmation.png` 供界面检查。这个用例要求新启动的隔离测试容器，不在日常业务库回退订单状态。

从仓库根目录先执行 `mvn -o verify`（首次依赖未缓存可去掉 `-o`），生成测试类和报告。然后在同一根目录打开单独的 PowerShell 终端：

```powershell
$report = [xml](Get-Content -Raw target\surefire-reports\TEST-dev.supportflow.chat.infrastructure.deepseek.DeepSeekOrderIntentRecognizerTest.xml)
$testClasspath = ($report.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' } | Select-Object -First 1).value
& 'D:\develop\jdk\bin\java.exe' -cp $testClasspath dev.supportflow.frontend.FrontendTestServer
```

JDK 路径按本机安装调整，或用 PATH 中的 `java`。等待 `FRONTEND_TEST_BACKEND_READY` 后，在前端目录另开终端：

```powershell
$env:E2E_BACKEND_URL='http://127.0.0.1:18081'
$browserOutput = 'test-results/live-' + (Get-Date -Format 'yyyyMMdd-HHmmssfff')
if (Test-Path -LiteralPath $browserOutput) { throw '请选择尚不存在的测试输出目录' }
npm.cmd run test:e2e -- --output=$browserOutput
$env:E2E_BACKEND_URL=$null
```

测试使用 launcher 中公开的假账号，不使用你的日常账号。完成后 Ctrl+C 停止测试后端，临时容器随其关闭。不要将 `E2E_BACKEND_URL` 指向日常业务实例。

参考：[Vue TypeScript](https://vuejs.org/guide/typescript/overview)、[Vite](https://vite.dev/guide/)、[Playwright Web Server](https://playwright.dev/docs/test-webserver)。
