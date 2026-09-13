package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 主线双轨（v0.2）：雷达区「升级到主线区」的人工主线标记。
 * 命中当日即算有主线（hasMainline=true），D2 评分对象优先取人工标记行业，高于 ≥3天 自动主线。
 */
@Data
@TableName("t_mainline_mark")
public class MainlineMark {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate tradeDate;
    private String industry;
    private Integer manual;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}