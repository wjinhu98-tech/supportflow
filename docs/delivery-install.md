# 版本化交付包与本地安装

交付包面向 Linux/amd64 容器和独立教学环境。使用 Docker Desktop 的 Linux containers 模式或 Linux Docker Engine，以及支持 `service_completed_successfully` 的 Docker Compose v2。Windows 启动脚本使用系统 PowerShell；安装者不需要 Maven、npm、Node、Java 或源码。首次启动会导入实验订单、店铺、商品及数据库登录账号，数据库始终为 `supportflow_lab`。买家、商家、运维登录名分别为 `lab-buyer`、`lab-merchant`、`lab-ops`；随机密码只在安装机生成。

## 选择与核验制品

CI 的交付作业等待 backend/live、frontend/mock、automation 三组检查成功后才生成交付包，不创建公开 Release、推送镜像或打标签。常规 push 生成精简制品包；手动运行工作流并选择 `include_images=true` 生成完整镜像包。文件名为 `<pom版本>-<提交前12位>-<构建时间>-<随机后缀>.zip`，每次创建新文件，不覆盖已有输出。`manifest.json` 记录完整提交、UTC 构建时间、`linux/amd64` 平台、载荷字节数、每个载荷文件的 SHA-256、镜像标签/ID和检查结果；`validation.json` 保存本次作业的结果证明。本地包标记 `sourceState=local-preview`，不以提交 SHA 暗示工作区干净；没有检查证明时明确标记未验证，不能据此声称测试通过。

下载 ZIP 和同名 `.zip.sha256`。先对照校验文件的 64 位 SHA-256，再解压到新的专用目录；不要覆盖旧安装目录或把 `.local` 复制进交付包。

```powershell
Get-FileHash -LiteralPath .\<交付包文件名>.zip -Algorithm SHA256
# 在 Windows 资源管理器中将 ZIP 解压到一个新目录，然后进入该目录。
.\supportflow.ps1 Verify
```

SHA-256用于发现损坏、下载差异和文件变更，不构成发布者签名。先通过可信渠道获取制品及校验文件。脚本会校验包内文件，并拒绝符号链接、junction 和越界路径。

`--with-images` 完整包包含 `supportflow-images.tar.gz`，其中同时保存后端、前端、`mysql:8.0.45` 和 `prom/prometheus:v3.2.1` 四个镜像。打包器流式校验 docker-save manifest、内容摘要和 `linux/amd64` config；同时支持传统 config ID 与 Docker 29 的 OCI 索引 ID，核对标签与索引到 config 的关联。加载后脚本再次检查 Docker 返回的实际 ID；该包可以在已有 Docker/Compose 的断网机器启动。BGE 权重不在包内，默认关闭 RAG；规则与本地数据流程可以离线演示，外部付费模型始终关闭。

不带镜像的制品包包含已经构建好的 JAR、Linux ONNX 动态库和前端静态文件。`BuildImages` 使用这些文件构建应用镜像，并下载固定版本的 Temurin、Nginx、MySQL、Prometheus 公共基础/伴随镜像，因此这个安装路线第一次需要互联网。它不会下载 Maven/npm 依赖，也不重新编译前后端。镜像标签固定版本并不等同不可变 digest；完整 CI 镜像包会另行记录实际镜像 ID。

## Windows 安装顺序

以下命令均在解压目录执行。有完整镜像包时：

```powershell
.\supportflow.ps1 LoadImages
.\supportflow.ps1 Prepare
.\supportflow.ps1 Start
.\supportflow.ps1 VerifyRuntime
```

只有制品包时，将第一行改为 `.\supportflow.ps1 BuildImages`，其余顺序相同。`Prepare` 在无网络的一次性 Java 容器中调用 JAR 内既有 `LabDeploymentTool prepare`，只挂载本机 `.local`，在 `.local/deployment-lab` 生成随机凭证。完整凭证目录再次运行时保留原值；目录不完整时停止，需人工检查，不自动删除或重置。启动服务以非 root 用户运行；一次性凭证准备容器在 Windows 以 root 写入 bind mount，在 Linux 以安装者 UID/GID 运行并将宿主 `.local` 设为 0700。

读取 `.local/deployment-lab/login.txt` 在本机登录。该目录包含明文密码和 bcrypt 登录哈希，只用于这次本机部署，不提交、截图或上传。后端通过 `configtree:/run/secrets/` 读取应用数据库密码及运维哈希；迁移、授权、业务账号导入和运行时的 secret 挂载仍各自分开。

`Start` 按 `mysql → migrate → grant → seed → seed-accounts → seed-shops → seed-products → seed-product-images → backend` 顺序启动。账号导入保留已有密码和禁用状态，并为实验商家补充 `CHAT_SUPPORT`；不会写入日常数据库或创建运维业务账号。一次性任务成功退出是正常状态。`Status` 查看容器状态；`VerifyRuntime` 验证数据库权限边界，它不能代替浏览器业务验收。

| 页面 | 地址 |
| --- | --- |
| 买家商城与聊天 | `http://127.0.0.1:18090/?shopId=100` |
| 商家工作台 | `http://127.0.0.1:18090/merchant` |
| 后端 readiness | `http://127.0.0.1:18091/actuator/health/readiness` |
| Prometheus | `http://127.0.0.1:19090` |

MySQL 和业务 API 8080 不映射宿主机端口。管理端点不经 Nginx 转发，只有上述三个回环端口。启动脚本使用 `--pull never`，缺少镜像时停止，不会在离线启动中暗中联网补镜像。

## 演示与停止

先使用买家账号查看店铺 100 的订单 `LAB-000001`，打开聊天窗口；再使用商家账号打开工作台处理同一店铺的人工接待、历史消息与知识。商品图示例在店铺 102/103，实验商家没有这两个店铺的授权。可在保留数据的情况下停止后重新启动，核对会话与消息。涉及售后的流程只使用隔离实验订单和模拟执行器。RAG 未启用时不能把规则回答描述成向量召回或真实大模型结果。

可选 Node 22.12+ 验收脚本一并提供：`node scripts/check-shop-windows-lab.mjs`、`node scripts/check-human-support-lab.mjs`。它们只访问固定回环教学环境，但人工接待脚本会创建隔离会话记录；不要求安装 Node 才能运行应用。

```powershell
.\supportflow.ps1 Status
.\supportflow.ps1 Stop
```

停止保留数据库卷和本机凭证；脚本不提供删除卷或清空数据命令。升级请使用新目录核验制品，人工规划凭证与数据库迁移，不能通过覆盖旧目录替代迁移。

需要与已有实验环境并存验收时，使用另一个新解压目录，所有涉及 Compose 的动作均加 `-ProjectName supportflow-delivery-smoke -FrontendPort 18190 -ManagementPort 18191 -PrometheusPort 19190`。项目名确保网络与数据卷分离；端口始终绑定 `127.0.0.1`。停止时也传相同参数，仅停止该验收项目。默认安装参数保持 `supportflow-lab` 和原端口，不能覆盖正在使用的安装目录。

## 单独准备与启用 RAG

模型校验脚本使用 .NET 的现代哈希 API；下载或 `EnableRag` 需要 PowerShell 7（`pwsh`），基础安装启动可以使用 Windows PowerShell。有网机器可在解压目录运行 `pwsh -File scripts/setup-rag-model.ps1`。固定来源为 Hugging Face `Xenova/bge-small-zh-v1.5` 的 revision `75c43b069aac4d136ba6bc1122f995fedcfd2781`，模型是 MIT 许可的量化 CPU ONNX 文件。`model_quantized.onnx` SHA-256 必须为 `15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc`；词表按 pinned Git blob SHA-1 `ca4f9781030019ab9b253c6dcb8c7878b6dc87a5` 核验。

断网机器需事先从该固定版本取得并校验 `model_quantized.onnx` 和 `vocab.txt`，复制到本机 `.local/models/bge-small-zh-v1.5/`。之后执行 `.\supportflow.ps1 EnableRag`：脚本要求两个文件已经存在、运行同一校验逻辑，通过后才应用只读模型挂载与 RAG override。缺文件或校验失败均保持关闭。没有提供或执行模型下载的 CI 检查不能被称为 RAG 运行验收。该设置只启用本地向量模型，DeepSeek 继续关闭。

## Linux 手动安装

在新解压目录先按 `SHA256SUMS` 核验文件。完整镜像包运行 `docker load --input supportflow-images.tar.gz`，按 `manifest.json` 核对四个镜像 ID。仅制品包时，用 `.env` 中的应用标签分别通过 `deploy/backend.Dockerfile` 与 `deploy/frontend.Dockerfile` 从当前目录构建，并拉取上述 MySQL/Prometheus 镜像。凭证准备使用后端镜像调用同一 Java 类，例如：

```sh
# 从已核验的 .env 读取 SUPPORTFLOW_BACKEND_IMAGE；该文件只有两个公开镜像标签。
set -a
. ./.env
set +a
mkdir -p .local
chmod 700 .local
docker run --rm --network none --user "$(id -u):$(id -g)" --entrypoint java \
  --mount "type=bind,src=$PWD/.local,dst=/bundle/.local" --workdir /bundle "$SUPPORTFLOW_BACKEND_IMAGE" \
  -Dloader.main=dev.supportflow.deployment.LabDeploymentTool -cp /app/app.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher prepare
docker compose --env-file .env -f deploy/compose.yml config --quiet
docker compose --env-file .env -f deploy/compose.yml up -d --pull never
docker compose --env-file .env -f deploy/compose.yml ps -a
```

`.local` 的 0700 父目录保护宿主机上的凭证，生成文件由安装者持有。保留生成文件的容器可读权限：Docker 将所选文件分别只读绑定为 secrets 后，运行用户 UID 10001 / 999 才能读取各自获准的文件。不要把所有文件改为宿主安装者独占的 0600 再导致服务读取失败。Windows 目录权限采用 Windows ACL，不承诺 POSIX 模式。模型文件同样必须先独立校验后，显式增加 `-f deploy/compose.rag.yml` 启动；不要在缺权重时启用 override。

## 从仓库生成交付包

构建者使用 Node 22+、JDK 21、Maven 和前端依赖；先完成实际检查，再打包。打包器本身不运行构建、Git、Docker 或服务，不扫描整个仓库，也不读取 `.local`。输入固定为 `pom.xml` 的项目版本、`target/supportflow-backend-<版本>.jar`、两份 Linux ONNX `.so`、`frontend/dist/`、明确列出的公共部署文件/脚本/本文。禁止链接、越界路径、未知前端资产类型、已有输出覆盖；`.git`、源码、依赖缓存、环境凭证、HTTP 私有配置与测试日志不进入包。

```sh
node --test scripts/build-delivery.test.mjs
node scripts/build-delivery.mjs --sha <完整提交SHA> --built-at <UTC的ISO时间>
# CI还先生成四个镜像及经过核验的 docker save archive，然后：
node scripts/build-delivery.mjs --sha <完整提交SHA> --built-at <UTC的ISO时间> --with-images
```

可选证明固定在 `target/delivery-input/validation.json`：`sourceSha` 与当前 SHA 一致，`checks` 恰好包含 `backend-live`、`frontend-mock`、`automation`，每项是 `name/status/command/exitCode`。`passed` 必须是实际命令退出 0，`failed` 对应非零，`not-run` 对应 null；不得手填通过。CI还将四个确切标签及 `docker image inspect` 返回的 `sha256:<64位>` ID 写入固定 `target/delivery-input/images.json`，格式为 `sourceSha/images:[{tag,id}]`，并把对应 `docker save` 的 gzip 输出放在 `target/delivery-input/supportflow-images.tar.gz`。打包器对其标签和 config 哈希作交叉验证。镜像压缩包上限 2 GiB、总输入载荷上限 3 GiB；经典 ZIP 总体必须小于 4 GiB。超过限制停止并保留现场文件，不产生可用交付包声明。

结果位于新的 `target/delivery/<版本>-<SHA前12位>-<唯一后缀>/` 暂存目录及同名 ZIP / SHA-256 校验文件。固定 UTC 时间和相同输入产生一致的 ZIP 字节；外部文件名中的唯一后缀用于避免覆盖。实际通过证据以本次 CI 作业和 `validation.json` 为准。
