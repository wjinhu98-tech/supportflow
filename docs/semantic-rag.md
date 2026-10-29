# 本地中文语义检索与公开参考资料

本轮在原有词法检索、当前店铺发布快照和来源引用之上增加真实的中文句向量。知识回答保留完整适用条件，以摘录方式展示；向量计算在本机进行。经用户于2026-10-29批准，聊天中的公开参考回答可额外将最多两篇命中摘要及本次知识问题交给现有DeepSeek服务筛选来源。商家录入的资料留在本地，账号、订单记录和聊天历史不进入这一步的请求正文。

## 实现的数据流

```text
问题 + 当前店铺
  → 订单/金额/退款等受保护业务先走原规则
  → SQL只读当前店铺已发布版本（不读草稿、其他店铺文章）
  → 加入当前店铺适用的公开参考摘要
  → 原关键词检索 + 本地BGE中文句向量
  → 余弦相似度、置信度及来源间差距门限
  → 两路排名融合，最多两条来源
  → 聊天回答且全部为规范公开摘要时，DeepSeek只选择来源ID
  → Java保留完整原文条件、来源ID及版本
  → 既有聊天与历史证据展示
```

资料分成270个Unicode码点的检索片段，重叠32个码点；标题最多80个码点，BERT输入最多384个token。片段只用于定位，回答的来源仍保留完整版本正文，不把条件段落丢掉。超过300字符的问题不被截断成另一条语义问题，而沿用原词法检索。

每次读取当前发布集合后才参与检索。向量缓存键包括店铺、来源ID、版本、完整内容哈希和片段号；同一ID更新正文也不会复用旧向量。下线文章不在候选集中，即使其旧向量尚留在有界缓存中，也不能被返回。默认最多4096个片段向量；处理时间门限在片段间检查，不把它声称为原生推理的强制中断。向量繁忙、异常或构建超时会记录降级指标并沿用可信的词法结果。

## 模型与环境

- 原模型：[BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)，MIT许可。
- ONNX转换：[Xenova/bge-small-zh-v1.5](https://huggingface.co/Xenova/bge-small-zh-v1.5)，固定提交 `75c43b069aac4d136ba6bc1122f995fedcfd2781`。
- 24,010,842字节的量化权重；512维CLS向量并L2归一化。不是哈希向量或手工同义词冒充语义模型。
- 模型SHA256：`15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc`。
- 词表SHA256：`45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c`。分词遵循该固定模型的大小写保留配置。
- Java使用[ONNX Runtime](https://onnxruntime.ai/docs/get-started/with-java)。本机JDK21.0.1自带旧版C++运行库，1.23.2实际加载失败；本轮验证使用1.20.0，不改系统或JDK的DLL。相关上游记录见[Windows Java运行库兼容问题](https://github.com/microsoft/onnxruntime/issues/23969)。模型仅允许加载已校验的固定本地文件，不接受用户上传模型。

首次准备：

```powershell
cd D:\project\supportflow-rebuild
.\scripts\setup-rag-model.ps1
```

权重和词表保存在 `.local/models/bge-small-zh-v1.5/`，被Git和Docker构建上下文忽略。应用启动不自动下载。`local`默认启用；可设置 `SUPPORTFLOW_RAG_ENABLED=false` 关闭。模型目录由 `SUPPORTFLOW_RAG_MODEL_DIRECTORY` 配置。启用时缺少文件或校验失败会明确停止启动，不伪装成语义检索正常。

Docker后端只读挂载模型目录。`mvn package` 另行解包Linux x64原生库到 `target/rag-native`，镜像复制到只读的 `/app/native`；保留原 `/tmp` 的 `noexec`，没有为模型关闭容器安全限制。本次镜像原生库为Linux x64版本。

## DeepSeek公开来源筛选

这一环节复用现有DeepSeek密钥和调用保护，不需要另填密钥。`local`默认开启，可设置 `SUPPORTFLOW_RAG_PUBLIC_SELECTION_ENABLED=false` 关闭；同时要求本地RAG和DeepSeek本身已启用。`lab`默认关闭此功能，仍以本地检索和摘录回答。

发送前，知识服务和模型适配器均核对命中资料的来源ID、版本、标题、完整正文及适用店铺，必须与代码内的公开目录完全一致。商家资料、私有与公开混合的结果、伪造ID或被修改的摘要均不发送。包含明显账号、密钥、联系方式或订单/退款等业务内容的问题也跳过模型筛选；这只是保守过滤，不能当作通用的个人信息检测器。

外部请求正文只包含固定筛选指令、本次问题和最多两篇规范公开摘要。DeepSeek只能返回候选来源ID数组；Java严格校验JSON、唯一性、候选范围和结束状态，不接收模型自写答案或工具调用。有效的空数组表示资料不足；接口异常、超时或非法结果则沿用本地引用。最终展示仍是原始摘要及完整条件，不能把这一步描述为自由生成答案。

单次调用最多8秒、输出最多256个token，不自动重试，与现有模型调用共享并发限制和熔断器。操作类型为固定的 `PUBLIC_KNOWLEDGE_EVIDENCE`，监控不保存问题和资料正文。只读知识GET、历史证据恢复和原来源追问不调用这一外部筛选器。

## 新增的18篇参考摘要

完整条目及适用店铺见 [`references.json`](../src/main/resources/knowledge/references.json)。这是重新整理的短摘要及原文链接，不是下载后重新分发整套厂商手册。每条包含发布方、来源URL、核对日期、适用型号或系统限制。不存在从网络直接生成本店退款、保修、费用或配送承诺的步骤。

| 主题                                        | 来源                                                                                                                           | 适用店铺      |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | ------------- |
| Windows蓝牙配对、重连与驱动                 | [Microsoft Support](https://support.microsoft.com/zh-cn/windows/hardware/bluetooth/pair-a-bluetooth-device-in-windows)         | 100、101、102 |
| Android配对、连接排查、快速配对及跨系统耳机 | [Google Android帮助](https://support.google.com/android/answer/9417604?hl=zh-Hans)                                             | 100、101、102 |
| AirPods机身和充电盒清理参考                 | [Apple支持](https://support.apple.com/zh-cn/102672)                                                                            | 100、101、102 |
| 无线鼠标接收器、无响应、清洁与电池检查      | [Microsoft Support](https://support.microsoft.com/zh-cn/windows/hardware/input-devices/mouse-and-keyboard-problems-in-windows) | 100、101、102 |
| 床品水洗与干燥图示                          | [GINETEX](https://www.ginetex.net/share/article/7005/care-symbols)                                                             | 100、103      |
| 被套洗护及被芯/枕芯尺寸核对示例             | [IKEA DVALA](https://www.ikea.com/gb/en/p/dvala-duvet-cover-and-2-pillowcases-white-90377965/)                                 | 100、103      |
| 陶瓷杯微波炉/洗碗机适用性核对示例           | [IKEA FÄRGKLAR](https://www.ikea.com/gb/en/p/faergklar-mug-light-blue-70604879/)                                               | 100、103      |

品牌示例不证明演示商品属于该品牌；某一型号可机洗、微波或充电，不能推导其他商品也可以。原商家FAQ及账号/授权不被本轮覆盖。公开参考按代码发布，不冒充商家编辑的草稿。

## 验证与指标

```powershell
mvn -o -Dtest=RagKnowledgeSearchTest,RagResourcesTest,PublicKnowledgeServiceTest,DeepSeekPublicKnowledgeSelectorTest,DeepSeekCallMetricsTest -Dit.test=LocalRagModelIT,LocalRagKnowledgeIT test-compile surefire:test failsafe:integration-test failsafe:verify
```

模型评测使用真实固定权重，包含22条校准案例及另外16条保留案例。门限以校准集确定后再运行保留集，保留集不用于调参。结果输出在 `target/knowledge-rag-evaluation/`，含模型/数据集哈希、参数、期望来源、实际来源、逐条相似度及JSON/Markdown汇总。

`Hit@2`只表示正例中前两条结果至少命中一条预期来源；不能等同于来源精确率、未知问题准确率或线上收益。测试不删除低置信度拒答案例：部分鼠标口语问法和相近洗护问法仍会拒答，需补充描述。

2026-10-28本机固定权重实测：

| 数据集     | 有答案问题 | 词法Hit@2 | 混合Hit@2 | 无关/跨店拒答 |
| ---------- | ---------- | --------- | --------- | ------------- |
| 校准集     | 16         | 8/16      | 14/16     | 6/6           |
| 独立保留集 | 12         | 1/12      | 10/12     | 4/4           |

低置信度的4个正例仍保留在报告里，未靠移除案例提高成绩。2026-10-28本地向量检索交付时，556个Java单元测试、168个相关集成测试及185个前端测试通过；日常后端与Docker版的8项只读知识核验各自通过，Docker另有12项权限/会话检查通过，14笔学习订单的商品资料和元数据复核通过。

2026-10-29公开来源筛选回归：567个Java单元测试、51个相关集成测试通过，其中覆盖真实Spring装配、公开/私有/混合/伪造资料、非法模型结果、降级、GET和追问不外发；既有47条离线Agent场景也全部通过。集成测试使用模拟模型和临时MySQL。另以固定的耳机连接问题及一篇公开摘要完成一次真实DeepSeek调用，严格来源解析通过；该次验证未连接业务数据库或写入聊天记录。一次成功调用验证了连接和协议，不能证明所有回答都准确。

本次打包后已更新日常后端和Docker实验后端。两者的8项知识只读核验各自通过，14笔学习订单的资料/图片与元数据复核通过，Docker的12项店铺/会话隔离检查通过。部署核验没有发送聊天POST或增加模型调用，数据库继续为V17，无新增迁移或授权。

独立MySQL/HTTP测试覆盖认证、买家/商家入口区别、草稿不见、跨店、发布、更新版本、下线后旧向量不再返回，以及只读查询不新增会话。测试使用临时数据库，不写日常知识、订单或售后表。

买家可使用只读接口 `GET /api/v1/shops/{shopId}/knowledge/search?q=...` 查看来源。它要求已认证买家的 `ORDER_READ` 权限，返回既有 `KnowledgeEvidence`，不创建会话、不调用远程模型。聊天入口继续复用同一检索服务和原有来源展示。

监控新增 `supportflow.rag.retrieval`、`supportflow.rag.retrieval.duration`、`supportflow.rag.fallback`、`supportflow.rag.embedding.cache` 和 `supportflow.rag.cached.chunks`。标签只使用固定结果类型，不记录问题、账号、来源正文或订单号。
