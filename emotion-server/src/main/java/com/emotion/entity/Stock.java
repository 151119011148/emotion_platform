package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A股代码名称总表：全市场一只一行，用来把"龙版传媒"这种名字变成可搜索的输入。
 *
 * 不绑 user_id，也不参与打分。退市股不会被删——这张表只做代码到名称的翻译，
 * 留着一个已经不在交易的代码比少一条搜索命中代价小得多。
 */
@Data
@TableName("t_stock")
public class Stock {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    /** 东财 f13：1=沪，0=深（北交所也报 0）。 */
    private Integer market;
    /** 按代码前缀归类：沪主板/深主板/创业板/科创板/北交所。 */
    private String board;
    private LocalDateTime updatedAt;
}
