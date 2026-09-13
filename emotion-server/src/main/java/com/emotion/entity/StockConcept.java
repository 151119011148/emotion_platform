package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * {@code t_stock_concept}：全市场「股票代码 → 概念板块」静态索引（D2 题材聚合的地基）。
 * 概念成分（东财 BKxxxx）变化慢，索引低频全量重建；同一股票可能在多个概念里（允许多行）。
 */
@Data
@TableName("t_stock_concept")
public class StockConcept {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 6 位股票代码。 */
    private String code;

    /** 概念板块代码（东财 BKxxxx）。 */
    private String conceptCode;

    /** 概念板块名称。 */
    private String concept;

    /** 股票简称，索引构建时带出，便于排查。 */
    private String name;

    private LocalDateTime updatedAt;
}