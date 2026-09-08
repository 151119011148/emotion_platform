package com.emotion.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

/**
 * 行情可自动取到的字段。字段名必须与 DailyRecordRequest 的市场字段逐字一致——
 * 前端 fillForm 是按 Object.keys(form) 逐个比名字合并的。
 *
 * NON_NULL 是这套契约的核心：序列化后剩下的 key 就是"这一次真的取到了哪些"，
 * 前端据此区分"已自动填充"和"仍需手工"。
 *
 * <p>库里这七列都是 DEFAULT NULL——"当天真的没有跌停"存 0，"没取到"存 NULL，两者在库里
 * 本来就分得开，响应的 NON_NULL 只负责表达本次取数结果，不背这个区分责任。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MarketFields {

    private Integer maxConsecutiveLimit;
    private Integer limitUpCount;
    private Integer limitDownCount;
    private BigDecimal yesterdayLimitPremium;
    private BigDecimal brokenBoardRate;
    private Integer bigLossCount;
    private BigDecimal totalVolume;
}
