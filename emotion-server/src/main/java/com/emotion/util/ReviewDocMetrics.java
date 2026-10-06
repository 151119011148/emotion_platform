package com.emotion.util;

import com.emotion.entity.DailyRecord;
import com.emotion.vo.MarketStocksVO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复盘文档的<b>派生读数</b>：把已经取到手里的数据算成他手写复盘里那些「库里没有列、但每天要抄」的数。
 *
 * <p>纯函数，不查库、不联网。每个口径都在这写死并写明<b>分母是什么</b>——这几个数他会拿去
 * 和开盘啦/东财的读数对，口径不一致比缺数更容易误导，所以宁可少算也不换算法。
 *
 * <ul>
 *   <li>红盘率 = 涨 /(涨 + 跌)。<b>库里没有平盘家数</b>，而他的手记口径分母含平盘（898/5562≈16%），
 *       少一个平盘分母会让这个数比他自己的读数略高，所以渲染时要带出分母口径。</li>
 *   <li>封板率 = 涨停池家数 /(涨停池 + 炸板池)，取自 {@code t_market_stock} 的池计数，
 *       与 {@code broken_board_rate} 无关——后者是评分维度里的口径，两处一旦打架没人说得清。</li>
 *   <li>连板家数 = 2 板及以上的涨停家数（梯队各档求和），首板不算。</li>
 *   <li>一字板数 = <b>整个涨停池</b>里首封 ≤09:30:00 且当日零炸板的家数，判据与行业快照的 yizi_cnt
 *       同源，只是不限前 5 个板块。</li>
 *   <li>量能增减 = 当日成交额 − 上一<b>记录</b>成交额。上一记录未必是昨天，渲染方负责标注。</li>
 * </ul>
 *
 * <p>他文档里的「12 天 6 板」这类<b>非连续口径</b>这里一律不算：库里 {@code break_count} 是当日炸板
 * 次数（zbc），推不出「N 天」这个跨度，起算段怎么定是他自己的规则，猜错比不猜更坏。
 */
public final class ReviewDocMetrics {

    /** 红盘率 %（四舍五入整数）。涨或跌缺一个就 null——半个读数不配叫比率。 */
    public final Integer redRate;
    /** 封板率 %。两个池计数任意一个为 null 就 null。 */
    public final Integer sealRate;
    /** 2 板及以上家数。没有盘面明细时 null（区别于 0：0 是"今天真的全是首板"）。 */
    public final Integer lianbanCount;
    /** 一字板数（涨停池全池）。没有盘面明细时 null。 */
    public final Integer yiziCount;
    /** 较上一记录的成交额差（亿），正为放量负为缩量。缺任一侧就 null。 */
    public final BigDecimal volumeDelta;
    /** 当日成交额（亿），原样带过来给渲染方少绕一次。 */
    public final BigDecimal volume;
    /** 跌停按行业分桶，家数降序——【二】那张「弱/死方向」表的原料。 */
    public final List<Sector> weakSectors;

    private ReviewDocMetrics(Integer redRate, Integer sealRate, Integer lianbanCount, Integer yiziCount,
                             BigDecimal volumeDelta, BigDecimal volume, List<Sector> weakSectors) {
        this.redRate = redRate;
        this.sealRate = sealRate;
        this.lianbanCount = lianbanCount;
        this.yiziCount = yiziCount;
        this.volumeDelta = volumeDelta;
        this.volume = volume;
        this.weakSectors = weakSectors;
    }

    /** 一个桶：行业名 + 跌停家数（分桶时递增）+ 代表票（按跌幅最深排前）。 */
    public static final class Sector {
        public final String industry;
        public int count;
        public final List<String> leaders = new ArrayList<>();
        /** 该行业里的大面数；0 表示这一行是纯跌停、没有大面。 */
        public int bigLossCount;

        Sector(String industry, int count) {
            this.industry = industry;
            this.count = count;
        }
    }

    /** 全空的一份：服务层取不到任何明细时用它，渲染方照样能一句"—"走天下。 */
    public static ReviewDocMetrics empty() {
        return new ReviewDocMetrics(null, null, null, null, null, null, new ArrayList<Sector>());
    }

    /**
     * @param today      当日读数（成交额、涨跌家数）
     * @param prev       上一记录，只为量能差；null 则 volumeDelta=null
     * @param ztPool     涨停池家数，null = 这天没拉过盘面明细
     * @param zbPool     炸板池家数
     * @param stocks     盘面明细（连板梯队、跌停名单）；null 或 available=false 时相关项留 null
     * @param yiziCount  涨停池全池一字板家数，null = 这天没拉过盘面明细
     */
    public static ReviewDocMetrics from(DailyRecord today, DailyRecord prev, Integer ztPool, Integer zbPool,
                                        MarketStocksVO stocks, Integer yiziCount) {
        Integer red = today == null ? null : rate(today.getUpCount(), today.getDownCount());

        Integer seal = null;
        if (ztPool != null && zbPool != null && ztPool + zbPool > 0) {
            seal = rate(ztPool, zbPool);
        }

        Integer lianban = null;
        if (stocks != null && stocks.isAvailable()) {
            int n = 0;
            for (MarketStocksVO.Tier t : stocks.getLadder()) {
                n += t.getStocks().size();
            }
            lianban = n;
        }

        BigDecimal delta = null;
        if (today != null && prev != null && today.getTotalVolume() != null && prev.getTotalVolume() != null) {
            delta = today.getTotalVolume().subtract(prev.getTotalVolume()).setScale(2, RoundingMode.HALF_UP);
        }

        return new ReviewDocMetrics(red, seal, lianban, yiziCount, delta,
                today == null ? null : today.getTotalVolume(),
                weakSectors(stocks));
    }

    /**
     * 跌停池按 {@code t_market_stock.industry}（东财 hybk 口径）分桶。
     *
     * <p>用东财行业而不是通达信主归属，是因为这份数据已经随盘面明细一起取到手了，而板块快照
     * 只覆盖涨停侧——跌停侧要通达信口径得新跑一次关联。渲染方负责把口径名写清，别让他当成
     * 自己手里那个「光通信/CPO」的题材桶。
     */
    public static List<Sector> weakSectors(MarketStocksVO stocks) {
        List<Sector> out = new ArrayList<>();
        if (stocks == null || !stocks.isAvailable()) {
            return out;
        }
        Map<String, Sector> byIndustry = new LinkedHashMap<>();
        for (MarketStocksVO.Item it : stocks.getLimitDown()) {
            String key = it.getIndustry() == null || it.getIndustry().trim().isEmpty()
                    ? "未分类" : it.getIndustry().trim();
            Sector s = byIndustry.get(key);
            if (s == null) {
                s = new Sector(key, 0);
                byIndustry.put(key, s);
            }
            s.count++;
            if (s.leaders.size() < 4) {
                s.leaders.add(label(it));
            }
        }
        // 大面不单独成桶：它是"这个行业伤得多深"的强度标记，并进同名的那一行
        for (MarketStocksVO.Item it : stocks.getBigLoss()) {
            String key = it.getIndustry() == null || it.getIndustry().trim().isEmpty()
                    ? "未分类" : it.getIndustry().trim();
            Sector s = byIndustry.get(key);
            if (s != null) {
                s.bigLossCount++;
            }
        }
        out.addAll(byIndustry.values());
        out.sort((a, b) -> b.count != a.count ? Integer.compare(b.count, a.count)
                : Integer.compare(b.bigLossCount, a.bigLossCount));
        return out;
    }

    /** 名字后跟跌幅：光看名单看不出是"跌得多"还是"跌得少"，而他这一栏要的就是杀伤力排序。 */
    private static String label(MarketStocksVO.Item it) {
        String pct = it.getPct() == null ? "" : signed(it.getPct());
        return it.getName() + pct;
    }

    private static String signed(BigDecimal v) {
        String s = v.stripTrailingZeros().toPlainString();
        return v.signum() > 0 ? "(+" + s + "%)" : "(" + s + "%)";
    }

    /** a /(a + b) 的百分数。分母为 0 返回 null——全市场零涨跌时不写一个假 0%。 */
    private static Integer rate(Integer a, Integer b) {
        if (a == null || b == null) {
            return null;
        }
        int total = a + b;
        if (total <= 0) {
            return null;
        }
        return (int) Math.round(a * 100.0 / total);
    }
}
