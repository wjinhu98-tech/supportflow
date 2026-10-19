# 第4天：带买家身份范围的订单查询接口

本日完成了本地账号配置绑定、真实密码认证、安全过滤链、订单 HTTP 接口和整链测试。
账号密码为独立生成的开发凭据；原有数据库密码文件未读取或修改。
本地账号来源是外部配置，生产身份接入属于后续工作。
后续已按“同一买家查看多家商铺订单”的需求调整身份模型。
当前身份为全局 buyerId；旧的固定 tenantId/customerId 身份和单订单号详情入口已停用。
接口、分页与存量迁移说明见 docs/cross-shop-buyer-orders.md。

## 一次请求怎样流动

    GET /api/v1/shops/100/orders/SF10001 + HTTP Basic 凭据
      → Spring Security 安全过滤链
      → ProviderManager
      → DaoAuthenticationProvider
      → LocalCustomerUserDetailsService 读取服务端账号配置
      → PasswordEncoder 核对密码哈希
      → 认证结果携带 CustomerPrincipal
      → ORDER_READ 权限检查
      → OrderController 取得 CustomerIdentity
      → OrderQueryService
      → JdbcSupportOrderRepository 绑定三个 SQL 参数
      → JDBC 跨进程访问 MySQL
      → SupportOrder
      → OrderResponse
      → JSON

过滤链到仓储之间是同一 JVM 内的方法调用；JDBC 执行查询时才跨到 MySQL 进程。

## 认证配置类的作用

LocalCustomerAccountsProperties 把 supportflow.security.local 下的账号列表绑定成 Java 对象。
它拒绝空账号和非 BCrypt 格式的密码配置，列表不可修改，日志输出不包含哈希。
LocalAuthenticationConfiguration 只在 local profile 生效，把账号来源、编码器、provider 和 manager 注册为 Bean。

    new DaoAuthenticationProvider(userDetailsService)
    provider.setPasswordEncoder(passwordEncoder)
    new ProviderManager(provider)

第一行使认证组件能够按用户名取账号，第二行提供密码核对能力，第三行提供统一认证入口。
这些依赖由 Spring 根据 Bean 类型传入；变量是否同名不决定注入关系。

## Controller 怎样取得查询范围

    orderQueryService.getOwnedOrder(
        principal.getIdentity(),
        shopId,
        orderNo
    )

shopId 和 orderNo 来自 URL，用来定位商铺内订单。
identity 来自认证成功的 principal，仅包含服务端账号配置中的全局 buyerId。
请求中的 buyerId、customerId 或相关身份请求头不作为查询身份来源。
请求前置权限验证检查能否执行订单读取；仓储 SQL 进一步限制能读取哪一行。

## 应用服务怎样解释没有结果

    supportOrderRepository.findByOrderNo(
        shopId,
        identity.buyerId(),
        orderNo
    ).orElseThrow(OrderNotFoundException::new)

仓储读到一行时返回订单；零行时返回 Optional.empty。
应用服务将空结果解释成范围内没有可访问订单，抛出 OrderNotFoundException。
统一异常处理器把这个业务结果映射为404，避免透露别人的订单是否存在。

## 响应 DTO 的作用

OrderResponse 公开 shopId、订单号、状态编码与描述、金额、币种及两个 UTC 时间。
shopId 对应订单中的 tenant_id，供跨商铺同号订单定位。
内部主键、buyerId 和历史 customerId 不出现在订单响应中。
列表响应的 nextAfterId 仅作分页游标；每次翻页仍强制校验当前买家归属。
领域模型继续供业务与仓储使用，DTO 用来维护对外接口契约。

| 状态 | 错误码或结果 | 含义 |
|---|---|---|
| 200 | OrderResponse | 当前身份范围内的订单 |
| 400 | INVALID_ARGUMENT | 订单号或消息参数错误 |
| 401 | AUTHENTICATION_REQUIRED | 未认证、错误密码或未知账号 |
| 403 | ACCESS_DENIED | 缺少操作权限或 CSRF 校验失败 |
| 404 | ORDER_NOT_FOUND | 范围内没有可访问订单 |
| 500 | INTERNAL_ERROR | 服务内部故障，客户端不接收内部细节 |

401/403 在安全过滤器中产生，因此使用独立的 entry point/handler 输出同一 ApiErrorResponse。
MVC 参数错误与业务异常由 GlobalExceptionHandler 处理。

## CSRF 与 session

HTTP Basic 继续启用 CSRF 保护。GET 订单是安全读取，不需要 CSRF token。
执行 POST chat 前，先认证访问 GET /api/v1/security/csrf，保存 cookie，并在请求头中带回 token。
认证上下文采用 STATELESS；默认 CSRF token 仍可以保存在 session 中。
因此“认证上下文不保存到 session”不等于任何请求都不会产生 session。

## 本地运行与请求

在项目根目录启动 SupportFlowApplication，使用程序参数：

    --spring.profiles.active=local

新开发账号的登录信息在 .local/day4-login.txt。
密码哈希在 .local/day4-security-secrets.yml，由 application-local.yml 引用。
IDEA 打开 orders.http 并选择 local 环境，会从 http-client.private.env.json 取得登录信息。
这些私密文件均被 Git 忽略；jar 不包含这些文件。

示例 SF10001 数据只在自动化测试的临时库中准备。
本地业务库按实际数据查询；如果没有这个订单，登录成功后返回404属于正确行为。
本日没有往现有业务库插入测试订单。

## 自动化验收

    mvn test
    mvn verify

第一个命令运行 Surefire 单元与配置测试；第二个命令进一步打包并执行 Failsafe 集成测试。
测试数量以本次 Maven 报告为准；跨商铺改造新增了列表、分页、全局归属和迁移验收。

OrderApiIT 加载真实 Spring Boot 配置与安全过滤链，使用 httpBasic 进行实际密码认证。
它加载专用测试配置，不读取本地私密配置；Testcontainers 创建独立 MySQL，管理员准备数据，
实际应用使用只读账号。测试同时验证只读授权和 MySQL 会话 UTC。

覆盖本人跨商铺订单、同号订单定位、其他买家拒绝、伪造身份、无效订单号、SQL注入式输入、
JSON401/403、真实CSRF token往返、消息参数校验及错误派发。

本次身份模型调整不增加新的登录方式或下单功能。

实际 local 服务启动会连接现有业务库，本次改造未启动该服务。
公开 local YAML 模板已用内存测试哈希绑定验收，未读取私密文件；
完整接口链路在独立 Testcontainers 数据库中验收。
