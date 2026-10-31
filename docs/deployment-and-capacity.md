# 部署、监控与容量验证

本轮交付是可复现的本机容器教学环境：独立数据库、同源前端代理、最小运行权限、独立运维认证、监控采集，以及实际执行过的容量和恢复实验。原有日常数据库 `supportflow_rebuild`、8080 后端和 5173 前端继续保留。

当前业务登录使用数据库账号、HttpOnly 服务端会话 Cookie 和 CSRF，默认关闭 Basic。该环境用于本机教学与验收；公网 HTTPS、统一身份平台、真实物流支付和外部告警通知仍需另行接入。

## 如何启动和使用

完整构建、凭据准备、启动、停止步骤见 [容器部署说明](../deploy/README.md)。运行后访问：

| 地址                                               | 作用                                              |
| -------------------------------------------------- | ------------------------------------------------- |
| `http://127.0.0.1:18090/?shopId=100`               | 新环境用户端，账号 `lab-buyer`                    |
| `http://127.0.0.1:18090/merchant`                  | 新环境商家端，账号 `lab-merchant`，仅授权店铺 100 |
| `http://127.0.0.1:18091/actuator/health/readiness` | 最小就绪状态，无配置或数据库详情                  |
| `http://127.0.0.1:19090`                           | Prometheus 查询、Targets 和 Alerts                |

原始登录密码只在本机 `.local/deployment-lab/login.txt`，不提交 Git。管理指标使用单独的 `lab-ops`，业务账号无法读取指标；运维账号也不能登录业务接口。前端代理不会转发 `/actuator`。

实验环境使用独立的 `SF_LAB_SESSION` Cookie 名，避免同机不同端口的日常站点与实验站点互相覆盖 CSRF 会话；浏览器凭据仍不持久保存。

MySQL 没有发布宿主端口。数据库网络保持 internal；网页和监控网络采用普通 bridge，以支持本次 Docker Desktop 29 实测需要的回环端口发布。不同服务仍按成员隔离，但普通 bridge 不等于禁止出站网络。实验通过 `lab` 配置关闭 DeepSeek、使用空 Key 和不挂载真实凭据，避免调用付费模型。源码 Compose 显式启用本地 BGE 与持久化向量，需要先准备模型文件；下载交付包的默认配置关闭 BGE，启用方式见 [交付安装](delivery-install.md)。

## 启动时数据如何流动

```text
本机 prepare：生成本实验的新凭据，不覆盖已有凭据
  → MySQL：新建 supportflow_lab 与独立账号
  → migrate：短生命周期迁移账号执行 V1–V22
  → grant：建表完成后授应用账号精确表/列写权限
  → seed：仅在新库为空时写入 5000 笔测试订单及 2 笔隔离样例
  → seed-accounts：导入缺失的数据库业务账号，为既有实验商家启用人工接待权限
  → backend：lab_app 运行，关闭运行时 Flyway，使用数据库会话认证
  → Nginx：构建后的 Vue + 同源 /api 代理
  → Prometheus：运维认证后采集管理端口
```

应用没有 DDL、DELETE、订单金额/买家归属修改或员工授权写权限。真实容器内执行 `LabDeploymentTool verify` 已验证这些边界；被拒绝的权限探测使用恒假谓词，不会改行。再次启动会验证迁移、保留既有种子数据，不把数据库重置为空。

V18的[买家删除订单](order-deletion.md)只追加不可变的可见性记录，不物理删除订单。实验应用对`support_order_buyer_deletion`只有读取和插入权限，不能更新或删除该表；迁移后需先重新执行`grant`步骤再更新运行服务。

V19知识文件导入同样只追加`support_knowledge_import`去重与审计记录；原有知识文章权限保持不变。实验应用对导入记录仅有读取和插入权限，不能修改或删除，模板与运行检查继续核实这一边界。

## 监控包含什么

标准指标包含 JVM、HTTP、连接池；自定义模型指标包含按固定 `operation/outcome` 分类的计数、耗时，以及真实许可占用数和上限。不以用户、订单号、消息或原始 URL 作为自定义标签。DeepSeek 在实验环境默认关闭，因此本轮没有真实供应商调用样本；相关故障和指标通过离线假 HTTP 测试验收。本地 BGE 是否启用由所用部署配置决定。

`liveness` 只检查进程存活状态；`readiness` 还检查数据库。数据库临时不可用时，让就绪状态降级，避免把依赖故障误当作必须反复重启进程的故障。指标仅供独立运维身份读取，`env/configprops/heapdump/logfile/shutdown` 等未开放。

配置依据：[Spring Boot 端点访问控制](https://docs.spring.io/spring-boot/4.0/reference/actuator/endpoints.html)、[独立管理端口](https://docs.spring.io/spring-boot/4.0/reference/actuator/monitoring.html)、[Prometheus 采集配置](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)。本项目保留 Boot 4.0.8，未因文档分支更新而升级版本。

## 历史基线：2026-10-26 Basic 认证版本

本节保留当时的测量记录。当前版本使用会话登录，以下吞吐与延迟不能直接作为当前版本的容量结论。

条件：Windows x64、i7-9750H、12 逻辑 CPU、约 31.86 GiB 宿主内存；Docker VM 可用 12 CPU、约 15.55 GiB。负载工具 Node 24.15.0；容器 Java 21.0.12.1、MySQL 8.0.45。

后端限制 1 CPU / 768 MiB、MySQL 1 CPU / 768 MiB、Nginx 0.5 CPU / 128 MiB；Hikari 最大 8、最小 2。固定读取一笔测试订单，包含 Basic/BCrypt 认证、Nginx、Java 与 MySQL 路径，不包含模型调用，也不是扫描 5000 行或复杂关联查询。

预热上限 20 次/3 秒后，每档 30 秒，并发依次为 1、4、8，每档最多 1000 次。原始结果：

| 并发 | 校验成功样本 | 成功吞吐 req/s | P50 ms | P95 ms | P99 ms | HTTP/超时/网络/校验错误 |
| ---- | ------------ | -------------- | ------ | ------ | ------ | ----------------------- |
| 1    | 415          | 13.83          | 67.06  | 98.39  | 126.99 | 0                       |
| 4    | 409          | 13.63          | 295.77 | 391.30 | 408.27 | 0                       |
| 8    | 390          | 13.00          | 599.98 | 796.49 | 896.10 | 0                       |

各档结束时主动取消的在途请求分别为 1、4、8，单独统计，不混入成功延迟或故障数。吞吐以实际经过时间计算，不把配置时长当成实际时长；所有计入延迟的响应均通过订单、店铺、金额及公开字段校验。

Docker 的压测前后快照只能说明当时资源状态，不能当作负载期间 CPU 峰值。因此另导出了同一时间窗口的 Prometheus 历史采样：7 个采样点中进程 CPU 最高约 99.3%，JVM 使用量最高约 144.21 MiB；连接池 active/pending/timeout 的这些采样点均为 0。瞬时采样为 0 不表示没有执行 SQL，也不能证明连接池永远没有等待。

观察到并发提高时吞吐没有提高、P95 增长，并伴随接近 1 CPU 配额的采样。下一步可用 JFR 分析认证、业务与 JDBC 的具体耗时；当前证据不能把热点归因于某一个方法。这是本机短时基线，不能作为生产容量上限、长期稳定性或任何模型速度的结论。

## 当前基线：2026-10-30 会话登录版本

本次使用 [提交 6c6aa65 的成功 CI](https://github.com/wjinhu98-tech/supportflow/actions/runs/41055715917) 产物，先核对 GitHub artifact 摘要、包内 ZIP 摘要和全部文件校验值，再从该 JAR、Linux 原生库与前端成品构建镜像。只更新原有 LAB 的前后端镜像，数据库卷、凭据与 5000 笔种子订单保留。运行时权限检查和 readiness 均通过。

完整数值、镜像 ID、JAR 摘要、压测脚本摘要及监控序列见 [机器可读验收记录](benchmarks/session-order-read-2026-10-30.json)。应用源码为 `6c6aa65532bf4812dec50b8cbadbed67b5fc6e8f`；测量脚本在该版本基础上只增加了显式 10000 次请求上限，默认值仍为 1000。

环境：Windows x64、i7-9750H、12 逻辑 CPU、约 31.86 GiB 宿主内存；Docker 29.7.2 的 Linux VM 有 12 CPU、约 15.55 GiB 内存。Node 24.15.0、容器 Java 21.0.12.1、MySQL 8.0.45。后端 1 CPU / 768 MiB，MySQL 1 CPU / 768 MiB，Nginx 0.5 CPU / 128 MiB；Hikari 最大 8、最小 2。

工作负载是经 Nginx 读取固定订单 `GET /api/v1/shops/100/orders/LAB-000001`。先以数据库账号登录一次，测量窗口内共享该买家会话，仍执行每次请求的账号有效性、权限和订单归属检查；登录及 BCrypt 校验在窗口外。每档配置 30 秒、最多 10000 次请求，以下三档均因时限结束，没有提前达到请求上限。预热最多 20 次 / 3 秒，自动重试为零；未调用 DeepSeek、订单或售后写接口。

| 并发 | 校验成功数 | 实际时长 s | 成功吞吐 req/s | P50 ms | P95 ms | P99 ms | HTTP / 超时 / 网络 / 校验错误 |
| ---- | ---------- | ---------- | -------------- | ------ | ------ | ------ | ---------------------------- |
| 1 | 4701 | 30.023 | 156.58 | 4.67 | 9.98 | 47.47 | 0 |
| 4 | 8334 | 30.044 | 277.39 | 4.69 | 70.66 | 80.10 | 0 |
| 8 | 8645 | 30.025 | 287.92 | 6.37 | 89.66 | 103.21 | 0 |

三档时限结束时主动取消的在途请求分别为 1、4、8，单独计数，未混入成功延迟或已完成请求的错误数。成功数共 21680；报告中的 `COMPLETED` 表示测量流程结束，零错误结论来自逐项核对错误、超时、网络、正文与环境校验计数。

同期 Prometheus 每 15 秒采样，相关指标各得到 7 个点：进程 CPU 采样最大约 98.65%，JVM 已用内存最大约 157.19 MiB；连接池 active 最大 2、pending 最大 0，timeout 累计计数保持 0。查询窗口包含预热和前后 Docker 快照，这些是采样最大值，不能当作连续峰值或归到某一并发档；pending 采样为零也不能证明从未发生短暂等待。

当前结果显示，并发 4 增至 8 时吞吐增长较小、尾延迟继续上升，并伴随接近后端 1 CPU 配额的采样。具体热点需要 JFR 等剖析后确认。旧版使用每次请求 Basic/BCrypt，版本和认证开销都不同，两次结果不能直接换算为某次优化的提升倍数。本节是单机、短时、固定订单和同一会话的读取基线，不能代表全站容量、多用户公平性、Agent 或真实模型性能。

本次本地镜像标签为 `supportflow-lab-backend:capacity-6c6aa65532bf` 和 `supportflow-lab-frontend:capacity-6c6aa65532bf`，不可当作远程镜像仓库地址。已有 V22 LAB 的情况下，先下载上述 CI 交付包并完成摘要与文件校验，在交付包解压目录构建对应标签：

```powershell
.\supportflow.ps1 Verify
docker build --platform linux/amd64 -f deploy/backend.Dockerfile -t supportflow-lab-backend:capacity-6c6aa65532bf .
docker build --platform linux/amd64 -f deploy/frontend.Dockerfile -t supportflow-lab-frontend:capacity-6c6aa65532bf .
```

回到源码仓库，在 `.local/capacity-ci-images.yml` 只覆盖两个运行镜像：

```yaml
services:
  backend:
    image: supportflow-lab-backend:capacity-6c6aa65532bf
  frontend:
    image: supportflow-lab-frontend:capacity-6c6aa65532bf
```

再使用两个 Compose 文件更新 LAB，沿用源码部署的本地模型与现有凭据；不运行初始化或重置数据的步骤：

```powershell
docker compose -f deploy/compose.yml -f .local/capacity-ci-images.yml up -d --no-deps --no-build --pull never backend frontend
```

随后执行下一节的压测和监控命令。脚本会先核对固定实验标识、买家身份和订单字段，条件不符时拒绝测量。

## 如何复现验证

```powershell
node scripts/check-deployment-lab.mjs
node scripts/capacity-lab.mjs --seconds=30 --concurrency=1,4,8 --max-requests=10000 --docker-stats
node scripts/capacity-monitoring-lab.mjs
node scripts/fault-check-lab.mjs
```

第一条检查页面、身份、CSRF 转发、指标隐私和 Prometheus；业务探测包含一次明确的无会话持久化查单 POST，登录与退出 POST 用于准备和清理认证会话，没有订单或售后写入。第二条先使用实验买家账号完成一次会话登录，测量阶段只请求固定隔离端口的订单 GET；不接受生产目标或任意 URL，不重试。默认每档最多 1000 次，可显式设置到 10000 次；每档在达到时限、请求上限或安全停止条件时结束，报告按实际经过时间计算吞吐。登录和 BCrypt 校验不计入测量窗口。第三条导出最近一次负载报告对应的监控历史，避免用前后快照代替负载证据。

最后一条会明确停止并恢复本实验的 MySQL/后端，不能与容量测量同时运行。它新建一轮实验会话用于验证持久化，不申请售后、不改订单、不调用模型、不删除数据卷。2026-10-26 的历史演练已观察到：

- 数据库停止后 readiness 返回 503/DOWN，liveness 仍为 200/UP。
- 数据库恢复后 readiness 回到 UP，同一会话的消息身份和内容仍可读取。
- 后端停止后 Prometheus `up=0`；抓取失败持续达到规则条件后，`SupportFlowLabScrapeUnavailable` 实际进入 firing。
- 后端恢复后 readiness、`up` 恢复，告警实际解除；会话与订单状态/金额保持一致。

这些恢复时间包含探测和采集间隔，不是恢复 SLA。告警目前只在 Prometheus Alerts 可见，没有接外部消息通知。

原始 JSON 只写入 `.local/capacity-results` 与 `.local/deployment-checks` 的新文件，不覆盖或批量清理历史报告；凭据、认证头和响应原文不进入报告。公开文档只汇总本次的测量条件和结果。

## 代码职责和验证记录

| 文件/类                           | 输入与输出、调用关系                                                                  |
| --------------------------------- | ------------------------------------------------------------------------------------- |
| `deploy/compose.yml`              | 组织迁移、授权、种子、运行服务；容器实际使用不同凭据                                  |
| `LabDeploymentTool`               | 固定 prepare/migrate/seed/verify 命令；生成私有凭据或操作固定实验库，无任意 JDBC 目标 |
| `OperationsSecurityConfiguration` | 管理 HTTP 请求 → 独立认证与只读规则；仅 local/lab 环境启用                            |
| `DeepSeekCallMetrics`             | 执行器的固定操作、结果、耗时和 Semaphore → Micrometer 指标                            |
| `capacity-lab.mjs`                | 固定读取与有界并发 → 分类、成功延迟、真实吞吐与 JSON 报告                             |
| `capacity-monitoring-lab.mjs`     | 已完成负载报告时间窗 → 对应 Prometheus 采样                                           |
| `fault-check-lab.mjs`             | 固定实验容器和一轮测试会话 → 故障、告警、持久化恢复记录                               |

历史版本验收记录（2026-10-26）：403 项单元测试、253 项集成测试通过；最后补齐环境安全边界后，全部 404 项单元测试和 4 项相关运维集成再次通过，并重新打包。Node 部署模板/压测脚本 26 项检查通过；Prometheus 配置和规则通过容器内 `promtool` 校验。实际镜像构建、最小权限检查、部署验收、容量和故障恢复均已运行通过。日常环境另做只读检查，原有 4 篇知识仍保持发布状态。

选择单体 Compose 可以直接验证现有系统的资源和失败边界。扩展到跨实例、生产身份与外部系统之前，需要独立设计和验收；本轮没有通过堆叠微服务或中间件来代替测量。
