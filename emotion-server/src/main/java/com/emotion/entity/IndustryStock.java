package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * {@code t_industry_stock}：个股 → 通达信二级行业 映射（来源海王星 tdxhy.cfg，行业码截 T+4）。
 * 板块排名/D2 雷达切到通达信二级行业时，用它把涨停池每只股票归到二级行业名。
 */
@Data
@TableName("t_industry_stock")
public class IndustryStock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String market;
    /** 二级行业码（T+4 位）。 */
    private String industryCode;
    /** 二级行业名（如：元器件、电力、煤炭）。 */
    private String industryName;
}