package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预判留痕：{@code 预判:} 存成 PLAN 行落在计划那天，{@code 对答案:} 存成 ANSWER 行落在回写那天。
 *
 * <p>两种行**不互相拷贝**：兑现结果是查的时候用"次日的 ANSWER 按名称匹配前一日 PLAN"算出来的。
 * 冗余拷贝会踩一个坑——重导前一个交易日是删日重建，那行已被次日回填的兑现结果就跟着没了，
 * 而你重导那天多半只是在改错别字。
 */
@Data
@TableName("t_prediction")
public class Prediction {

    /** 盘前推演的一条路径，来自 {@code 预判:}。 */
    public static final String KIND_PLAN = "PLAN";
    /** 次日对答案的一条结论，来自 {@code 对答案:}。 */
    public static final String KIND_ANSWER = "ANSWER";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate tradeDate;
    private String kind;
    /** 路径名。跨日对齐只认名称，所以名字必须每天复用。 */
    private String name;
    /** PLAN：发生概率 0-100。 */
    private Integer prob;
    /** PLAN：触发条件原文。 */
    private String conditionText;
    /** ANSWER：命中 / 落空 / 部分 / 违约。 */
    private String result;
    /** ANSWER：一句话依据。 */
    private String resultNote;
    private LocalDateTime createdAt;
}
