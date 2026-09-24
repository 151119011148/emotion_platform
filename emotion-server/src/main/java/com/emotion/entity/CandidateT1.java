package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 候选票在 D+1 的实际表现。复盘与权重回归的唯一数据来源。
 *
 * <p>为什么除了 {@code t1ChangePct} 还要存 {@code t1Open} 和 {@code gapPct}：
 * 候选池的定义是「D 日已涨停」，也就是说 D 日收盘价<strong>根本买不到</strong>。
 * {@code t1ChangePct}（相对 D 日收盘的涨幅）里有一大块是「不持仓时的隔夜跳空」，
 * 那部分永远拿不到；而 {@code gapPct} 恰好就是它，{@code t1Open} 则是唯一可在 D+1 成交的价格。
 *
 * <p>所以：以 {@code close(D)} 为起算价的收益只能当「信号有效性基准」，
 * 真正可落地的口径是 {@code close(D+1) / open(D+1) - 1}，两者相差极大
 * （实测全样本 +3.36% 对 −0.20%）。这两个字段就是让后者能被算出来，
 * 不需要每次复盘都回上游重拉日 K。
 */
@Data
@TableName("t_candidate_t1")
public class CandidateT1 {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long strategyId;
    /** 候选产生日 D。 */
    private LocalDate tradeDate;
    private String code;
    /** D 日连板数。 */
    private Integer board;
    /** 验证日 D+1。 */
    private LocalDate t1Date;
    /** D+1 开盘价（不复权，与计算 gap 用的昨收同源）。 */
    private BigDecimal t1Open;
    /** 隔夜跳空 % = open(D+1)/close(D) − 1。D 日盘后唯一能事前算出的「买不买得到」前哨。 */
    private BigDecimal gapPct;
    /** D+1 收盘涨幅 %（相对 D 日收盘）。含隔夜跳空，不等于可实现收益。 */
    private BigDecimal t1ChangePct;
    /** D+1 所属池：ZT/ZB/DT，NULL=三池均无。 */
    private String t1Pool;
    /** 是否晋级：D+1 仍涨停且连板数 = D 日 + 1。 */
    private Integer promoted;
    /** D+1 盘中最高涨幅 %，算盈利空间用。 */
    private BigDecimal maxChg;
    private LocalDateTime updatedAt;
}
