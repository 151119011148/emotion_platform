package com.emotion.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.emotion.market.TencentClient.DayBar;

/**
 * 阵眼跨度：从一只票的日 K 上读出"这轮它走到哪了"。
 *
 * <p>纯静态、不碰网络，单测直接喂数组。跨度里所有量都从日 K 现算，
 * 因为只要有一个数能手填，它就一定会和行情对不上——而且对不上是静默的。
 *
 * <p>涨跌停一律用<b>涨幅阈值</b>判，不用 {@code round(prevClose*1.1,2)} 那种绝对价：
 * 前复权序列里真实 +10.00% 的涨停会算成 +10.13%，绝对价判据在复权因子面前不可用。
 */
public final class AnchorMetrics {

    /** 主板 ±10% 留两分容差；创业板/科创板 ±20%；北交所 ±30%。 */
    public static final BigDecimal LIMIT_MAIN = new BigDecimal("9.8");
    public static final BigDecimal LIMIT_GROWTH = new BigDecimal("19.8");
    public static final BigDecimal LIMIT_BEIJING = new BigDecimal("29.8");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private AnchorMetrics() {
    }

    /** 板块到阈值的映射由 {@code t_stock.board} 决定，不在这里再猜一次代码前缀。 */
    public static BigDecimal limitOf(String board) {
        if (board == null) {
            return LIMIT_MAIN;
        }
        if (board.contains("创业") || board.contains("科创")) {
            return LIMIT_GROWTH;
        }
        if (board.contains("北交")) {
            return LIMIT_BEIJING;
        }
        return LIMIT_MAIN;
    }

    /**
     * 量一只阵眼到 {@code date} 为止的跨度。
     *
     * @param bars 升序日 K；前一根可以在区间外（涨幅本来就要靠它算）
     * @param endInclusive 跨度终点，null 表示仍在位；晚于 date 的按 date 截
     */
    public static Span measure(List<DayBar> bars, LocalDate startDate, LocalDate endInclusive,
                              LocalDate date, BigDecimal limitPct) {
        Span span = new Span();
        span.tradeDate = date;
        span.limitPct = limitPct;
        if (bars == null || bars.isEmpty() || startDate == null) {
            return span;
        }
        LocalDate until = endInclusive == null || endInclusive.isAfter(date) ? date : endInclusive;

        DayBar prev = null;
        DayBar today = null;
        DayBar before = null;
        int streak = 0;
        BigDecimal peakClose = null;
        int tradeDays = 0;
        for (DayBar bar : bars) {
            if (bar.getDate() == null) {
                continue;
            }
            if (bar.getDate().isBefore(startDate)) {
                prev = bar;
                continue;
            }
            if (bar.getDate().isAfter(until)) {
                break;
            }
            tradeDays++;
            if (bar.getClose() != null && (peakClose == null || bar.getClose().compareTo(peakClose) > 0)) {
                peakClose = bar.getClose();
            }
            boolean limitUp = isLimit(bar.getPct(), limitPct, true);
            streak = limitUp ? streak + 1 : 0;
            if (span.getMaxBoard() < streak) {
                span.maxBoard = streak;
            }
            if (!limitUp && isLimit(prev == null ? null : prev.getPct(), limitPct, true)) {
                span.breakDates.add(bar.getDate());
            }
            if (bar.getDate().isEqual(date)) {
                today = bar;
                // 必须在 prev 被本根覆盖之前拿走：循环 break 得晚一步，循环外的 prev 已经是 today 自己。
                before = prev;
            }
            prev = bar;
        }
        span.tradeDays = tradeDays;
        span.peakClose = peakClose;
        if (today == null || tradeDays == 0) {
            // 这一天这只票没有柱子：停牌或压根没数据。不能拿前一根冒充，那会把"没行情"读成"没跌"。
            return span;
        }
        span.available = true;
        span.lastClose = today.getClose();
        span.pct = today.getPct();
        span.lowPct = today.getLowPct();
        span.closeLimitUp = isLimit(today.getPct(), limitPct, true);
        span.closeLimitDown = isLimit(today.getPct(), limitPct, false);
        span.touchedLimitDown = isLimit(today.getLowPct(), limitPct, false);
        span.brokeToday = !span.closeLimitUp && isLimit(before == null ? null : before.getPct(), limitPct, true);
        if (before != null && before.getDate() != null && !before.getDate().isBefore(startDate)) {
            span.prevBar = before;
        }
        if (peakClose != null && today.getClose() != null) {
            span.newSpanHigh = today.getClose().compareTo(peakClose) >= 0;
            span.drawdownPct = today.getClose().subtract(peakClose)
                    .divide(peakClose, 6, RoundingMode.HALF_UP)
                    .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
        }
        return span;
    }

    /** {@code up=true} 判涨停、{@code false} 判跌停；null 涨幅永远为假。 */
    private static boolean isLimit(BigDecimal pct, BigDecimal limitPct, boolean up) {
        if (pct == null || limitPct == null) {
            return false;
        }
        return up ? pct.compareTo(limitPct) >= 0 : pct.compareTo(limitPct.negate()) <= 0;
    }

    /** 一只阵眼到某日的跨度快照。缺数据时 available=false，其余字段一律留 null。 */
    public static final class Span {
        private LocalDate tradeDate;
        private BigDecimal limitPct;
        private boolean available;
        private int tradeDays;
        private int maxBoard;
        private BigDecimal pct;
        private BigDecimal lowPct;
        private BigDecimal lastClose;
        private BigDecimal peakClose;
        private BigDecimal drawdownPct;
        private boolean closeLimitUp;
        private boolean closeLimitDown;
        private boolean touchedLimitDown;
        private boolean brokeToday;
        private boolean newSpanHigh;
        private DayBar prevBar;
        private final List<LocalDate> breakDates = new ArrayList<LocalDate>();

        public LocalDate getTradeDate() {
            return tradeDate;
        }

        /** 判涨跌停用的阈值，卡片上要说清这只票按几个点判。 */
        public BigDecimal getLimitPct() {
            return limitPct;
        }

        /** 这一天这只票有没有行情。false 意味着第 8 维未评，不是 0 分。 */
        public boolean isAvailable() {
            return available;
        }

        /** 跨度里的交易日根数。 */
        public int getTradeDays() {
            return tradeDays;
        }

        /** 区间最高连板：连续涨停的最长长度。 */
        public int getMaxBoard() {
            return maxBoard;
        }

        /** 当日收盘涨幅%。 */
        public BigDecimal getPct() {
            return pct;
        }

        /** 当日最低点涨幅%——盘中触板只看它，收盘跌停看 pct。 */
        public BigDecimal getLowPct() {
            return lowPct;
        }

        public BigDecimal getLastClose() {
            return lastClose;
        }

        /** 跨度内最高收盘价，绝对价，只在 qfq 序列内部自比。 */
        public BigDecimal getPeakClose() {
            return peakClose;
        }

        /** 距跨度最高收盘的回撤%，≤0；peakClose 缺失时为 null。 */
        public BigDecimal getDrawdownPct() {
            return drawdownPct;
        }

        public boolean isCloseLimitUp() {
            return closeLimitUp;
        }

        public boolean isCloseLimitDown() {
            return closeLimitDown;
        }

        /** 盘中最低越到过跌停价，收盘可能已经拉回来了——这正是"触板"那种走法。 */
        public boolean isTouchedLimitDown() {
            return touchedLimitDown;
        }

        /** 昨日涨停、今日未涨停。 */
        public boolean isBrokeToday() {
            return brokeToday;
        }

        /** 收盘创跨度内新高。 */
        public boolean isNewSpanHigh() {
            return newSpanHigh;
        }

        /** 跨度内所有断板日，升序；最近一次断板是判退潮的直接依据。 */
        public List<LocalDate> getBreakDates() {
            return breakDates;
        }

        public LocalDate getLastBreakDate() {
            return breakDates.isEmpty() ? null : breakDates.get(breakDates.size() - 1);
        }

        /** 参与当日判定的前一根，null 表示跨度第一天（前一根在起点之前）。 */
        public DayBar getPrevBar() {
            return prevBar;
        }
    }
}
