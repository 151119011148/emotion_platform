package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一次运行的留痕：跑过几次、什么状态、多久、有没有告警。
 *
 * <p>与候选明细刻意分开：同一交易日重跑三次，这张表会有三行（历史不覆盖，AC-9 要求看得见三次），
 * 而 {@link CandidateStock} 只有一份（整日替换）。看到「跑了三次都成功但候选只有 12 只」是正常的。
 */
@Data
@TableName("t_strategy_run")
public class StrategyRun {

    public static final String TRIGGER_SCHEDULE = "SCHEDULE";
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_BACKTEST = "BACKTEST";

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 跑通了但一只候选都没有。不是失败——空候选通常意味着市场没有符合条件的目标。 */
    public static final String STATUS_EMPTY = "EMPTY";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long strategyId;
    /** 用的哪版配置。复算的依据。 */
    private Long versionId;
    private LocalDate tradeDate;
    /** SCHEDULE / MANUAL / BACKTEST。 */
    private String triggerType;
    /** 1=试运行，只回结果不落候选表（BACKTEST 走这条）。 */
    private Integer dryRun;
    /** RUNNING / SUCCESS / EMPTY / FAILED / SKIPPED。 */
    private String status;
    private Integer candidateCount;
    private Integer nodeCount;
    /** CANDIDATE_TRUNCATED / TOPIC_FALLBACK / NO_NODE …，界面据此提示「结果可能不完整」。 */
    private String warning;
    private String errorMsg;
    /** 逐级漏斗计数（候选 → 各过滤器 → 最终），界面解释「为什么只剩这几只」用的就是它。 */
    private String detailJson;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer costMs;
}
