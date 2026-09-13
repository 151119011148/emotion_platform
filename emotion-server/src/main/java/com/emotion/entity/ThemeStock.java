package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 题材-个股关系（按日快照）。题材（概念）与板块（行业）的根本差异是"一只票可归多个题材"，
 * 所以题材计数必须用 {@link #isPrimary}（主题材）去重——同一只票可以出现在多个 theme_id 下，
 * 但只在其 is_primary=1 的题材里计涨停数，避免跨题材总和超过全市场涨停数。
 *
 * <p>人工归类=MANUAL；自动回填热门行业= AUTO（见 {@link com.emotion.service.IntradayService}）。
 */
@Data
@TableName("t_theme_stock")
public class ThemeStock {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long themeId;
    private LocalDate tradeDate;
    private String code;
    private String name;
    /** 冗余行业，便于题材→板块双向映射。 */
    private String industry;
    /** 1=主题材（参与涨停数统计）0=辅题材（仅关联展示不计数）。 */
    private Integer isPrimary;
    /** MANUAL 人工 / AUTO 自动回填。 */
    private String source;
    private LocalDateTime createdAt;
}