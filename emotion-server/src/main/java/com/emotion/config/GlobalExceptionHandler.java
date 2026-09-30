package com.emotion.config;

import com.emotion.exception.BizException;
import com.emotion.vo.ApiResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：HTTP 状态刻意仍是 200，只把 code 置 400。
     * 前端 axios 拦截器只在 200 这条分支里读 res.message，4xx/5xx 会先落进 error 分支、
     * 被统一说成「网络错误」，业务原因根本传不到页面上。
     *
     * 显式声明而不靠下面的 RuntimeException 兜底：BizException 正是它的子类，
     * 兜底其实也命中，但「哪个 handler 赢」取决于匹配深度，写清楚不赌这个。
     */
    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException e) {
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ApiResponse<Void> handleRuntime(RuntimeException e) {
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        return ApiResponse.error(500, "服务器内部错误: " + e.getMessage());
    }
}
