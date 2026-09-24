package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 候选池明细：某个策略某个交易日选出来的票，逐只一行。
 *
 * <p>「按 run 隔离」在 PRD 里是这么写的，但落地时按 {@code (strategy_id, trade_date)} 整日替换：
 * 同一天重跑三次，run 表留三行历史，候选只保留最新一次的结果（AC-9 要求候选行数不变）。
 * 用户要看的是「那天的候选是谁」，不是「历次运行分别算出谁」——后者会让界面出现三份互相打架的清单。
 *
 * <p>{@code rank_no} 是按<strong>可执行性</strong>排的，不是按分数。理由见
 * {@code WaveRiderEngine}：在「隔夜跳空 ≤ 门槛」这个可执行子样本里，连板数、成交额、
 * 首封时间的排序力都不成立，所以这里不做因子加权排序。
 */
@Data
@TableName("t_candidate_stock")
public class CandidateStock {

    public static final String PRINCIPLE_POSITION = "POSITION";
    public static final String PRINCIPLE_NODE = "NODE";

    /** 一字缩量：封单占成交额过高，次日大概率一字开盘买不进。 */
    public static final String RISK_YIZI_THIN = "YIZI_THIN";
    /** 高位过度换手。 */
    public static final String RISK_HIGH_TURNOVER = "HIGH_TURNOVER";
    /** 龙头断板。 */
    public static final String RISK_DRAGON_DEAD = "DRAGON_DEAD";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long strategyId;
    /** 候选产生日 D。注意这天它是涨停的，收盘价买不到——收益起算日永远是 D+1。 */
    private LocalDate tradeDate;
    private Integer rankNo;
    private String code;
    private String name;
    /** D 日连板数。 */
    private Integer board;
    /** 主归属题材。 */
    private String topic;
    /** 全部命中题材（多归属），JSON 数组。 */
    private String conceptsJson;
    /** POSITION / NODE，可同时命中，JSON 数组。 */
    private String hitPrinciplesJson;
    private String nodeType;
    private LocalDate nodeDate;
    /** 身位描述，如「出版最高板」。 */
    private String positionType;
    private BigDecimal score;
    /** 建议仓位（小数）。单票上限由配置的 max_single_position 决定。 */
    private BigDecimal suggestPosition;
    private String riskFlag;
    /** 过滤器逐条判定明细，界面「点开追溯」看到的就是它。 */
    private String filterDetailJson;
    private LocalDateTime createdAt;
}
