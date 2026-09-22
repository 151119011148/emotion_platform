package com.emotion.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 日 K 缓存行（{@code t_daily_bar}）。
 *
 * <p>只存前复权四价，<b>不存涨跌幅</b>：pct / lowPct 一律由「序列里紧邻的前一根收盘」推出，
 * 落库再读会面临「这段数据是哪一次拉取的、前一根在不在库里」的拼接问题，
 * 把派生值存下来就等于让同一个数有两处真相（上游重算 qfq 后旧 pct 不会跟着变，错得更隐蔽）。
 * 读的时候现算，代价只是一次减法，换来的是「库里任何一段拼起来都对得上」。
 *
 * <p>另一个要点：这是<b>带保鲜期的缓存</b>而不是档案。除权除息会让上游把历史 qfq 价整体重算，
 * 所以 {@code t_daily_bar_fetch} 过期后会整段重拉覆盖，不要把它当成不可变的历史事实表用。
 */
@Data
@TableName("t_daily_bar")
public class DailyBar {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** gtimg 代码，如 {@code sz000993} / {@code sh000001}。 */
    private String symbol;
    private LocalDate tradeDate;
    private BigDecimal openPrice;
    private BigDecimal closePrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    /** 上游复权版本号；一段内出现两种即说明是拼凑的，须整段重拉。 */
    private String fqVersion;
    private LocalDateTime updatedAt;
}
