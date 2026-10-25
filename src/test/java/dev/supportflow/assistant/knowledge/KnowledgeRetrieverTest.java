package dev.supportflow.assistant.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRetrieverTest {
    private static final KnowledgeDocument CARD = document("kb-card", "贺卡书写", List.of("贺卡"),
            "本条仅供演示。贺卡可填写祝福语。");
    private static final KnowledgeDocument SIZE = document("kb-size", "尺码选择", List.of("尺码", "肩宽"),
            "尺码建议结合肩宽、胸围和衣长，并以商品尺码表为准。");
    private static final KnowledgeDocument COTTON = document("kb-cotton", "棉质衣物洗护", List.of("棉质", "洗护"),
            "棉质衣物洗护应遵循标签，建议深浅颜色分开。");
    private static final KnowledgeDocument CUP = document("kb-cup", "陶瓷杯清洁", List.of("陶瓷杯", "清洁", "保养"),
            "陶瓷杯日常清洁使用软海绵，特殊装饰须遵循商品说明。");
    private final KnowledgeRetriever retriever = new KnowledgeRetriever(List.of(CARD, SIZE, COTTON, CUP));

    @Test
    void explicitSynonymsResolveToTheSameConceptAndSource() {
        for (String query : List.of("能放张祝福卡吗", "留言卡可以写字吗", "祝福小卡")) {
            assertThat(retriever.retrieve(query)).extracting(KnowledgeMatch::sourceId).containsExactly("kb-card");
        }
        assertThat(retriever.retrieve("码数怎么选")).extracting(KnowledgeMatch::sourceId).containsExactly("kb-size");
        assertThat(retriever.retrieve("纯棉衣物怎么清洗")).extracting(KnowledgeMatch::sourceId).containsExactly("kb-cotton");
        assertThat(retriever.retrieve("陶瓷水杯如何清洁")).extracting(KnowledgeMatch::sourceId).containsExactly("kb-cup");
    }

    @Test
    void genericTermsCannotGuessMaterialOrProductCategory() {
        for (String query : List.of("怎么清洁保养", "杯子怎么洗", "衣服怎么洗", "给点测量建议", "清洗说明方法")) {
            assertThat(retriever.retrieve(query)).as(query).isEmpty();
        }
    }

    @Test
    void normalizationOnlyWhitespaceKeywordsCannotMatchEveryUnrelatedQuestion() {
        for (String keyword : List.of("\u00a0", "\u2007", "\u202f")) {
            var unicodeTrap = new KnowledgeRetriever(List.of(document("kb-trap", "Unrelated material",
                    List.of(keyword), "unrelated")));
            assertThat(unicodeTrap.retrieve("火星荧光蘑菇培养方案")).as("Unicode keyword U+%04X", (int) keyword.charAt(0))
                    .isEmpty();
        }
    }

    @Test
    void nearCategoryConflictsAreNotMistakenForKnownProducts() {
        for (String query : List.of("玻璃杯怎么清洗", "塑料杯清洁保养", "保温杯怎么洗", "不锈钢杯清洗",
                "纯棉毛巾怎么洗", "纯棉床单洗护", "棉被洗护", "真丝衣物洗护", "羊毛衣服怎么洗",
                "银行卡可以写祝福语吗", "购物卡说明")) {
            assertThat(retriever.retrieve(query)).as(query).isEmpty();
        }
    }

    @Test
    void repeatedAliasesDoNotInflateScores() {
        assertThat(retriever.retrieve("祝福卡 祝福卡 留言卡 贺卡").getFirst().score())
                .isEqualTo(retriever.retrieve("贺卡").getFirst().score());
        var aliases = new KnowledgeRetriever(List.of(document("kb-card", "贺卡", List.of("贺卡", "祝福卡", "留言卡"),
                "贺卡可写祝福语。")));
        var canonical = new KnowledgeRetriever(List.of(document("kb-card", "贺卡", List.of("贺卡"), "贺卡可写祝福语。")));
        assertThat(aliases.retrieve("祝福卡").getFirst().score()).isEqualTo(canonical.retrieve("祝福卡").getFirst().score());
    }

    @Test
    void matchEvidenceNamesTheVersionedExpansionWithoutMutatingTheQuestion() {
        String original = "能放张祝福卡吗？";
        var matches = retriever.retrieve(original);
        assertThat(original).isEqualTo("能放张祝福卡吗？");
        assertThat(matches.getFirst().matchedTerms()).contains("[" + KnowledgeQueryExpansion.VERSION + "]贺卡");
        assertThat(matches).isEqualTo(retriever.retrieve(original));
    }

    @Test
    void retrievalIsBoundedAndTiesUseStableSourceIdentifiers() {
        var tied = new KnowledgeRetriever(List.of(
                document("kb-charlie", "贺卡", List.of("贺卡"), "贺卡说明"),
                document("kb-alpha", "贺卡", List.of("贺卡"), "贺卡说明"),
                document("kb-bravo", "贺卡", List.of("贺卡"), "贺卡说明")));
        assertThat(tied.retrieve("贺卡")).extracting(KnowledgeMatch::sourceId).containsExactly("kb-alpha", "kb-bravo");
    }

    @Test
    void controlledPublicCatalogRetainsItsExistingLexicalFallback() {
        var publicSource = new KnowledgeRetriever(List.of(document("public-rules", "演示服务", List.of("范围"),
                "测试服务支持公开说明以及历史咨询记录。")));
        assertThat(publicSource.retrieve("历史咨询记录")).extracting(KnowledgeMatch::sourceId).containsExactly("public-rules");
    }

    private static KnowledgeDocument document(String id, String title, List<String> keywords, String content) {
        return new KnowledgeDocument(id, "v1", title, keywords, content);
    }
}
