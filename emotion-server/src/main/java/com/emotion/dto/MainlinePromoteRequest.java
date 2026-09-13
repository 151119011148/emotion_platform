package com.emotion.dto;

import java.time.LocalDate;

import lombok.Data;

/**
 * 双轨页「升级到主线区」请求体：人工主线标记的日期与行业。
 */
@Data
public class MainlinePromoteRequest {
    private LocalDate tradeDate;
    private String industry;
}