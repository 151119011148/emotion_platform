package com.emotion.ai;

/** 模型调用失败。只带一句能给他看的原因，不带 key、不带完整响应体。 */
public class LlmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
