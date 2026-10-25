package dev.supportflow.merchant.knowledge;

import dev.supportflow.assistant.aftersale.AssistantBusinessException;
import dev.supportflow.common.api.error.ErrorCode;
import org.springframework.http.HttpStatus;

public final class KnowledgeException {
    private KnowledgeException() { }

    public static AssistantBusinessException invalid() {
        return new AssistantBusinessException(ErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST,
                "请检查知识标题、关键词、正文和版本；请填写有效的纯文本内容。");
    }

    public static AssistantBusinessException notFound() {
        return new AssistantBusinessException(ErrorCode.KNOWLEDGE_NOT_FOUND, HttpStatus.NOT_FOUND, "知识资料不存在。");
    }

    public static AssistantBusinessException conflict() {
        return new AssistantBusinessException(ErrorCode.KNOWLEDGE_CONFLICT, HttpStatus.CONFLICT,
                "知识资料已更新或当前状态不支持此操作，请刷新后重试。");
    }

    public static AssistantBusinessException limit() {
        return new AssistantBusinessException(ErrorCode.KNOWLEDGE_LIMIT_REACHED, HttpStatus.CONFLICT,
                "每家店铺最多同时发布100篇资料，请先下线不再使用的资料。");
    }
}
