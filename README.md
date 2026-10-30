# SupportFlow

Java / Spring Boot + Vue 店铺客服学习项目：买家查本人订单与物流、看带来源的规则、申请退款；商家维护知识、人工审核并接待咨询。模型参与受限决策，Java 控制身份、权限、状态与执行边界。

## 从这里开始

| 想做什么 | 入口 |
| --- | --- |
| 下载可运行交付包 | 仓库 Actions → **SupportFlow CI and delivery** → 成功的 main 推送或手动 run → Summary / Artifacts；先看 [安装与包校验](docs/delivery-install.md) |
| 五分钟展示买家到商家的闭环 | [演示步骤](docs/demo-guide.md)：隔离 LAB、双浏览器、默认关闭付费模型 |
| 从业务看懂代码 | [项目地图与调用链](docs/project-overview.md)：角色、输入输出、谁调用谁、数据库边界 |
| 准备 Java / AI 应用面试 | [面试指南](docs/interview-guide.md)：设计取舍、失败处理、证据与表述范围 |
| 沿用本机开发环境 | [前端与联调](frontend/README.md)、[日常演示数据](docs/local-demo-data.md)、[源码 Compose 实验](deploy/README.md) |

交付包包含后端、前端、隔离部署脚本与校验信息；需要可离线导入的运行镜像时，在手动工作流中选择 `include_images=true`。以具体 run 的结果为准，新增工作流或本地验证通过不代表云端已经通过。工作流见 [CI 配置](.github/workflows/agent-evaluation.yml)。

## 能力与边界

已有店铺/买家隔离、商品名查单与商品图、每店聊天窗口、退款原因与收货后 15 天规则、人工审批和持久化模拟退款；也有数据库账号、服务端会话、限流与追踪、只读 Agent 检查点/预算/有限重试/熔断、可选 BGE 与持久向量、TXT/MD 知识草稿发布、独立权限的人工接待。

当前为单体学习系统。物流读取本地记录，退款只产生模拟回执；未实现真实支付、微服务、Redis、MQ、WebSocket 或 PDF/OCR。隔离演示默认关闭 DeepSeek 和 BGE，不会自动调用付费模型；模型 Key 只保存在后端私密配置。

## 专项文档

- 业务：[商品名查单](docs/product-order-lookup.md)、[订单删除标记](docs/order-deletion.md)、[商家授权](docs/merchant-workspace.md)、[售后执行](docs/after-sale-execution.md)、[人工接待](docs/human-support.md)。
- AI 与状态：[Agent 循环与预算](docs/agent-loop-budget.md)、[模型保护](docs/agent-resilience.md)、[会话上下文](docs/conversation-context.md)、[语义检索](docs/semantic-rag.md)、[离线评测](docs/agent-evaluation.md)。
- 知识与工程：[店铺知识发布](docs/shop-knowledge-management.md)、[文件导入](docs/knowledge-file-import.md)、[工程补全](docs/job-readiness.md)、[部署与容量实验](docs/deployment-and-capacity.md)。
- 接口：[店铺聊天](docs/shop-chat.md)、[HTTP 示例](chat.http)；图片来源见 [商品图片说明](docs/product-images.md)。
