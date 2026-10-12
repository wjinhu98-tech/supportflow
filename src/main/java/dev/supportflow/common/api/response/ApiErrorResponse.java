package dev.supportflow.common.api.response;

import dev.supportflow.common.api.error.ErrorCode;

/**
 * 接口错误响应。
 *
 * @param code 错误分类
 * @param message 本次错误的具体说明
 */
public record ApiErrorResponse(ErrorCode code, String message) {
}