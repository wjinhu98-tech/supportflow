# SupportFlow

Java / Spring Boot + Vue 店铺订单客服学习项目，包含店铺本人订单、版本化知识检索、受限 DeepSeek 工具调用、学习版物流、售后人工审批与持久化模拟退款处理。

- 后端：当前目录 Maven 工程，沿用 `local` profile 与忽略提交的本地配置。
- 前端：[`frontend/`](frontend/README.md)，Vue 3 + TypeScript + Vite，按店铺上下文接入真实后端。
- 聊天接口：[`docs/shop-chat.md`](docs/shop-chat.md) 与 [`chat.http`](chat.http)。
- 架构与首次启用：[`docs/assistant-architecture.md`](docs/assistant-architecture.md)。**新售后功能需要 V4、表级写权限与独立审核账号，不能直接沿用只有 SELECT 的旧账号完成写入。**
- 本地演示数据与订单号：[`docs/local-demo-data.md`](docs/local-demo-data.md)。
- 售后执行与重启恢复：[`docs/after-sale-execution.md`](docs/after-sale-execution.md)。**本轮新增 V5；运行账号需追加任务与模拟回执表权限后重启后端。**
- 会话与聊天记录：[`docs/conversation-history.md`](docs/conversation-history.md)，**新环境需要 V8 和会话表最小运行权限；恢复历史不重放业务操作。**
- 用户/商家双入口与店铺授权：[`docs/merchant-workspace.md`](docs/merchant-workspace.md)，**V9后用户端仍为根页面，商家管理端为 `/merchant`，审核必须有显式店铺授权。**

本地完整操作顺序和前端测试说明见 [前端 README](frontend/README.md)。模型 API Key 只放在后端本地私密配置，不放进前端或 Git。
