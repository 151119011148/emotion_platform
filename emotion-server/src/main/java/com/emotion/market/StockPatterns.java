package com.emotion.market;

import com.emotion.entity.MarketStock;

/**
 * 涨停个股日内形态判定（东财涨停池字段，无网请求，纯函数）：
 * <ul>
 *   <li>ONE_LINE 一字板：09:30 前（含集合竞价 09:25）封死且全天炸板 0 次；</li>
 *   <li>T_SHAPE T字板：开盘封死但盘中开过板又回封（首封≤09:30，炸板≥1 次）；</li>
 *   <li>TURNOVER 换手板：09:30 之后才封板（盘中换手后上板）。</li>
 * </ul>
 * 历史明细在 fbt 落库前没有首封时间，返回 null（显示「—」，不猜形态）。
 */
public final class StockPatterns {

    /** 09:30:00，含 09:25 集合竞价封单。 */
    private static final int OPEN_SEAL_BOUND = 93000;

    public static final String ONE_LINE = "ONE_LINE";
    public static final String T_SHAPE = "T_SHAPE";
    public static final String TURNOVER = "TURNOVER";

    private StockPatterns() {
    }

    /** 按已落库的涨停池行判形态；非涨停行或缺首封时间返回 null。 */
    public static String of(MarketStock ztRow) {
        if (ztRow == null) {
            return null;
        }
        Integer fbt = ztRow.getFirstSealTime();
        if (fbt == null) {
            return null;
        }
        int breaks = ztRow.getBreakCount() == null ? 0 : ztRow.getBreakCount();
        if (fbt <= OPEN_SEAL_BOUND) {
            return breaks == 0 ? ONE_LINE : T_SHAPE;
        }
        return TURNOVER;
    }
}
