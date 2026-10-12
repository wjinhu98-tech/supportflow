package dev.supportflow.common.security.api;

import dev.supportflow.common.api.error.ErrorCode;
import dev.supportflow.common.api.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 将安全过滤器中的错误写成与 MVC 一致的 JSON 响应。
 */
@Component
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(
                objectMapper, "objectMapper must not be null"
        );
    }

    public void write(
            HttpServletResponse response,
            HttpStatus status,
            ErrorCode errorCode
    ) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new ApiErrorResponse(errorCode, errorCode.getDefaultMessage())
        );
    }
}
