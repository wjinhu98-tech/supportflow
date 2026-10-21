# 本地联调测试数据

`tools/LocalDemoData.java` 为本机 `127.0.0.1:3306/supportflow_rebuild` 添加固定的演示数据。它只在显式运行时执行，不会随应用启动或 Flyway 自动执行。

本批订单号以 `SFDEMO20261021` 开头。当前开发账号 `customer-200` 对应 `buyer_id=200`。

| 店铺 | 买家 | 订单数 | 用途 |
| --- | --- | --- | --- |
| 100 | 200 | 8 | 当前页面的订单查询、状态展示、物流和售后申请 |
| 101 | 200 | 2 | 切店查询，以及同号订单按店铺隔离 |
| 100 | 201 | 2 | 验证其他买家的订单不会出现在当前账号中 |

共12笔订单、13条学习版物流事件。没有预先创建售后单；在页面选择已付款、已发货或已完成的订单后，发送 `申请售后 商品破损` 走完整业务流程。

店铺100可直接尝试：

| 订单号 | 状态 | 金额（元） |
| --- | --- | --- |
| SFDEMO2026102101 | 待付款 CREATED | 59.90 |
| SFDEMO2026102102 | 已付款 PAID | 199.00 |
| SFDEMO2026102103 | 已发货 SHIPPED，含物流记录 | 299.90 |
| SFDEMO2026102104 | 已完成 COMPLETED，含签收记录 | 89.00 |
| SFDEMO2026102105 | 已取消 CANCELLED | 39.90 |
| SFDEMO2026102106 | 已发货 SHIPPED，含物流记录 | 1299.00 |
| SFDEMO2026102107 | 已付款 PAID | 26.80 |
| SFDEMO2026102108 | 已完成 COMPLETED，含签收记录 | 168.00 |

店铺101也有 `SFDEMO2026102103`，金额为399.90元，用于验证相同业务订单号在不同店铺下不会混淆。买家201的数据不应被 `customer-200` 查询到。

## 显式运行

从仓库根目录执行，需要 JDK21、已有 V4 表结构及本地迁移账号。工具在本机进程内读取 `SUPPORTFLOW_MIGRATOR_PASSWORD` 环境变量，未设置时从 `.local/secrets.yml` 中取同名属性，不打印密码。依赖沿用项目 Maven 测试类路径；以下报告由 `mvn verify` 生成。

```powershell
cd D:\project\supportflow-rebuild
$demoReport = [xml](Get-Content -Raw target\surefire-reports\TEST-dev.supportflow.chat.infrastructure.deepseek.DeepSeekOrderIntentRecognizerTest.xml)
$demoClasspath = ($demoReport.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' } | Select-Object -First 1).value
java -cp $demoClasspath tools/LocalDemoData.java inspect
java -cp $demoClasspath tools/LocalDemoData.java apply
```

`inspect` 只读核对目标库、V4与本批计数。`apply` 在同一事务内插入订单和物流，数据库自动分配主键；重复执行复用完全匹配的订单并跳过已有物流。若相同店铺/订单号已有不同的归属、金额、状态或币种，整批回滚，保留原数据。

导入后在前端点击订单列表的刷新，或发送「我的订单」。查询物流可选择 `SFDEMO2026102103` 后点击「查物流」，也可直接发送 `查物流 SFDEMO2026102103`。
