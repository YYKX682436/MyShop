package com.myshop.exception;

import com.myshop.common.ApiResponse;
import com.myshop.constant.ErrorCode;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import lombok.extern.slf4j.Slf4j;
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

import java.util.stream.Collectors;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResponse.error(e.getCode(), e.getMessage(), e.getParams()));
    }

    @ExceptionHandler(SalesChannelSpecMappingException.class)
    public ResponseEntity<ApiResponse<Void>> handleSalesChannelSpecMapping(SalesChannelSpecMappingException e) {
        log.warn("Sales-channel specification mapping failed: code={}, retryable={}",
                e.getCode(), e.isRetryable());
        return ResponseEntity.status(e.isRetryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, e.getMessage(),
                        java.util.Map.of("errorCode", e.getCode(), "retryable", e.isRetryable())));
    }

    @ExceptionHandler(SalesChannelProviderClientException.class)
    public ResponseEntity<ApiResponse<Void>> handleSalesChannelProviderClient(
            SalesChannelProviderClientException e) {
        boolean retryable = e.getDisposition()
                == SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE;
        HttpStatus status = retryable ? HttpStatus.SERVICE_UNAVAILABLE
                : "ITEM_SYNC_BUSY".equals(e.getErrorCode()) ? HttpStatus.CONFLICT
                : "ACCOUNT_AUTH_REQUIRED".equals(e.getErrorCode())
                    || "ITEM_SYNC_PAGE_LIMIT_REACHED".equals(e.getErrorCode())
                    ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY;
        String message = switch (e.getErrorCode() == null ? "" : e.getErrorCode()) {
            case "ACCOUNT_AUTH_REQUIRED" -> "闲鱼登录已失效，请先重新登录账号。";
            case "ITEM_SYNC_BUSY" -> "闲鱼商品同步正在进行，请稍后重试。";
            case "ITEM_SYNC_PAGE_LIMIT_REACHED" -> "商品目录超过完整同步页数上限，本次同步未完成。";
            default -> e.getMessage() == null ? "闲鱼服务暂时不可用" : e.getMessage();
        };
        log.warn("Sales-channel provider request failed: code={}, retryable={}", e.getErrorCode(), retryable);
        return ResponseEntity.status(status).body(ApiResponse.error(
                ErrorCode.BAD_REQUEST, message,
                Map.of("errorCode", e.getErrorCode() == null ? "AGENT_REQUEST_FAILED" : e.getErrorCode(),
                        "retryable", retryable)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
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
        if (detail.contains("uq_sales_channel_listings_account_external_item")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(ErrorCode.ITEM_ALREADY_LINKED,
                            "该闲鱼商品已关联其它 MyShop 商品"));
        }
        if (detail.contains("uq_sales_channel_listings_account_product")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(ErrorCode.PRODUCT_ALREADY_LINKED,
                            "该 MyShop 商品已关联其它闲鱼商品"));
        }
        log.warn("Data integrity violation");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, "数据校验失败"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.SERVER_ERROR, "System error, please try again later"));
    }
}
