package dev.supportflow.common.security.api;

import dev.supportflow.common.api.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 权限不足或 CSRF 校验失败时，返回统一 403。
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter responseWriter;

    public JsonAccessDeniedHandler(
            SecurityErrorResponseWriter responseWriter
    ) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException {
        responseWriter.write(
                response, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED
        );
    }
}
