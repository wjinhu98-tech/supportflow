package dev.supportflow.common.security.api;

import dev.supportflow.common.api.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;

/**
 * 未认证或凭据错误时，返回统一 401 并保留 Basic 认证挑战。
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter responseWriter;
    private boolean basicEnabled = true;

    public JsonAuthenticationEntryPoint(
            SecurityErrorResponseWriter responseWriter
    ) {
        this.responseWriter = responseWriter;
    }

    @Value("${supportflow.security.authentication.basic-enabled:true}")
    public void setBasicEnabled(boolean basicEnabled) {
        this.basicEnabled = basicEnabled;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        if (basicEnabled) response.setHeader(
                HttpHeaders.WWW_AUTHENTICATE,
                "Basic realm=\"SupportFlow\", charset=\"UTF-8\""
        );
        responseWriter.write(
                response,
                HttpStatus.UNAUTHORIZED,
                ErrorCode.AUTHENTICATION_REQUIRED
        );
    }
}
