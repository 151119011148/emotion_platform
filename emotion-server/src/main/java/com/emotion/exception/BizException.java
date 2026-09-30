package com.emotion.exception;

/**
 * 业务性失败：参数不合法、用户名重复、权限不够这类「用户看得懂、也该看见原因」的错误。
 *
 * <p>刻意区别于 {@link RuntimeException} 的其它子类——那些是 bug，不该把堆栈里的原话
 * 直接甩给前端。{@code GlobalExceptionHandler} 只认这一种，把它翻译成
 * {@code code=400} 的 {@code ApiResponse}，其余异常的行为保持原样（Spring 默认 500），
 * 免得一次改动把全站的错误码语义一起换了。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }
}
