package com.emotion.market;

/**
 * 面向使用者的失败原因。必须是中文：GlobalExceptionHandler 会把 message 原样回给前端，
 * 直接抛 IOException / ResourceAccessException 会把英文技术栈漏到界面上。
 */
public class MarketDataException extends RuntimeException {

    public MarketDataException(String message) {
        super(message);
    }
}
