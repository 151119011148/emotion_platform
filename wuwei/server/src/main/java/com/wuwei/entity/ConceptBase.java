package com.wuwei.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/** 3.2 板块题材池（含题材生命周期 stage 与催化剂硬度） */
@Data
@TableName("t_concept_base")
public class ConceptBase {
    @TableId(type = IdType.INPUT)
    private String conceptId;
    private String name;
    private String source;
    private String level1;
    private String level2;
    private Boolean isMainLine;
    /** 题材生命周期：萌芽/确认/扩散/亢奋/退潮 */
    private String stage;
    /** 催化剂硬度 1-5 */
    private Integer catalystHardness;
    /** 连续活跃天数 */
    private Integer continuousDays;
    private LocalDate activeSince;
    private String note;
}
