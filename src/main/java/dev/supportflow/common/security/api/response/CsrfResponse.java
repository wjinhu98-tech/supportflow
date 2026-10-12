package dev.supportflow.common.security.api.response;

/**
 * 客户端执行有副作用请求时所需的 CSRF 信息。
 */
public record CsrfResponse(
        String headerName,
        String parameterName,
        String token
) {
}
