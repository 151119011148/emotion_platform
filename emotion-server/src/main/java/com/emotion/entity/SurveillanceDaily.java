package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 监管全生命周期某一天的一条轨迹。
 *
 * <p>由 {@code t_surveillance} 事件 + {@code SurveillanceKind} 定的监管窗口现算后落库：
 * pool/连板/封单/炸板/大面取自 {@code t_market_stock}（仅进池日有值），
 * change_pct 用腾讯日K补齐（停牌/缺失为 null）。公开数据，不绑 user_id。
 */
@Data
@TableName("t_surveillance_daily")
public class SurveillanceDaily {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 6 位股票代码。 */
    private String stockCode;
    /** 股票简称。 */
    private String stockName;
    /** 监管公告日 D0。 */
    private LocalDate annDate;
    /** SEVERE / EXCH / ZD，见 {@link com.emotion.market.SurveillanceKind}。 */
    private String kind;
    /** 监管期内交易日。 */
    private LocalDate tradeDate;
    /** 相对公告日的交易日偏移：D0=0，D+1=1 ... D+N=N。 */
    private Integer dayOffset;
    /** 当日连板数（进池日有值，非涨跌停日为 null）。 */
    private Integer consecutive;
    /** 当日涨跌幅%（日K补齐；停牌/缺失为 null）。 */
    private BigDecimal changePct;
    /** ZT 涨停 / DT 跌停 / ZB 炸板，非进池日为 null。 */
    private String pool;
    /** 当日是否大面/核按钮（1=是）。 */
    private Integer bigLoss;
    /** 当日炸板次数。 */
    private Integer breakCount;
    /** 当日封单额。 */
    private BigDecimal sealAmount;
    /** 当日停牌（该票无日K，与公共交易日错位）：1=停牌。 */
    private Integer suspended;
    private LocalDateTime updatedAt;
}