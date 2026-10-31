# 本机独立部署实验环境

这套 Compose 使用独立项目 `supportflow-lab`、独立 MySQL 容器与命名卷，数据库固定为 `supportflow_lab`。它不连接宿主机的 `supportflow_rebuild`，不导入日常订单或日常账号。应用使用 `lab` profile，关闭启动时 Flyway，由一次性迁移服务先完成建表；该 profile 显式关闭 DeepSeek 并将 API Key 设为空，服务不挂载真实模型 Key。网络配置不代表完全阻断出站流量。

这是学习和容量测量的单机基线，不是生产部署、微服务拆分或高可用方案。实际构建、启动、业务验收和容量结果需要单独验证，不能从配置上推断吞吐或延迟。

本文用于**从源码构建的 Compose 环境**，默认启用本地 BGE 检索并只读挂载已校验的模型文件。下载 GitHub 交付包后，请使用[交付安装说明](../docs/delivery-install.md)：交付包默认关闭 RAG，安装者只需要 Docker/Compose，可另行准备模型后启用。两条路径都关闭 DeepSeek，不挂载真实模型 Key。

当前会话版本的固定订单读取实测、确切 CI 构建来源与镜像复现步骤见[部署和容量记录](../docs/deployment-and-capacity.md#当前基线2026-10-30-会话登录版本)。其中的镜像覆盖文件仅用于在已有 V22 LAB 上固定这次应用构建。

## 服务与访问边界

| 服务                         | 宿主机入口                        | 用途与上限                                                              |
| ---------------------------- | --------------------------------- | ----------------------------------------------------------------------- |
| `frontend`                   | `http://127.0.0.1:18090`          | 非 root Nginx，容器 8080；0.5 CPU / 128 MiB                             |
| `backend`                    | 仅管理端 `http://127.0.0.1:18091` | Java 21，主 API 8080 仅容器网络可见；1 CPU / 768 MiB                    |
| `mysql`                      | 不映射宿主端口                    | MySQL 8.0.45，独立命名卷；1 CPU / 768 MiB                               |
| `prometheus`                 | `http://127.0.0.1:19090`          | 拉取后端指标，保留最多 24 小时/128 MB 的实验时序数据；0.5 CPU / 256 MiB |
| `migrate` / `grant` / `seed` / `seed-accounts` | 无入口，一次性执行 | 顺序完成迁移、精确授权、实验数据和账号导入；不随业务请求运行 |

买家入口为 `http://127.0.0.1:18090/?shopId=100`，商家入口为 `http://127.0.0.1:18090/merchant`，由同一 SPA 提供。Nginx 只代理 `/api/`，保留认证头、Cookie、CSRF 与当前 Host，拒绝 `/actuator` 和其子路径，不自动重试上游写请求。所有 Nginx 响应包含 `X-SupportFlow-Environment: isolated-lab`，压测工具应先核实这个标记再运行。

Nginx access log 仅记录方法、状态码、请求时间与上游状态，不记录 URI、query、认证头、IP 或消息内容。代理使用 Docker DNS `127.0.0.11` 和固定后端变量，最多缓存解析 10 秒，后端容器重建换 IP 后可重新解析；这不代表切换窗口内没有失败，请求仍不会自动重放。

管理端与网页入口分开。`/actuator/health/liveness` 和 `/actuator/health/readiness` 只公开整体状态，不公开组件或详情；readiness 包含数据库检查。指标等管理接口要求独立 `lab-ops` 认证，买家与商家账号不能代替运维账号。Prometheus UI 本身只绑定本机回环，本轮没有 Grafana、外部通知或公网认证代理。

`database` 保持 `internal=true`；`web` 和 `monitoring` 使用普通 bridge，供 Docker Desktop 正常发布上述回环端口。本机 Docker 29 实测中，服务仅连接 internal 网络时虽然存在 PortBindings，宿主端口仍不可达，因此没有将三个网络全部设为 internal。前端只加入 `web`，Prometheus 只加入 `monitoring`，两者均不加入数据库网络；后端加入三者，MySQL 及初始化任务只加入 `database`。数据库和后端主 API 8080 都不映射宿主端口，发布端口仍严格绑定 `127.0.0.1`。普通 bridge 可能提供出站路由，不能把它描述为完全禁止外网；模型关闭和空 Key 由 `lab` 配置保证。不要把这些本机端口改成公网监听后就当作生产安全方案。

## 构建与首次启动

前提是 Docker Desktop 已启动并支持 Linux 容器、Docker Compose 的健康/成功依赖条件，以及本机 Java 21、Maven 和 PowerShell 7（`pwsh`）。在仓库根目录先准备并校验本地 BGE 模型，再完成统一验证并生成最终 Spring Boot jar：

```powershell
pwsh -File scripts/setup-rag-model.ps1
mvn verify
```

模型脚本按固定 revision 和校验值准备 `.local/models/bge-small-zh-v1.5/model_quantized.onnx` 与 `vocab.txt`；缺文件时下载，已有文件只做校验，失败时保留原文件。它不调用 DeepSeek。源码 Compose 显式启用本地 RAG，缺少或校验不通过的模型会阻止后端启动；全量集成测试也使用这些文件。后端镜像只复制 `target/supportflow-backend-0.1.0-SNAPSHOT.jar`，不在镜像构建时重新运行 Maven。

在仓库根目录的 PowerShell 中准备本实验专用凭证：

```powershell
java '-Dloader.main=dev.supportflow.deployment.LabDeploymentTool' -cp target/supportflow-backend-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher prepare
```

该命令只在 `.local/deployment-lab` 生成新的实验密码、登录哈希和私有 `login.txt`，不覆盖既有文件，也不把真实值打印到控制台。目录已存在且不完整时，应先查明原因，不要批量删除或覆盖。账号为 `lab-buyer`、`lab-merchant`、`lab-ops`；原始登录密码只在私有文件中查看，不复制到 Git、Compose、终端命令参数或截图。

生成内容中，三个数据库密码使用 24–128 位字母、数字、下划线或连字符。这个边界让初始化脚本可以安全构造 SQL 和 MySQL 客户端配置；不要手工换成包含引号、换行或反斜杠的数据库密码。买家/商家原始密码文件及 `login.txt` 不挂载进任何服务。

确认准备完成后：

```powershell
docker compose -f deploy/compose.yml config --quiet
docker compose -f deploy/compose.yml build
docker compose -f deploy/compose.yml up -d
docker compose -f deploy/compose.yml ps -a
```

首次前端镜像构建会按 lockfile 安装依赖，并用新目录及 `vite build --emptyOutDir false` 打包，避免递归清理旧输出。镜像构建上下文采用默认拒绝的 `.dockerignore`；只放行最终 jar、前端所需源码/清单和镜像配置，不发送 `.local`、工作站 `node_modules`、Git 数据或环境文件。Node 仅用于构建，最终前端镜像只有 Nginx 与静态文件。

当前固定标签为 `mysql:8.0.45`、`eclipse-temurin:21.0.12.1_1-jre-jammy`、`node:22.15.0-alpine3.21`、`nginxinc/nginx-unprivileged:1.28.0-alpine-slim` 和 `prom/prometheus:v3.2.1`。标签并不是不可变 digest；复现实验应记录实际镜像 ID/digest、提交版本、构建日期与宿主资源，升级镜像后重新验证。固定版本不代表已经做过漏洞评估。

## 为什么分成这些初始化步骤

```text
mysql 首次启动
  → init-lab.sh：只建 supportflow_lab、lab_app、lab_migrator 和初始库权限
  → migrate：LabDeploymentTool migrate，迁移账号运行 Flyway V1–V22
  → grant：root 仅授应用所需表/列写权限，此时业务表已经存在
  → seed：LabDeploymentTool seed，迁移账号写入实验数据与商家关系
  → seed-accounts：导入缺失的 lab-buyer / lab-merchant，保留已有状态及密码，并为有效实验商家补充人工接待权限
  → backend：lab_app 启动，Flyway=false，readiness 检查通过
      ├─ frontend：同源网页与 /api 反向代理
      └─ prometheus：用独立运维账号抓取管理端
```

`migrate`、`seed` 和 `seed-accounts` 固定访问容器地址 `mysql:3306/supportflow_lab`，不读取本机日常配置。前两者只挂载迁移密码文件；`seed-accounts` 额外挂载既有买家与商家 bcrypt 哈希，只插入缺失账号，不更新已有密码、禁用状态或店铺授权。它还为已有有效商家员工绑定补充 `CHAT_SUPPORT`；首次补充会提升账号安全版本，使旧会话在后续请求中失效，重复执行不重复提升版本。它不会创建 `lab-ops` 业务账号。`seed` 写入店铺 100、买家 200 的 5000 笔实验订单及所需商家映射；`LAB-000001` 为已发货、199.90 CNY 的实验订单。它不用于回退或重置日常业务数据。

MySQL 首次 init 只运行在新数据卷。应用最初只有新库 `SELECT`，迁移账号有该库的 `SELECT, INSERT, UPDATE, CREATE, INDEX, ALTER`。`grant` 在迁移成功后才授表/列写权限，避免向不存在的表授权；应用只有订单 `status/received_at` 列更新、收货审计插入、售后/执行/会话/知识表相应写权限，以及 V18 买家订单删除记录、V19 知识导入记录和 V21 持久化向量的插入权限。V22 人工接待与处理权表可读写，人工消息表只能查询和插入，不能修改历史消息。V20 账号表只能查询，应用不能创建、修改或删除账号；不获 DELETE、DDL、GRANT OPTION 或商家授权关系写权限。删除、导入和向量记录不可修改，原订单保留；升级时先运行 `migrate`、`grant` 和 `seed-accounts`，再更新后端。初始库级权限转义数据库名中的下划线，避免 MySQL 的库权限通配语义扩大匹配范围。

已有实验卷升级时，在新镜像构建完成、MySQL 正常运行后依次执行：

```powershell
docker compose -f deploy/compose.yml run --rm --no-deps migrate
docker compose -f deploy/compose.yml run --rm --no-deps grant
docker compose -f deploy/compose.yml run --rm --no-deps seed-accounts
docker compose -f deploy/compose.yml up -d backend frontend prometheus
docker compose -f deploy/compose.yml exec backend java '-Dloader.main=dev.supportflow.deployment.LabDeploymentTool' -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher verify
```

应用固定使用数据库账号认证，网页通过带 CSRF 的登录换取 HttpOnly、SameSite=Lax 的服务器会话；业务 Basic 已关闭，运维抓取仍使用独立 Basic。会话闲置 30 分钟过期，后端重启后需重新登录。账号禁用、密码修改或安全版本提升会在后续请求中撤销旧会话。按用户请求限流及本地知识向量持久化在 Compose 中启用；向量只保存本地模型计算的结果，不包含文档正文，也不触发外部模型调用。

根数据库密码只挂载给 MySQL 和一次性 `grant`；运行中的应用只挂载应用数据库密码及运维哈希，买家、商家哈希只在 `seed-accounts` 导入时挂载。Prometheus 只拿到运维抓取用的原始密码，不能据此登录数据库。Compose 本机 secret 是文件挂载边界，不等于外部密钥管理系统；宿主机私有文件仍需访问权限保护。

脚本以子 shell 执行，兼容官方 MySQL 入口对 `.sh` 的执行或 source 行为。数据库凭证不出现在命令行，临时客户端配置固定为 `/tmp/supportflow-lab-root-client.cnf`、权限 600，完成时只删除这一明确文件。脚本须保持 LF 行尾。

## 检查、监控与停止

查看启动顺序与失败原因：

```powershell
docker compose -f deploy/compose.yml logs --tail=100 mysql migrate grant seed seed-accounts backend
(Invoke-WebRequest http://127.0.0.1:18090/healthz).Headers['X-SupportFlow-Environment']
Invoke-RestMethod http://127.0.0.1:18091/actuator/health/readiness
```

`migrate`、`grant`、`seed`、`seed-accounts` 成功退出是正常状态；失败时先检查对应日志，不要跳过授权、禁用归属校验或改用 root 运行后端。不要把私有文件内容粘贴进诊断输出。后端健康检查直接在容器内读取 readiness HTTP，不依赖外部 curl，也不需要挂载运维原始密码。

打开 Prometheus 的 Targets 页面确认 `supportflow-backend` 为 UP，查询 `up{job="supportflow-backend"}`。可以查看实际存在的 JVM、HTTP 请求与数据库连接池指标，再结合负载工具的错误率和延迟报告判断瓶颈。`SupportFlowLabScrapeUnavailable` 仅在抓取失败持续一分钟后出现在实验 Alerts 页面，没有外部通知，也不是生产 SLO。

如需手动检查受保护指标，可用交互提示输入运维密码，避免写入命令历史：

```powershell
curl.exe --user lab-ops http://127.0.0.1:18091/actuator/prometheus
```

停止时保留实验数据：

```powershell
docker compose -f deploy/compose.yml stop
```

命名卷会保留实验订单和时序数据，重新 `up -d` 不应被当作恢复空库的方法。不要删除卷、批量清理容器资源或批量删除凭证目录来排错；确实需要另建一套空环境时，先明确保留哪些数据，再单独设计迁移或重建步骤。

恢复验证应在不删除数据的情况下执行 `docker compose -f deploy/compose.yml restart backend`，等待 readiness 恢复后重新连接买家账号，确认之前创建的会话仍可读取、订单与已提交记录保持一致，并确认 Prometheus 的 `up` 恢复。重启后端会丢失内存认证状态，所以需要重新连接；数据库持久化记录不应因此消失。验证结果应记录在本轮验收中，不能仅凭命名卷存在就宣称恢复已通过。

后端、数据库和监控资源限制只是本次可记录的基线。容量报告应注明这些上限、宿主 CPU/内存、Docker Desktop 分配、并发、持续时间、样本量、失败和瓶颈；不要把短时本机压测描述成生产容量。

## 不启动容器的模板检查

```powershell
node --test deploy/templates.test.mjs
```

该检查只读公开模板，检查端口隔离、secret 挂载、授权边界和代理防护，不读 `.local`，不启动 Docker，不代替 `docker compose config`、镜像构建、实际权限验证或浏览器业务验收。
