package com.emotion.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 复盘字段的纯计算。刻意与 {@link com.emotion.service.MarketDataService} 分开：
 * 这里没有网络、没有 Spring，可以用存下来的 fixture 离线跑单测。
 *
 * 口径全部按知识库 03 篇原文，不按行业惯例——两处都和常见写法不同，见各方法注释。
 */
public final class MarketMetrics {

    /** 03 篇：大面 = 从涨停/大涨砸到绿盘，日内回撤 > 7%。 */
    public static final BigDecimal BIG_LOSS_PULLBACK = BigDecimal.valueOf(7);

    /**
     * 1 进 2 口径的<b>收盘</b>大面线：昨日收在涨停价（=昨收），今日收盘跌幅 {@code >7%}
     * 即"从昨涨停砸下来回撤 &gt;7% 且收绿"，与 {@link #BIG_LOSS_PULLBACK} 同一个 7%，
     * 只是参照价换成昨日涨停价，算式因此直接等于今日涨跌幅 {@code < -7%}（严格不等）。
     */
    public static final BigDecimal CLOSE_BIG_LOSS_PCT = BigDecimal.valueOf(-7);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private MarketMetrics() {
    }

    /** 炸板次数：池子里每只股票 zbc 的合计，不是家数。 */
    public static int breakCount(List<PoolRow> rows) {
        int sum = 0;
        for (PoolRow row : rows) {
            if (row.getZbc() != null) {
                sum += row.getZbc();
            }
        }
        return sum;
    }

    /**
     * 炸板率 = 炸板次数 ÷（炸板次数 + 封住家数）。
     * 行业常见的是家数口径，同一天两者能差 30 个百分点、把这一维从 1 分改成 0 分。
     *
     * @return 分子分母都为 0（当天既没涨停也没炸板）时返回 null，交由调用方决定怎么记
     */
    public static BigDecimal brokenRate(int breaks, int sealedHomeCount) {
        int total = breaks + sealedHomeCount;
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(breaks).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    /** 百分比，用于把另一种口径的数字摆出来供核对。 */
    public static BigDecimal percent(int part, int total) {
        if (total <= 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(part).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    /**
     * 家数封板率 = 涨停家数 ÷（涨停家数 + 炸板家数）。
     *
     * <p>刻意不是「100 − 炸板率」：炸板率数的是打开<b>次数</b>，一只票炸三次和三只票各炸一次
     * 在两个口径里是两回事。09-04 实测炸板率 84.7%、家数封板率 44.8%，
     * 拿互补数当封板率等于把同一个读数打两遍分，这一维并不会因此变宽。
     *
     * @return 明细为空（那天没回补过三池）时 null=未评，不是 0%
     */
    public static BigDecimal sealedHomeRate(PoolCounts counts) {
        if (counts == null || counts.isEmpty()) {
            return null;
        }
        int total = counts.getZtCount() + counts.getZbCount();
        return total <= 0 ? null : percent(counts.getZtCount(), total);
    }

    /**
     * 回封率 = 封住前曾打开的涨停家数 ÷（那些 + 炸板家数）。
     * 分子只数今天最终封住的，炸开没封回去的都只在分母里——这一项问的是「炸开之后接不接得住」。
     *
     * @return 明细为空时 null；涨停池里没有一只开过板时是 0.0（真有分母，只是分子为 0）
     */
    public static BigDecimal resealRate(PoolCounts counts) {
        if (counts == null || counts.isEmpty()) {
            return null;
        }
        int total = counts.getResealCount() + counts.getZbCount();
        return total <= 0 ? null : percent(counts.getResealCount(), total);
    }

    /**
     * 大面：炸板池里"自涨停回撤 &gt; 7% 且收盘为绿盘"的家数。
     * 只看涨跌幅会漏掉那些高开低走但仍红盘的，只看回撤会把微跌的算进来，两个条件都要。
     *
     * 命中的行一并交出去：卡面上那个数字和 hover 出来的名单必须是同一次筛选的结果，
     * 否则"4 家"配三行名单，使用者就会开始怀疑整块面板。家数由名单长度派生，不留两个能各自漂移的计数。
     */
    public static BigLoss bigLoss(List<PoolRow> brokenPool) {
        List<PoolRow> hit = new ArrayList<>();
        int unusableCount = 0;
        for (PoolRow row : brokenPool) {
            BigDecimal pullback = row.pullbackFromLimitPct();
            if (pullback == null || row.getZdp() == null) {
                unusableCount++;
                continue;
            }
            if (pullback.compareTo(BIG_LOSS_PULLBACK) > 0 && row.getZdp().signum() < 0) {
                hit.add(row);
            }
        }
        return new BigLoss(hit, unusableCount);
    }

    /**
     * 昨日涨停溢价：昨日涨停池全部标的今日涨幅的算术平均。
     * 匹配不上的（停牌、北交所无报价）只能跳过，但样本数必须如实交出去，
     * 否则一个 -5% 的读数可能只代表了 42 只里的 3 只。
     */
    public static Premium premium(List<PoolRow> prevLimitUpPool, Map<String, TencentClient.StockQuote> quotes) {
        BigDecimal sum = null;
        int matched = 0;
        for (PoolRow row : prevLimitUpPool) {
            TencentClient.StockQuote quote = quotes.get(TencentClient.toGtimgCode(row));
            if (quote == null || quote.getChangePct() == null) {
                continue;
            }
            sum = sum == null ? quote.getChangePct() : sum.add(quote.getChangePct());
            matched++;
        }
        if (matched == 0) {
            return new Premium(null, 0);
        }
        return new Premium(sum.divide(BigDecimal.valueOf(matched), 2, RoundingMode.HALF_UP), matched);
    }

    /**
     * 第 9 维的人群溢价：当日在列监管股今日涨幅的算术平均。
     *
     * 与档位溢价同一道脏值守卫，但这里必须逐只过而不是只校最终标量：在列常常只有三五只，
     * 一个上游脏值就能把整维从 3 分打到 0 分。上限按板块取，见 {@link #pctBoundOf}。
     * count=0 与"从没拉过"是两件事，交给调用方用 {@code surv_count} 的 0/NULL 区分。
     */
    public static Survival survivalPremium(List<SurvivalMember> members) {
        BigDecimal sum = null;
        int matched = 0;
        int dropped = 0;
        for (SurvivalMember member : members) {
            BigDecimal pct = member.getPct();
            if (pct == null) {
                continue;
            }
            BigDecimal bound = pctBoundOf(member.getCode());
            if (pct.abs().compareTo(bound) > 0) {
                dropped++;
                continue;
            }
            sum = sum == null ? pct : sum.add(pct);
            matched++;
        }
        if (matched == 0) {
            return new Survival(members.size(), 0, null, dropped);
        }
        return new Survival(members.size(), matched,
                sum.divide(BigDecimal.valueOf(matched), 2, RoundingMode.HALF_UP), dropped);
    }

    /** 8 及以上合并成一档：6 板以上本来就每天 1~3 只，再拆细只是噪声。 */
    public static final int MAX_BOARD = 8;
    /** 北交所 920xxx 一天能走 30%，拿 20% 当脏值线会把它真正的涨停当成上游出错丢掉。 */
    private static final BigDecimal STD_PCT_BOUND = BigDecimal.valueOf(20);
    private static final BigDecimal BJ_PCT_BOUND = BigDecimal.valueOf(30);

    /** 单日个股涨幅的物理上限：越出自己的板块幅度才算脏值。 */
    private static BigDecimal pctBoundOf(String code) {
        return TencentClient.isBeijing(code) ? BJ_PCT_BOUND : STD_PCT_BOUND;
    }

    /**
     * 昨日涨停池按连板档分组的溢价。首板不计入。
     *
     * <p>不新增任何上游请求：{@code pctByCode} 就是 {@link #premium} 那一次批量报价的产物
     * （code → 今日涨跌幅%），历史回补则由调用方从日 K 换算成同一形状的 map。
     * 两条路径共用这一份分组逻辑，打分口径才不会分叉。
     *
     * <p>越界的档整档丢弃、不丢整日：一个档可能只剩一只脏票，丢整日等于把当天唯一的低位信号一起带走。
     * 丢弃原因一律进 warnings，不进分母的数必须能看见。
     */
    public static PremiumTiers premiumTiers(List<PoolRow> prevLimitUp, Map<String, BigDecimal> pctByCode) {
        Map<Integer, Accum> byBoard = new TreeMap<>();
        int firstBoard = 0;
        int noBoard = 0;
        for (PoolRow row : prevLimitUp) {
            Integer lbc = row.getLbc();
            if (lbc == null) {
                noBoard++;
                continue;
            }
            if (lbc <= 1) {
                firstBoard++;
                continue;
            }
            Accum accum = byBoard.get(Math.min(lbc, MAX_BOARD));
            if (accum == null) {
                accum = new Accum();
                byBoard.put(Math.min(lbc, MAX_BOARD), accum);
            }
            accum.stockCount++;
            BigDecimal pct = pctByCode == null ? null : pctByCode.get(row.getCode());
            if (pct != null) {
                accum.add(row.getCode(), pct);
            }
        }

        List<TierPremium> tiers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<Integer, Accum> entry : byBoard.entrySet()) {
            Accum accum = entry.getValue();
            String dropped = null;
            if (accum.matched == 0) {
                dropped = "该档一只都没取到报价";
            } else {
                BigDecimal avg = accum.mean();
                if (avg.abs().compareTo(accum.pctBound) > 0) {
                    dropped = "该档均值 " + avg + "% 超出 ±" + accum.pctBound + "%，按脏值丢弃";
                }
            }
            if (dropped != null) {
                warnings.add(entry.getKey() + " 板档 " + accum.stockCount + " 只：" + dropped);
                tiers.add(new TierPremium(entry.getKey(), accum.stockCount, 0,
                        BigDecimal.ZERO, null, null));
                continue;
            }
            tiers.add(new TierPremium(entry.getKey(), accum.stockCount, accum.matched,
                    accum.sum, accum.max, accum.min));
        }
        return new PremiumTiers(tiers, firstBoard, noBoard, warnings);
    }

    /**
     * 从已落库的逐档行还原一次分组溢价（重算、回读、卡片 tooltip 都走这里）。
     * 首板家数库里没存，所以还原出来的对象只报档位、不报排除数。
     */
    public static PremiumTiers tiersFromStored(List<TierPremium> stored) {
        List<TierPremium> sorted = new ArrayList<>(stored);
        sorted.sort(Comparator.comparingInt(TierPremium::getBoard));
        return new PremiumTiers(sorted, 0, 0, new ArrayList<String>());
    }

    private static final class Accum {
        private int stockCount;
        private int matched;
        private BigDecimal sum = BigDecimal.ZERO;
        private BigDecimal max;
        private BigDecimal min;
        /** 一档里可能混着不同板块，取最宽的那个上限，否则北交所那只会把整档带下水。 */
        private BigDecimal pctBound = STD_PCT_BOUND;

        void add(String code, BigDecimal pct) {
            sum = sum.add(pct);
            matched++;
            BigDecimal bound = pctBoundOf(code);
            if (bound.compareTo(pctBound) > 0) {
                pctBound = bound;
            }
            if (max == null || pct.compareTo(max) > 0) {
                max = pct;
            }
            if (min == null || pct.compareTo(min) < 0) {
                min = pct;
            }
        }

        BigDecimal mean() {
            return sum.divide(BigDecimal.valueOf(matched), 2, RoundingMode.HALF_UP);
        }
    }

    /**
     * 1 进 2 失败票是不是大面，两条路取并集（打分与首板成绩单共用这一个判据，避免各算各的）：
     * <ul>
     *   <li>(a) 今日炸板且 big_loss=1：冲 2 板当日被砸，参照价是今日涨停价（回撤&gt;7%且收绿）；</li>
     *   <li>(b) 今日收盘涨跌幅 &lt; −7%：低开闷杀甚至跌停、全天没触板的票——它们不在 ZT/ZB/DT
     *       任何池子里，只能靠逐只表现（t_zt_perf）给出收盘价。漏了这条路，22 只失败只会数出
     *       炸板池里那 1 只（09-11 实测）。</li>
     * </ul>
     *
     * @param brokenBigLoss 今日炸板池 big_loss 标记（未触板票为 false）
     * @param todayClosePct 今日收盘涨跌幅 %；null=该票今日没有逐只报价，不能按 0 处理
     */
    public static boolean firstToTwoBigLoss(boolean brokenBigLoss, BigDecimal todayClosePct) {
        if (brokenBigLoss) {
            return true;
        }
        return todayClosePct != null && todayClosePct.compareTo(CLOSE_BIG_LOSS_PCT) < 0;
    }

    /** 大面命中名单 + 因缺字段无法判断的家数（后者意味着结果是下界，必须提示）。 */
    public static final class BigLoss {
        private final List<PoolRow> rows;
        private final int unusable;

        BigLoss(List<PoolRow> rows, int unusable) {
            this.rows = rows;
            this.unusable = unusable;
        }

        public int getCount() {
            return rows.size();
        }

        public List<PoolRow> getRows() {
            return rows;
        }

        public int getUnusable() {
            return unusable;
        }
    }

    public static final class Premium {
        private final BigDecimal value;
        private final int matched;

        Premium(BigDecimal value, int matched) {
            this.value = value;
            this.matched = matched;
        }

        public BigDecimal getValue() {
            return value;
        }

        public int getMatched() {
            return matched;
        }
    }

    /** 在列监管股的今日溢价。avgPct=null 表示没有可用样本（整维未评），count=0 表示今天没有在列的票。 */
    public static final class Survival {
        private final int count;
        private final int matched;
        private final BigDecimal avgPct;
        private final int dropped;

        Survival(int count, int matched, BigDecimal avgPct, int dropped) {
            this.count = count;
            this.matched = matched;
            this.avgPct = avgPct;
            this.dropped = dropped;
        }

        /** 当日在列家数（不论有没有取到价）。 */
        public int getCount() {
            return count;
        }

        public int getMatched() {
            return matched;
        }

        public BigDecimal getAvgPct() {
            return avgPct;
        }

        /** 被脏值守卫（上限按板块 20%/30%）丢掉的家数。非零时这个均值就不能无条件信。 */
        public int getDropped() {
            return dropped;
        }
    }

    /** 一个连板档：档内家数、真正取到涨跌的家数，以及后者的均值/最高/最低。 */
    public static final class TierPremium {
        private final int board;
        private final int stockCount;
        private final int matched;
        private final BigDecimal sum;
        private final BigDecimal max;
        private final BigDecimal min;

        TierPremium(int board, int stockCount, int matched, BigDecimal sum, BigDecimal max, BigDecimal min) {
            this.board = board;
            this.stockCount = stockCount;
            this.matched = matched;
            this.sum = sum;
            this.max = max;
            this.min = min;
        }

        /**
         * 从已落库的档位行还原（重算与回读路径）。库里存的是两位小数的均值，
         * 所以这里的 sum 是 avg×matched 的重建值——打分以这份为准，才能和卡面上显示的数是同一个数。
         */
        public static TierPremium ofStored(int board, int stockCount, int matched,
                                           BigDecimal avgPct, BigDecimal maxPct, BigDecimal minPct) {
            // 均值是 null 时样本数一律归 0：否则重建出来的 sum/matched 会让 getAvgPct() 吐出 0.00，
            // 把"这档没评"写成"这档今天不涨不跌"。
            int sample = avgPct == null ? 0 : Math.max(0, matched);
            BigDecimal sum = avgPct == null ? BigDecimal.ZERO : avgPct.multiply(BigDecimal.valueOf(sample));
            return new TierPremium(board, stockCount, sample, sum, maxPct, minPct);
        }

        /** 8 代表"8 及以上"。 */
        public int getBoard() {
            return board;
        }

        /**
         * 归属由当天的最高板决定，单档自己算不出来——所以 H 必须由 {@link PremiumTiers} 传进来。
         * 外部一律走 {@link PremiumTiers#groupOf}，不要自己攒一个 H。
         */
        public PremiumGroup getGroup(int h) {
            return PremiumGroup.of(board, h);
        }

        public int getStockCount() {
            return stockCount;
        }

        public int getMatched() {
            return matched;
        }

        /** matched 为 0 时是 null（无报价或被判脏值），不是 0——0 会被读成"这档今天不涨不跌"。 */
        public BigDecimal getAvgPct() {
            return matched == 0 ? null
                    : sum.divide(BigDecimal.valueOf(matched), 2, RoundingMode.HALF_UP);
        }

        public BigDecimal getMaxPct() {
            return max;
        }

        public BigDecimal getMinPct() {
            return min;
        }
    }

    /** 一次分组溢价的全部产物：逐档 + 三组聚合 + 被排除的行数 + 丢档告警。 */
    public static final class PremiumTiers {
        private final List<TierPremium> tiers;
        private final int firstBoard;
        private final int noBoard;
        private final List<String> warnings;

        PremiumTiers(List<TierPremium> tiers, int firstBoard, int noBoard, List<String> warnings) {
            this.tiers = tiers;
            this.firstBoard = firstBoard;
            this.noBoard = noBoard;
            this.warnings = warnings;
        }

        /** 按档位升序。 */
        public List<TierPremium> getTiers() {
            return tiers;
        }

        /** 首板家数：明知道有、但刻意不计入，不报出来的话"覆盖 13/32"会像漏抓。 */
        public int getFirstBoard() {
            return firstBoard;
        }

        /** 连板数缺失、无法归档的家数。正常上游恒为 0。 */
        public int getNoBoard() {
            return noBoard;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public int getConsidered() {
            int count = 0;
            for (TierPremium tier : tiers) {
                count += tier.getStockCount();
            }
            return count;
        }

        public int getMatched() {
            int count = 0;
            for (TierPremium tier : tiers) {
                count += tier.getMatched();
            }
            return count;
        }

        /**
         * 当日档位行里的最高板，即"前一天涨停池最高板"。动态中位线的唯一依据。
         *
         * <p>刻意不用 {@code t_daily_record.max_consecutive_limit}：那是<b>今天</b>的高度，
         * 和这批档位行量的不是同一天。命中 {@link #MAX_BOARD} 封顶时真实最高板可能更大，
         * 这条偏差由 {@link #maxBoardCapped()} 说出去，不藏。
         */
        public int maxBoard() {
            int max = 0;
            for (TierPremium tier : tiers) {
                if (tier.getBoard() > max) {
                    max = tier.getBoard();
                }
            }
            return max;
        }

        /** 封顶生效了：真实最高板 ≥8 板，半线只能按 8 取，中位线会比实际偏低一档。 */
        public boolean maxBoardCapped() {
            return maxBoard() >= MAX_BOARD;
        }

        /** 这一档当天算哪一组。 */
        public PremiumGroup groupOf(TierPremium tier) {
            return tier.getGroup(maxBoard());
        }

        /**
         * 组聚合。均值是把组内所有档的样本直接合并再平均，不是"逐档均值再按家数加权"——
         * 后者在家数相同的一档里会因为四舍五入而漂移，前者才是"这一组全部样本的均值"。
         *
         * @return 该组没有任何可用样本时 avgPct 为 null（未评），不是 0
         */
        public GroupPremium group(PremiumGroup group) {
            int h = maxBoard();
            int count = 0;
            int matched = 0;
            BigDecimal sum = BigDecimal.ZERO;
            List<Integer> boards = new ArrayList<>();
            for (TierPremium tier : tiers) {
                if (tier.getGroup(h) != group) {
                    continue;
                }
                count += tier.getStockCount();
                matched += tier.getMatched();
                sum = sum.add(tier.sum);
                boards.add(tier.getBoard());
            }
            return new GroupPremium(group, count, matched, boards, sum);
        }
    }

    /** 低/中/高任一组的聚合结果。 */
    public static final class GroupPremium {
        private final PremiumGroup group;
        private final int stockCount;
        private final int matched;
        private final List<Integer> boards;
        private final BigDecimal sum;

        GroupPremium(PremiumGroup group, int stockCount, int matched, List<Integer> boards, BigDecimal sum) {
            this.group = group;
            this.stockCount = stockCount;
            this.matched = matched;
            this.boards = boards;
            this.sum = sum;
        }

        public PremiumGroup getGroup() {
            return group;
        }

        public int getStockCount() {
            return stockCount;
        }

        public int getMatched() {
            return matched;
        }

        /** 该组实际有人的档位，用于判断"高位整组空缺"还是"高位全是脏值"。 */
        public List<Integer> getBoards() {
            return boards;
        }

        public boolean isEmpty() {
            return matched == 0;
        }

        public BigDecimal getAvgPct() {
            return matched == 0 ? null
                    : sum.divide(BigDecimal.valueOf(matched), 2, RoundingMode.HALF_UP);
        }
    }
}
