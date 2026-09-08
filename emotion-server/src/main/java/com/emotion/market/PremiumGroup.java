package com.emotion.market;

/**
 * 连板档的三组归并：低/中/高。分组是"钱在哪个档赚钱"的读法，逐档（2..8+）只是它的展示粒度。
 *
 * <p>界线<b>是活的</b>：中位线取当日最高板的一半（使用者原话"可以用前一天最高板的一半来定义中位"）。
 * 写死的 2-3/4-5/6+ 在一轮周期里会把"5 板"永远读成中位，而那可能已经是这轮周期的顶端；
 * 顶端在哪，高位就该跟着在哪。
 *
 * <p>{@code H=8} 时这套规则和原先写死的三档完全重合，所以它只是把低周期那几天挪了位置。
 */
public enum PremiumGroup {

    LOW,
    MID,
    HIGH;

    /**
     * 中位下界。{@code Math.round(2.5)=3}、{@code Math.round(3.5)=4}，一半落不进整数时往上取，
     * 也就是"中位偏厚、高位更难进"——高位抱团这个判据宁可少报也别虚报。
     *
     * @param h 当日档位行里的最高板（= 昨日涨停池最高板），2..{@link MarketMetrics#MAX_BOARD}
     */
    public static int midLine(int h) {
        return Math.max(2, (int) Math.round(h / 2.0));
    }

    /** 低 = 2..M-1、中 = M..M+1、高 = ≥M+2。M 夹在 2 起，所以 2 板永远在低或中，不会有档落空。 */
    public static PremiumGroup of(int board, int h) {
        int m = midLine(h);
        if (board >= m + 2) {
            return HIGH;
        }
        return board >= m ? MID : LOW;
    }

    /** 中文档位名，给卡片和 tooltip 用，不参与计算。界线随当天 H 走，所以标签也必须按 H 现生成。 */
    public String label(int h) {
        int m = midLine(h);
        switch (this) {
            case LOW:
                // M=2 时低档在结构上就是空的：当天最高才 3~4 板，2 板已经算中位了。
                // 这时候写"低位(2-1板)"是句读不通的话，直接说没有。
                return m - 1 < 2 ? "低位(当天无此档)" : "低位(2-" + (m - 1) + "板)";
            case MID:
                return "中位(" + m + "-" + (m + 1) + "板)";
            default:
                return "高位(" + (m + 2) + "板+)";
        }
    }
}
