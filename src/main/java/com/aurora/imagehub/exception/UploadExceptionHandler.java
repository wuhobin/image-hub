package com.aurora.imagehub.exception;

import com.aurora.starter.verification.exception.VerificationCooldownException;
import com.aurora.starter.verification.exception.VerificationDeliveryException;
import com.aurora.starter.webmvc.domain.response.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 将上传体积限制及邮件验证码异常转换为前端可识别的统一业务响应。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadExceptionHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> tooLarge() {
        return Result.error(413, "单张图片不能超过 10 MB");
    }

    @ExceptionHandler(VerificationCooldownException.class)
    public Result<Void> cooldown() {
        return Result.error(429, "验证码发送过于频繁，请稍后再试");
    }

    @ExceptionHandler(VerificationDeliveryException.class)
    public Result<Void> mailFailure() {
        return Result.error(503, "邮件发送失败，请稍后重试");
    }
}
