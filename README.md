# SupportFlow

Java / Spring Boot + Vue 店铺订单客服学习项目，包含店铺本人订单、版本化知识检索、受限 DeepSeek 工具调用、学习版物流、售后人工审批与持久化模拟退款处理。

- 后端：当前目录 Maven 工程，沿用 `local` profile 与忽略提交的本地配置。
- 前端：[`frontend/`](frontend/README.md)，Vue 3 + TypeScript + Vite，按店铺上下文接入真实后端。
- 聊天接口：[`docs/shop-chat.md`](docs/shop-chat.md) 与 [`chat.http`](chat.http)。
- 架构与首次启用：[`docs/assistant-architecture.md`](docs/assistant-architecture.md)。**新售后功能需要 V4、表级写权限与独立审核账号，不能直接沿用只有 SELECT 的旧账号完成写入。**
- 本地演示数据与订单号：[`docs/local-demo-data.md`](docs/local-demo-data.md)。
- 售后执行与重启恢复：[`docs/after-sale-execution.md`](docs/after-sale-execution.md)。**本轮新增 V5；运行账号需追加任务与模拟回执表权限后重启后端。**
- 会话与聊天记录：[`docs/conversation-history.md`](docs/conversation-history.md)，**新环境需要 V8 和会话表最小运行权限；恢复历史不重放业务操作。**
- 每店一个聊天窗口：[`docs/shop-chat-windows.md`](docs/shop-chat-windows.md)，V15 汇总旧咨询，切店 / 重连恢复同一窗口；支持主动删除确认。新增演示店铺 102 / 103，商家账号仍只管理店铺 100。
- 受控会话话题：[`docs/conversation-context.md`](docs/conversation-context.md)，保存有限的订单号或知识来源引用，30 分钟过期；追问重新查实时订单或当前发布资料，写操作不继承历史订单。复用现有响应 JSON，无新增数据库迁移或授权。
- 模型调用保护：[`docs/agent-resilience.md`](docs/agent-resilience.md)，三个 DeepSeek 入口共享并发上限与熔断器；连续失败后暂停并以单请求探测恢复。只读 Agent 在持久化步骤层最多自动重试一次，原始聊天与业务写入不自动重发。V14 沿用已有运行表授权。
- 容器部署、监控与容量验证：[`docs/deployment-and-capacity.md`](docs/deployment-and-capacity.md)，独立教学数据库与账号、受保护指标、真实读取压测及停机恢复记录；部署步骤见 [`deploy/README.md`](deploy/README.md)。
- 持久化 Agent 运行：[`docs/agent-loop-budget.md`](docs/agent-loop-budget.md)，会话中由模型读取工具结果并决定下一步，只读工具、Java 校验、步骤 Checkpoint、双代次隔离和显式恢复；按运行限制步数、总期限、Token 与估算费用。V12 旧运行保留原三阶段恢复，售后继续人工审批。V13 沿用两张运行表的已有权限。
- 用户/商家双入口与店铺授权：[`docs/merchant-workspace.md`](docs/merchant-workspace.md)，**V9后用户端仍为根页面，商家管理端为 `/merchant`，审核必须有显式店铺授权。**
- 商家店铺知识库：[`docs/shop-knowledge-management.md`](docs/shop-knowledge-management.md)，**V10支持独立知识维护权限、草稿/发布/下线、版本审计和当前店铺已发布资料检索。**
- 知识问答质量与面试依据：[`docs/knowledge-quality-and-interview.md`](docs/knowledge-quality-and-interview.md)，有界同义检索、结构化引用、历史证据快照与可复现评测；不将词法检索包装成向量RAG。

本地完整操作顺序和前端测试说明见 [前端 README](frontend/README.md)。模型 API Key 只放在后端本地私密配置，不放进前端或 Git。
