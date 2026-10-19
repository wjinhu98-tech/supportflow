# 聊天查订单：把两个已有功能接起来

## 本次做了什么

之前发送消息只会回显，查询订单需要访问另外的订单接口。
本页介绍单笔查单指令，复用已有订单查询能力；不调用 AI，不增加数据库表。
后来增加的“我的订单”及翻页功能见 [聊天订单列表](chat-order-list.md)。

请求：POST /api/v1/chat/messages，继续使用 HTTP Basic 和 CSRF token。

```json
{"message":"查订单 100 SF10001"}
```

这段输入只指定“商铺100”和“订单SF10001”，没有指定买家。
买家身份必须来自服务端认证成功的账号，不能从消息、请求体或请求头中指定。

在自动化测试里，买家200拥有这笔订单，数据库状态为已发货、金额199.90元。
因此回复是：

```json
{"reply":"商铺 100 的订单 SF10001：已发货，订单金额 199.90 CNY。"}
```

状态和金额来自实际查询结果，不是识别到订单号后拼一段固定答案。
这笔样例订单只存在于自动化测试的临时库；本地没有它时返回404是正常行为。

## 一次输入怎样变成回复

1. 安全过滤链核对账号密码与 CSRF token，取得可信买家身份。
2. ChatController 接收消息，把认证身份和消息传给 ChatService。
3. OrderLookupCommand 将明确的指令解析成商铺ID、订单号；普通消息不查库。
4. ChatService 调用已有 OrderQueryService。方法级授权首先检查 ORDER_READ。
5. 订单服务复用原仓储，以商铺、认证买家、订单号三个条件访问 MySQL。
6. 查到订单后，ChatService 用状态描述和金额组成回复；Controller 包装为 JSON。

只有仓储通过 JDBC 访问 MySQL 时跨越网络边界，其余步骤是同一 Java 进程内调用。

## 这次涉及的类各负责什么

| 类 | 谁调用它 | 输入 | 输出与职责 |
| --- | --- | --- | --- |
| ChatController | Spring MVC | 认证 principal、ChatRequest | 调用 ChatService，返回 ChatResponse |
| OrderLookupCommand | ChatService | 消息字符串 | 返回商铺ID与订单号；普通聊天返回空，格式错误抛专用异常；不查库 |
| ChatService | ChatController | CustomerIdentity、消息 | 调用 OrderQueryService，返回回复文字 |
| OrderQueryService（已有） | 订单与聊天两个入口 | 可信身份、商铺ID、订单号 | 检查读取权限，调用仓储，返回真实订单或抛404业务异常 |
| GlobalExceptionHandler（已有） | Spring MVC | 处理中的异常 | 将指令错误转400、权限拒绝转403、不可访问订单转404 |

## 为什么权限检查放到订单服务

聊天 POST 允许任何已认证账号发送普通消息；它不能因此允许所有账号查询订单。
在 OrderQueryService 上启用方法级 ORDER_READ 检查，使两个入口复用同一条权限规则。
原订单 HTTP 路由权限检查继续保留。方法级授权依靠 Spring 管理的代理生效，
直接 new 一个服务对象的单元测试不验证授权，真实 Spring 集成测试负责验证这一点。

实现依据：[Spring Security 方法级授权](https://docs.spring.io/spring-security/reference/7.0/servlet/authorization/method-security.html)。

## 本次明确的行为边界

- 普通消息如“你好”“帮我查一下订单”仍然回显，不做自然语言理解。
- 去除首尾空白后，以“查订单”开头的消息保留为指令；格式不对返回400及格式提示。
- 格式为 `查订单 <商铺ID> <订单号>`，允许字段之间多个空白。
- 商铺ID为正的 long；订单号为不含空白的1至64字符，保留大小写。
- 消息不能为空，最多1000个字符；多余字段、商铺ID溢出等都不能绕过校验。
- 未认证请求在携带有效 CSRF 的情况下返回401；CSRF缺失可能先返回403。
- 没有 ORDER_READ 时普通聊天仍成功，合法查单请求返回403。
- 别人的订单、没有可信买家归属的历史订单与不存在的订单一律404，不透露是否存在。
- 单笔查单回复只包含商铺、订单号、状态描述、金额和币种，不输出内部主键或买家/历史客户编号。
- 本页描述单笔查询；列表功能见上述独立文档。不实现模型调用、多轮记忆、下单、支付或物流轨迹。

## 验收方法

运行 `mvn -o verify`（本地已有依赖时）。不执行 clean。

- OrderLookupCommandTest：验证指令识别与边界，不启动 Spring 或数据库。
- ChatServiceTest：验证身份传递、真实订单转文字、普通消息不查库及异常传播。
- OrderApiIT：使用真实认证过滤链、方法授权和 Testcontainers MySQL，验证本人、跨店、越权、身份伪造、CSRF与参数边界。

测试使用独立配置和随机凭据，不导入 .local 配置，不连接日常业务库。
chat.http 提供手工请求示例；执行前须启动本地应用并使用自己合法的开发凭据。

2026-10-16 实际执行 `mvn -o verify`：32个单元测试、58个集成测试，
合计90个测试，失败、错误、跳过均为0，BUILD SUCCESS。
本次新增10个单元测试和12个集成测试；未启动本地业务服务。

本次验收完成后即停止扩展，下一步功能另行确认。
