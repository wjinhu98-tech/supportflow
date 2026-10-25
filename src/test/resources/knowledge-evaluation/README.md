# 知识检索固定回归集

版本：`knowledge-cases-2026-10-25-v1`；词法别名版本由 `KnowledgeQueryExpansion.VERSION` 输出。

这是一组人工编写的学习环境合成样本，不是线上用户日志、开放基准或真实泛化测试。修改别名、阈值或摘录规则时应先保留此集，再追加新发现的失败案例；不能删除失败样本来提高指标。

## 运行

在仓库根目录运行：

```powershell
mvn.cmd -o "-Dtest=KnowledgeEvaluationTest,KnowledgeRetrieverTest,KnowledgeAnswerTest" test
```

不需要 Docker、真实数据库或模型 Key，不读取 `.local`，不调用外部模型。`KnowledgeEvaluationTest` 通过模拟的 `publishedForShop(shopId)` 仓储对每条问题执行真实 `tryAnswerDetailed` 链路，按店铺选择语料后再过滤与排序。没有可靠商家命中时，空 Optional 记为 `NO_MATCH`；受保护问题仍由上层原业务/受控政策链路处理，不能据此声称“整个客服不能回答”。草稿、下线、会话恢复和鉴权的真实数据库行为由 MySQL 集成测试验证。

## 数据格式

- `corpus.tsv`：店铺、文章 UUID、发布修订号、标题、以 `|` 分隔的关键词、原文。原文中的字面量 `\n` 在载入时转为换行。
- `cases.tsv`：案例 ID、当前店铺、以 `|` 分隔的期望来源、问题、案例类别；`-` 表示不应由商家 FAQ 回答。
- 来源 UUID 固定，测试不随机生成标题、标签或期望结果。语料中包含待过滤的注入、伪造政策和伪造订单金额正文，用于验证它们不能成为证据。

## 输出和指标

控制台输出 `KNOWLEDGE_EVAL`，包含样本/别名版本、案例数、precision、recall、rejection、grounding、tenantIsolation，以及原始计数。

- **Micro precision**：所有案例中正确的返回来源数 / 全部返回来源数；来源最多两条。
- **Micro recall**：所有案例中正确的返回来源数 / 全部人工标注期望来源数。
- **Unanswerable rejection**：没有期望商家来源且实际 `NO_MATCH` 的案例数 / 所有没有期望商家来源的案例数。
- **Source grounding**：来源 ID、版本、标题和完整原文均与固定语料完全一致的引用数 / 全部返回引用数。它验证引用快照忠实性，不是自由生成自然语言的事实正确率。
- **Tenant isolation**：引用的语料确属当前店铺的引用数 / 全部返回引用数；跨店空结果另有案例断言。

固定集中每个案例都做精确标签和状态断言，指标门槛为 1.0。这个门槛是受控回归要求，不代表对未知问题能达到 100% 正确率。没有延迟、QPS、成本或生产容量结论；未来扩大语料后应增加未见过的独立验收集和实际压测。

同义扩展有意保守：“祝福卡→贺卡”“码数→尺码”“纯棉→棉质”“陶瓷水杯→陶瓷杯”。“杯子怎么洗”不会猜成陶瓷杯，“衣服怎么洗”不会猜成棉质衣物。当前没有 Embedding、向量索引或模型生成回答。
