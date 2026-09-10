package com.wuwei.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 3.7 每日评分（details_json 为引擎五维明细快照，超出 PRD 的附加列） */
@Data
@TableName("t_sentiment_score")
public class SentimentScore {
    @TableId(type = IdType.INPUT)
    private LocalDate tradeDate;
    private BigDecimal scoreMarket;
    private BigDecimal scoreConcept;
    private BigDecimal scoreLianban;
    private BigDecimal scoreShouban;
    private BigDecimal scoreZhenyan;
    private BigDecimal totalScore;
    private Boolean forceExit;
    private String forceReason;
    private String detailsJson;
    private LocalDateTime createdAt;
}
