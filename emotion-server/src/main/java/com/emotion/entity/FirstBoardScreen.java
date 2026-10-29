package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 首板「站上年线 + 题材在天梯里没有」的判定留档，一票一天一行。
 *
 * <p>判据与为什么必须落库见迁移 {@code V47__first_board_screen.sql}：年线每只票都要拉一年日 K，
 * 而日 K 缓存对最近三天不留痕、每天首版判定必然回源，所以计算放在 15:30 的选股链路里，
 * D6 与 D9 都只读这张表。
 *
 * <p>{@code passed=false} 的行同样存在——它们是「某某为什么不在名单里」的唯一答案。
 */
@Data
@TableName("t_first_board_screen")
public class FirstBoardScreen {

    /** 两条都过：可进 D9 候选。 */
    public static final String REASON_PASS = "PASS";
    /** 有年线，但收盘在年线之下（或正好持平）。 */
    public static final String REASON_BELOW_MA250 = "BELOW_MA250";
    /** 题材在天梯里出现过——这个题材当天有 2 板及以上的票。 */
    public static final String REASON_THEME_IN_LADDER = "THEME_IN_LADDER";
    /** 通达信题材索引里没有这只票的概念行：判不了，按未通过记。 */
    public static final String REASON_NO_CONCEPT = "NO_CONCEPT";
    /** 可取到的收盘根数不足 250（多为上市不足一年）：没有年线，判不了。 */
    public static final String REASON_NO_ANNUAL_LINE = "NO_ANNUAL_LINE";
    /** 池内当日收盘价为空：年线凑不满 250 根，判不了。 */
    public static final String REASON_NO_CLOSE = "NO_CLOSE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate tradeDate;
    private String code;
    private String name;
    /** 通达信二级行业，与 {@code t_market_stock.industry} 同口径，只作展示。 */
    private String industry;
    /** 该股所属通达信题材简称，逗号分隔。 */
    private String concepts;
    private Integer conceptCount;
    /** 当日天梯覆盖的题材并集只数。 */
    private Integer ladderConcepts;
    /** 该股题材与天梯题材的交集只数。 */
    private Integer overlapCount;
    private BigDecimal closePrice;
    private BigDecimal ma250;
    /** (收盘 − 年线) ÷ 年线 × 100。 */
    private BigDecimal abovePct;
    /** 参与年线的收盘根数（含当日那根）。 */
    private Integer barsUsed;
    private Boolean passed;
    private String reason;
    private LocalDateTime createdAt;
}
