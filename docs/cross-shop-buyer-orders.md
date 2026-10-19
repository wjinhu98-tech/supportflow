# 平台买家的跨商铺订单查询

## 业务约定

一个平台买家可以查看自己在多家商铺的订单。
商铺仅表示订单卖方，不能限制一个买家只能属于一家商铺。
所有订单查询都使用认证结果中的全局 buyerId，客户端不能指定查询身份。

| 名称 | 含义 | 来源 |
| --- | --- | --- |
| buyerId / buyer_id | 平台全局买家ID | 可信账号资料和已确认的订单归属 |
| shopId / tenant_id | 订单所属商铺 | 订单记录；详情请求中用作定位条件 |
| customer_id | 历史店铺内客户编号 | 原有订单记录；不用于跨商铺身份认证 |
| orderNo / order_no | 商铺内唯一的订单号 | 订单记录 |

CustomerIdentity 现在仅保存 buyerId。
本地开发账号配置改为 identity.buyer-id；用户名和密码哈希占位符不变。
LocalCustomerAccount 仍保存账号资料，认证成功后的 CustomerPrincipal 携带全局买家身份。
生产环境应从平台统一账号体系取得 buyerId；本次仍使用已有 local 账号来源。

## API

### 我的全部商铺订单

    GET /api/v1/orders?afterId=0&size=20

需要 HTTP Basic 认证和 ORDER_READ 权限。
afterId 默认0，必须非负；size 默认20，范围1至100。
结果按内部订单主键 id 递增，并非按下单时间倒序。

响应包含 items 和 nextAfterId。items 是 OrderResponse 列表，每条包含 shopId。
nextAfterId 非空时，将其原样作为下一次请求的 afterId。
nextAfterId 为 null 表示本次查询时没有下一页；空结果也返回200。
服务多读一条来判断下一页，并用最后一条实际返回的订单生成游标。
每次分页都重新按当前认证 buyerId 过滤，篡改游标不会赋予其他买家的访问权。
游标分页不提供跨请求的一致性快照；两次请求之间新增的订单可能出现在后续页中。

### 某商铺的一笔订单

    GET /api/v1/shops/100/orders/SF10001
    GET /api/v1/shops/300/orders/SF10001

同一个账号可读取这两家商铺中属于自己的同号订单。
WHERE 同时匹配 tenant_id、buyer_id 和 order_no。
shopId 从路径取得，buyerId 仅从 principal.getIdentity() 取得。
未知订单和属于其他买家的订单统一返回404 ORDER_NOT_FOUND。
shopId 必须为正数，订单号必须非空且不超过64字符。

旧的 GET /api/v1/orders/{orderNo} 已停用，认证后由默认拒绝规则返回403。
这是显式接口变更：跨商铺订单号可能重复，客户端必须携带商铺ID定位详情。
orders.http 已更新为新接口。

## 数据流

列表：请求 → 认证取得 buyerId → Controller 接收分页参数 → Service 多取一条
→ Repository 按 buyer_id、id 游标查询所有商铺 → 返回订单对象
→ Service 生成下一页游标 → OrderResponse 隐藏买家及历史客户编号 → JSON。

详情：请求的 shopId + orderNo，与认证 buyerId 汇合
→ Repository 绑定三个 SQL 参数 → 当前买家的订单或空结果。

## Flyway 与历史数据

保持已执行的 V1__create_support_order.sql 不变。
新增 V2__add_global_buyer_identity.sql：

- 增加可空 buyer_id，非空时必须为正数。
- 新增 (buyer_id, id) 索引支撑买家列表和游标查询。
- 保留原来的 (tenant_id, order_no) 唯一约束及 customer_id。

不能直接执行 buyer_id = customer_id 的全库赋值：
不同商铺里的同号客户可能是不同的人。
V2 不自动回填任何历史身份，升级后旧订单 buyer_id 为 NULL。
这些订单不会出现在买家的列表或详情中，直到根据可信账号对应关系显式补齐归属。
后续新增订单时必须由可信业务流程写入正确的全局 buyer_id。

本次代码修改和自动化测试没有连接开发者业务库。
本地应用下一次启用 Flyway 启动时会执行 V2；实际存量归属回填需另行确认映射。
没有读取或改动 .local 私密配置、密码或 HTTP 客户端私密环境文件。

## 验证

在项目根目录运行 mvn verify；不需要 mvn clean。
测试使用独立的 Testcontainers MySQL，API 测试配置不会导入本地私密文件。

覆盖：同一买家跨商铺同号订单、不同店铺客户编号映射到同一买家、
相同店铺客户编号不能冒充其他买家、未知归属的历史订单不可见、
空列表、连续分页、游标和身份参数伪造、分页和商铺参数校验、
401/403/404、只读数据源、原有 CSRF 与聊天接口回归，
以及 V1 存量数据升级到 V2 后保持数据且不自动推断身份。
