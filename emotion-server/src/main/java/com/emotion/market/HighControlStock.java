package com.emotion.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「高控庄票」风险识别：<strong>持续缩量一字的小盘连板</strong>。
 *
 * <p>五条全部满足才打标（AND）：
 * <ol>
 *   <li>连板 ≥ 2</li>
 *   <li>当日一字锁死（首封 ≤ 93030 且全天 0 炸板）</li>
 *   <li>当日换手 &lt; 2% <strong>且</strong>当日成交额 ≤ 1 亿 —— 地量，不是「没人炒」那种低换手</li>
 *   <li>本段连板里<strong>至少 2 天</strong>是一字锁死（持续性；只有一天是蒙上的）</li>
 *   <li>首板日流通市值 ≤ 35 亿</li>
 * </ol>
 *
 * <p><strong>为什么收紧（2026-10-15）：</strong>老判据只有「连板≥2 + 换手&lt;5% + 当日一字锁死 +
 * 首板日≤35亿」四条，一个月里给 954 行涨停明细打了 20 个标、候选池 141 行里 12 行带标，
 * 里面混着慈文传媒、新经典这类<strong>游资题材连板</strong>——它们放量抢上来、只在加速那天蒙了个一字，
 * 筹码结构（慈文前十大流通 33%、新经典主力成交占比 0.69%）根本不支持「庄在里头」。
 * 现在的量级：同一个月 5 个标，两条掉标腿各管一类——
 * 换手/成交额这条把「放量票偶尔一字」筛掉（换手 2.4%~3.9%、成交 0.9~1.2 亿那批全部出局），
 * 持续这条把「首尔蒙一次一字」筛掉（段内只有一天锁死的出局）。
 * 雪龙集团 09-29（换手 1.67%、成交 0.72 亿、段内 2 天锁死）这种照旧命中。
 *
 * <p>判据里没有股东结构：{@code t_market_stock} 只有行情列，拿不到户数 / 前十大流通 / 主力占比，
 * 所以「谁在控盘」只能用<strong>缩量持续锁死</strong>这个外壳去逼近。要真按筹码定性，
 * 得先给库里加股东数据，那是另一件事。
 *
 * <p>首板日市值的还原方式：在 {@code t_market_stock} 里找该 code 最近一条 {@code consecutive=1}
 * 且 {@code float_mv IS NOT NULL} 的行（45 天回溯），取它的 {@code float_mv}。
 * 找不到就 fail-open（不打标）——判不出来就说判不出来。
 */
public final class HighControlStock {

    private HighControlStock() {}

    public static final int MIN_BOARD = 2;
    public static final BigDecimal FLOAT_MV_CAP = new BigDecimal("3500000000");
    /** 与 PRD 里「缩量一字」同一个换手下限：高控庄票是缩量一字的<strong>持续＋小盘</strong>版。 */
    public static final BigDecimal TURNOVER_MAX = new BigDecimal("2");
    /** 地量下限，单位元。写死而不读 {@code filter_min_amount}：调仓位下限的旋钮不该顺带动庄票判据。 */
    public static final BigDecimal AMOUNT_MAX = new BigDecimal("100000000");
    /** 本段连板里要求的一字锁死天数（含今天）。1＝老判据那种「今天恰好一字」，2＝真的锁着。 */
    public static final int MIN_LOCKED_DAYS = 2;
    public static final int FLASH_LINE_MAX = 93030;
    public static final int LOOKBACK_DAYS = 45;

    /** 一字锁死：首封 ≤ 93030 且全天 0 炸板。与 TiantiService.isLocked 同口径。 */
    public static boolean isLocked(MarketStock r) {
        Integer fbt = r == null ? null : r.getFirstSealTime();
        if (fbt == null) {
            return false;
        }
        int brk = r.getBreakCount() == null ? 0 : r.getBreakCount();
        return fbt <= FLASH_LINE_MAX && brk == 0;
    }

    /**
     * 本段连板的追溯结果：首板日流通市值 + 段长 + 段内一字锁死天数。
     *
     * <p>段长从当天往回按板数逐级 −1 追溯，一旦接不上就停（那天没在涨停池＝断板，或者库里缺行）。
     * 缺行只会让段算短、锁死天数算少，判据因此偏保守而不是偏松。
     */
    public static final class Streak {
        private final BigDecimal firstBoardFloatMv;
        private final int days;
        private final int lockedDays;

        public Streak(BigDecimal firstBoardFloatMv, int days, int lockedDays) {
            this.firstBoardFloatMv = firstBoardFloatMv;
            this.days = days;
            this.lockedDays = lockedDays;
        }

        public BigDecimal firstBoardFloatMv() {
            return firstBoardFloatMv;
        }

        public int days() {
            return days;
        }

        public int lockedDays() {
            return lockedDays;
        }
    }

    /**
     * 纯判据：给定当日行情与本段连板追溯，判是否高控庄票。
     *
     * <p>{@code streak} 为 null（当天没在涨停池、或调用方没给追溯结果）时返回 false，
     * 首板日市值为 null 时同样返回 false——两个都是 fail-open：判不出来就不打标。
     */
    public static boolean isHighControl(MarketStock row, Streak streak) {
        if (row == null || streak == null) {
            return false;
        }
        int board = row.getConsecutive() == null ? 0 : row.getConsecutive();
        if (board < MIN_BOARD) {
            return false;
        }
        if (!isLocked(row)) {
            return false;
        }
        BigDecimal hs = row.getTurnoverRate();
        if (hs == null || hs.compareTo(TURNOVER_MAX) >= 0) {
            return false;
        }
        BigDecimal amt = row.getAmount();
        if (amt == null || amt.compareTo(AMOUNT_MAX) > 0) {
            return false;
        }
        if (streak.lockedDays() < MIN_LOCKED_DAYS) {
            return false;
        }
        BigDecimal firstBoardFloatMv = streak.firstBoardFloatMv();
        return firstBoardFloatMv != null && firstBoardFloatMv.compareTo(FLOAT_MV_CAP) <= 0;
    }

    /**
     * 从一只票的涨停明细（任意顺序）算出本段连板的追溯结果。
     *
     * <p>{@code date} 那天没有行 → 段长 0，只剩首板日市值，判据必然不过。
     */
    public static Streak streakOf(List<MarketStock> rowsOfOneCode, LocalDate date) {
        if (rowsOfOneCode == null || rowsOfOneCode.isEmpty() || date == null) {
            return new Streak(null, 0, 0);
        }
        List<MarketStock> asc = new ArrayList<>(rowsOfOneCode);
        asc.sort(Comparator.comparing(MarketStock::getTradeDate));
        int today = -1;
        for (int i = asc.size() - 1; i >= 0; i--) {
            if (date.equals(asc.get(i).getTradeDate())) {
                today = i;
                break;
            }
        }
        BigDecimal firstBoardMv = firstBoardFloatMv(asc, date);
        if (today < 0) {
            return new Streak(firstBoardMv, 0, 0);
        }
        MarketStock head = asc.get(today);
        int days = 1;
        int locked = isLocked(head) ? 1 : 0;
        for (int want = board(head) - 1, j = today - 1; j >= 0 && want >= 1; j--) {
            if (board(asc.get(j)) != want) {
                break;
            }
            days++;
            if (isLocked(asc.get(j))) {
                locked++;
            }
            want--;
        }
        return new Streak(firstBoardMv, days, locked);
    }

    /** 首板日流通市值：窗口内最近一条 {@code consecutive=1} 且 {@code float_mv} 非空的行。 */
    private static BigDecimal firstBoardFloatMv(List<MarketStock> asc, LocalDate date) {
        BigDecimal mv = null;
        LocalDate best = null;
        for (MarketStock r : asc) {
            if (board(r) != 1 || r.getFloatMv() == null || r.getTradeDate() == null) {
                continue;
            }
            if (r.getTradeDate().isAfter(date)
                    || ChronoUnit.DAYS.between(r.getTradeDate(), date) > LOOKBACK_DAYS) {
                continue;
            }
            if (best == null || r.getTradeDate().isAfter(best)) {
                best = r.getTradeDate();
                mv = r.getFloatMv();
            }
        }
        return mv;
    }

    private static int board(MarketStock r) {
        return r.getConsecutive() == null ? 0 : r.getConsecutive();
    }

    /**
     * 批量追溯一批 code 在 {@code date} 当天的本段连板。
     *
     * <p>一次查询把 {@value #LOOKBACK_DAYS} 天内的涨停池行全捞回来分组，
     * 首板日市值与段内锁死天数都从这份结果算——老版本只查 {@code consecutive=1} 的行，
     * 那批行算不出持续性。{@code pool=ZT} 必须带上：炸板池／跌停池没有板数，
     * 老写法靠 {@code consecutive=1} 这个条件替它过滤，现在条件没了。
     */
    public static Map<String, Streak> streaks(MarketStockMapper mapper,
                                              Collection<String> codes,
                                              LocalDate date) {
        Map<String, Streak> result = new HashMap<>();
        if (codes == null || codes.isEmpty() || date == null) {
            return result;
        }
        LocalDate from = date.minusDays(LOOKBACK_DAYS);
        Map<String, List<MarketStock>> byCode = new HashMap<>();
        for (MarketStock r : mapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .in(MarketStock::getCode, codes)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP)
                .ge(MarketStock::getTradeDate, from)
                .le(MarketStock::getTradeDate, date))) {
            byCode.computeIfAbsent(r.getCode(), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<MarketStock>> e : byCode.entrySet()) {
            result.put(e.getKey(), streakOf(e.getValue(), date));
        }
        return result;
    }
}
