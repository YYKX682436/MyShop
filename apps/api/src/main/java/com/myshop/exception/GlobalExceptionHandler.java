package com.myshop.exception;

import com.myshop.common.ApiResponse;
import com.myshop.constant.ErrorCode;
import com.myshop.model.request.RegisterRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        // Parser exceptions may contain request bodies, including administrator credentials.
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST, "请求格式无效"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResponse.error(e.getCode(), e.getMessage(), e.getParams()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        var fieldErrors = e.getBindingResult().getFieldErrors();
        if (e.getParameter().getParameterType() == RegisterRequest.class && !fieldErrors.isEmpty()) {
            FieldError fieldError = fieldErrors.stream()
                    .filter(error -> "NotBlank".equals(error.getCode()) || "NotNull".equals(error.getCode()))
                    .findFirst()
                    .orElse(fieldErrors.get(0));
            String validation = switch (String.valueOf(fieldError.getCode())) {
                case "NotBlank", "NotNull" -> "required";
                default -> "format";
            };
            String field = "captchaId".equals(fieldError.getField()) ? "captcha" : fieldError.getField();
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    ErrorCode.BAD_REQUEST,
                    fieldError.getDefaultMessage(),
                    java.util.Map.of("field", field, "validation", validation)));
        }

        String message = fieldErrors.stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, message));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "Not logged in or session expired"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.FORBIDDEN, "Access denied"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, "本次上传总大小不能超过 30MB"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(DataIntegrityViolationException e) {
        String detail = String.valueOf(e.getMostSpecificCause().getMessage());
        if (detail.contains("uq_payment_channels_active_code")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorCode.CHANNEL_CODE_CONFLICT,
                            "渠道代码已存在，请使用其他通道实例代码"));
        }
        if (detail.contains("uq_users_email_normalized")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorCode.EMAIL_EXISTS,
                            "该邮箱已被其他用户使用"));
        }
        if (detail.contains("uq_users_phone")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorCode.PHONE_EXISTS,
                            "该手机号已注册"));
        }
        if (detail.contains("uq_users_username_ci")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorCode.USERNAME_EXISTS,
                            "用户名已存在"));
        }
        log.warn("Data integrity violation");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, "数据校验失败"));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Void> handleMissingResource() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort() {
        // Video players routinely close a streaming request when seeking or
        // navigating away. The response is already gone; do not write JSON to
        // a video/mp4 response or report the disconnect as a server failure.
        log.debug("Client disconnected while receiving a response");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.SERVER_ERROR, "System error, please try again later"));
    }
}
