package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 昨日涨停股今日表现，逐只一行（<b>含首板</b>，这是它与 {@link PremiumTier} 档位表最大的不同）。
 *
 * <p>三池明细只覆盖今天还触板的票。昨首板今天低开闷杀、全天未触板的票不在 ZT/ZB/DT 任何池里，
 * 1 进 2 大面若只数炸板池 big_loss 就必然是下界——09-11 实测 22 只失败只数出 1 只，实有 5 只。
 * 公开行情数据，不绑 user_id。
 */
@Data
@TableName("t_zt_perf")
public class ZtPerf {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 表现日 D：衡量的是昨日涨停股在 D 这一天的涨跌。 */
    private LocalDate tradeDate;
    private String code;
    private String name;
    /** D-1 连板数：1=昨首板（1 进 2 分母口径），2 及以上=连板档。 */
    private Integer prevConsecutive;
    /** D 收盘涨跌幅 %（相对昨收，而昨收即昨日涨停价）。 */
    private BigDecimal changePct;
    /** QUOTE=腾讯批量快照 / KBAR=日 K 回补 / BK=东财昨涨停板块。 */
    private String source;
    private LocalDateTime createdAt;
}
