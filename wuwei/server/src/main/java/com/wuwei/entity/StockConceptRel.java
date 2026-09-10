package com.wuwei.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/** 3.3 个股↔题材关系 */
@Data
@TableName("t_stock_concept_rel")
public class StockConceptRel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate tradeDate;
    private String tsCode;
    private String conceptId;
    /** MAIN / MINOR */
    private String role;
    /** 龙头分工标签：ZONG_LONG/ZHONG_JUN/GEN_FENG/KA_WEI/FAN_BAO */
    private String dragonRole;
}
