package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日内核心题材榜 Top5 快照：题材维度按用户个性化（t_theme / t_theme_stock 带 userId），
 * 与行业快照（公开不绑用户）不同，这里按 (user_id, trade_date) 存当日展示的前 5 名，
 * 供「题材表」历史可回溯而不必每次重算。读取题材表时自动回填（同 AUTO 绑定惯例）。
 */
@Data
@TableName("t_theme_daily_snapshot")
public class ThemeSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate tradeDate;
    /** 题材绑定所属用户（AUTO 口径）。 */
    private Long userId;
    /** 题材榜排名 1-5。 */
    private Integer rank;
    private String themeName;
    private Integer ztCount;
    private BigDecimal strength;
    private Integer maxBoard;
    private Integer continuousDays;
    private Integer hardness;
    private String lifecycle;
    /** 关联板块（逗号拼接的通达信二级行业）。 */
    private String relatedIndustries;
    private String leaderCode;
    private String leaderName;
    private Integer leaderBoard;
}