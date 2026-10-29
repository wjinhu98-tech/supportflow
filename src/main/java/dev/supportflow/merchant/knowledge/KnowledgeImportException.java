package dev.supportflow.merchant.knowledge;

import dev.supportflow.assistant.aftersale.AssistantBusinessException;
import dev.supportflow.common.api.error.ErrorCode;
import org.springframework.http.HttpStatus;

final class KnowledgeImportException {
    private KnowledgeImportException() { }

    static AssistantBusinessException unsupported() { return invalid("请选择TXT或Markdown纯文本文件。"); }
    static AssistantBusinessException encoding() { return invalid("文件不是有效的UTF-8文本，请转换编码后重试。"); }
    static AssistantBusinessException content() { return invalid("正文须为1至4000字的有效纯文本，超出部分不会截断。"); }
    static AssistantBusinessException metadata() { return invalid("请填写有效标题及1至12个不重复关键词，每个关键词最多40字。"); }
    static AssistantBusinessException fields() { return invalid("请上传一个文件，并按要求填写标题和关键词。"); }
    static AssistantBusinessException unreadable() { return invalid("文件暂时无法读取，请重新选择文件后重试。"); }
    static AssistantBusinessException tooLarge() {
        return new AssistantBusinessException(ErrorCode.KNOWLEDGE_IMPORT_INVALID, HttpStatus.PAYLOAD_TOO_LARGE,
                "文件不能超过64KiB，请拆分后再导入。");
    }
    private static AssistantBusinessException invalid(String message) {
        return new AssistantBusinessException(ErrorCode.KNOWLEDGE_IMPORT_INVALID, HttpStatus.BAD_REQUEST, message);
    }
}
