package dev.supportflow.common.api.advice;

import dev.supportflow.common.api.error.ErrorCode;
import dev.supportflow.common.api.response.ApiErrorResponse;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.Objects;

/**
 * 统一处理接口参数错误和业务异常，内部异常不向客户端暴露细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception) {

        ErrorCode errorCode = ErrorCode.INVALID_ARGUMENT;
        String message = errorCode.getDefaultMessage();

        FieldError fieldError = exception.getBindingResult().getFieldError();
        if (fieldError != null && fieldError.getDefaultMessage() != null) {
            message = fieldError.getDefaultMessage();
        }

        ApiErrorResponse response = new ApiErrorResponse(errorCode, message);

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 处理请求体缺失或无法读取的情况。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest() {
        ApiErrorResponse response = new ApiErrorResponse(
                ErrorCode.INVALID_ARGUMENT,
                "请求体缺失或格式不正确"
        );

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 将范围内没有可访问订单的业务结果转换为统一错误响应。
     */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleOrderNotFound() {
        ErrorCode errorCode = ErrorCode.ORDER_NOT_FOUND;

        ApiErrorResponse response = new ApiErrorResponse(
                errorCode,
                errorCode.getDefaultMessage()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(response);
    }

    /**
     * 处理路径参数等 Controller 方法入参的校验失败。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(
            HandlerMethodValidationException exception
    ) {
        if (exception.isForReturnValue()) {
            LOGGER.error("Controller return-value validation failed", exception);
            return internalError();
        }

        String message = exception.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(ErrorCode.INVALID_ARGUMENT.getDefaultMessage());

        return ResponseEntity.badRequest().body(
                new ApiErrorResponse(ErrorCode.INVALID_ARGUMENT, message)
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception
    ) {
        if (exception instanceof ErrorResponse errorResponse
                && !errorResponse.getStatusCode().is5xxServerError()) {
            return ResponseEntity.status(errorResponse.getStatusCode()).body(
                    new ApiErrorResponse(
                            ErrorCode.REQUEST_REJECTED,
                            ErrorCode.REQUEST_REJECTED.getDefaultMessage()
                    )
            );
        }

        LOGGER.error("Unhandled server exception", exception);
        return internalError();
    }

    private ResponseEntity<ApiErrorResponse> internalError() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ApiErrorResponse(
                        ErrorCode.INTERNAL_ERROR,
                        ErrorCode.INTERNAL_ERROR.getDefaultMessage()
                )
        );
    }
}
