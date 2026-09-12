package com.emotion.market;

import java.math.BigDecimal;

import com.emotion.entity.MarketStock;

/**
 * D5 高位生态（阵眼·抱团·监管）的全部纯判据：行为五态、各档打分、交叉信号与强制风控阈值。
 *
 * <p>与 {@link MarketMetrics}/{@link AnchorMetrics} 同一哲学：只做推导、不碰 DB 与网络，
 * 取数在 {@code HighEcoMetricsService}，这里每个函数都能用内存 fixture 单测。档位与
 * 《D5·高位生态融合版 PRD v2.0/v2.1》四子项表逐字对应，改动即改口径、必须连测试一起改。
 */
public final class HighEcoMetrics {

    private HighEcoMetrics() {
    }

    // ---------------- 阵眼行为五态 ----------------

    public static final String JIN_JIA = "JIN_JIA";     // 晋级
    public static final String FAN_BAO = "FAN_BAO";    // 反包
    public static final String HANG_TIAO = "HANG_TIAO";// 抗跌
    public static final String DUAN_BAN = "DUAN_BAN";  // 断板
    public static final String HE_PAN = "HE_PAN";      // 核按钮

    /** 行为分：晋级100/反包80/抗跌60/断板20/核按钮0。未知态=未评（null）。 */
    public static Integer actionScore(String action) {
        if (action == null) {
            return null;
        }
        switch (action) {
            case JIN_JIA:
                return 100;
            case FAN_BAO:
                return 80;
            case HANG_TIAO:
                return 60;
            case DUAN_BAN:
                return 20;
            case HE_PAN:
                return 0;
            default:
                return null;
        }
    }

    public static String actionLabel(String action) {
        if (action == null) {
            return "未评";
        }
        switch (action) {
            case JIN_JIA:
                return "晋级";
            case FAN_BAO:
                return "反包";
            case HANG_TIAO:
                return "抗跌";
            case DUAN_BAN:
                return "断板";
            case HE_PAN:
                return "核按钮";
            default:
                return action;
        }
    }

    /**
     * 五态判定（用 t_market_stock 前后两日池归属）：
     * 今日 ZT 且连板=昨+1 → 晋级；昨不在 ZT 今 ZT → 反包；
     * 今 DT 池或 big_loss=1 → 核按钮；今 ZB 或三池皆无 → 断板；其余（今 ZT 但板数对不上）→ 抗跌。
     *
     * @param todayZt       今日在涨停池
     * @param todayBoard    今日 consecutive（仅 ZT 池有）
     * @param todayZb       今日在炸板池
     * @param todayDt       今日在跌停池
     * @param bigLoss       今日是否大面（ZB 行 big_loss=1）
     * @param prevZt        昨日在涨停池
     * @param prevBoard     昨日 consecutive
     */
    public static String actionOf(boolean todayZt, Integer todayBoard, boolean todayZb, boolean todayDt,
                                  boolean bigLoss, boolean prevZt, Integer prevBoard) {
        if (todayZt) {
            if (prevZt && prevBoard != null && todayBoard != null && todayBoard == prevBoard + 1) {
                return JIN_JIA;
            }
            if (!prevZt) {
                return FAN_BAO;
            }
            return HANG_TIAO;
        }
        if (todayDt || bigLoss) {
            return HE_PAN;
        }
        // 炸板池，或断板后三池皆无（非跌停）——断板日本最需要继续盯住它
        return DUAN_BAN;
    }

    // ---------------- 高位阈值（动态，承接 PRD 3.1） ----------------

    /** H>=5: 高位=5板及以上；H<5: 高位=H-1板及以上（最高板+次高板，至少 3 板）。 */
    public static int highThreshold(int h) {
        return h >= 5 ? 5 : Math.max(3, h - 1);
    }

    // ---------------- 子项1：阵眼个体 ----------------

    /** 高度分：板数 vs H，=H 100 / H-1 80 / H-2 60 / 更低 30；无板数=未评。 */
    public static Integer heightScore(Integer board, int h) {
        if (board == null || h <= 0) {
            return null;
        }
        int gap = h - board;
        if (gap <= 0) {
            return 100;
        }
        if (gap == 1) {
            return 80;
        }
        if (gap == 2) {
            return 60;
        }
        return 30;
    }

    /**
     * 封板质量分：一字100 / 换手回封80 / 烂板(开板未回封)40 / 断板核按钮0。
     * 一字判据：封住(ZT)、开板次数 0、首次封板时间不晚于 09:30:00；封住但开过板=换手回封；
     * 封住且无开板时间记录（历史行）按换手板 80，不冒充一字。
     */
    public static Integer sealScore(String pool, Integer breakCount, Integer firstSealTime, Integer bigLoss) {
        boolean zt = MarketStock.POOL_LIMIT_UP.equals(pool);
        boolean zb = MarketStock.POOL_BROKEN.equals(pool);
        boolean dt = MarketStock.POOL_LIMIT_DOWN.equals(pool);
        if (dt || (bigLoss != null && bigLoss == 1 && !zt)) {
            return 0;
        }
        if (zb) {
            return 40;
        }
        if (!zt) {
            return 0; // 断板后三池皆无
        }
        if (breakCount != null && breakCount > 0) {
            return 80;
        }
        if (firstSealTime != null && firstSealTime <= 93000) {
            return 100;
        }
        return 80;
    }

    /** 主线一致性：阵眼行业==日内核心行业 100，否则 60（错位只在此处扣分，D2/D3 不再连乘）。 */
    public static int consistScore(boolean aligned) {
        return aligned ? 100 : 60;
    }

    /** 单阵眼四子项合成：行为40%+高度25%+封板20%+一致15%；任一未评按已评权重归一，全缺=null。 */
    public static Integer anchorOneScore(Integer action, Integer height, Integer seal, Integer consist) {
        return weighted(
                new int[] {40, 25, 20, 15},
                new Integer[] {action, height, seal, consist});
    }

    // ---------------- 子项2：抱团与资金 ----------------

    /** 高位家数占比（%，分母=当日连板≥2 家数）：20-40=100；10-20/40-60=70；>60=40；<10=50。 */
    public static int ratioScore(double pct) {
        if (pct >= 20 && pct <= 40) {
            return 100;
        }
        if (pct > 60) {
            return 40;
        }
        if (pct >= 10) {
            return 70;
        }
        return 50;
    }

    /** 高位封单集中度（%）：≤50=90；≤70=70；>70=40。 */
    public static int sealRatioScore(double pct) {
        if (pct <= 50) {
            return 90;
        }
        if (pct <= 70) {
            return 70;
        }
        return 40;
    }

    /** 空间板唯一性（consecutive==H 的家数）：2-3=100；≥4=70；唯一或 0=50（孤军）。 */
    public static int topUniqueScore(int topCount) {
        if (topCount >= 2 && topCount <= 3) {
            return 100;
        }
        if (topCount >= 4) {
            return 70;
        }
        return 50;
    }

    /** 梯队支撑：2..H 板无断层 100，有断层 40。 */
    public static int tierScore(boolean gap) {
        return gap ? 40 : 100;
    }

    /** 高位溢价（%）：>3=95；1-3=80；0-1=65；-3~0=35；<-3=10。 */
    public static int premiumScore(double pct) {
        if (pct > 3) {
            return 95;
        }
        if (pct >= 1) {
            return 80;
        }
        if (pct >= 0) {
            return 65;
        }
        if (pct >= -3) {
            return 35;
        }
        return 10;
    }

    /** 高位晋级率（%）：≥60=95；40-60=80；25-40=65；15-25=45；<15=20。 */
    public static int jrScore(double pct) {
        if (pct >= 60) {
            return 95;
        }
        if (pct >= 40) {
            return 80;
        }
        if (pct >= 25) {
            return 65;
        }
        if (pct >= 15) {
            return 45;
        }
        return 20;
    }

    /** 结构质量 60% + 资金强度 40%。 */
    public static int coalitionScore(int structure, int strength) {
        return (int) Math.round(0.60 * structure + 0.40 * strength);
    }

    // ---------------- 子项3：监管压制 ----------------

    /** 监管家数（SEVERE/EXCH 在列）：0=100；≤2=80；≤5=55；≤10=30；>10=10。 */
    public static int pressureCountScore(int count) {
        if (count == 0) {
            return 100;
        }
        if (count <= 2) {
            return 80;
        }
        if (count <= 5) {
            return 55;
        }
        if (count <= 10) {
            return 30;
        }
        return 10;
    }

    /** 高位监管占比（%）：0=100；≤30=70；≤60=40；>60=15。 */
    public static int highSurvRatioScore(double pct) {
        if (pct == 0) {
            return 100;
        }
        if (pct <= 30) {
            return 70;
        }
        if (pct <= 60) {
            return 40;
        }
        return 15;
    }

    /** 监管扩散度（同行业最多监管家数）：0=100；1=85；≤3=50；>3=20。 */
    public static int spreadScore(int maxSector) {
        if (maxSector == 0) {
            return 100;
        }
        if (maxSector == 1) {
            return 85;
        }
        if (maxSector <= 3) {
            return 50;
        }
        return 20;
    }

    // ---------------- 子项4：监管反馈（五态） ----------------

    /**
     * 反馈分：核按钮≥1 → 0；否则均涨 >5→70（监管无效/亢奋）、>0→85（红盘消化）、
     * >-7→50（绿盘分歧）、其余 25。均价缺失（全员没取到价）=未评 null。
     */
    public static Integer feedbackScore(int nuke, BigDecimal avgPct) {
        if (nuke >= 1) {
            return 0;
        }
        if (avgPct == null) {
            return null;
        }
        double v = avgPct.doubleValue();
        if (v > 5) {
            return 70;
        }
        if (v > 0) {
            return 85;
        }
        if (v > -7) {
            return 50;
        }
        return 25;
    }

    // ---------------- 交叉信号（PRD 六、节） ----------------

    /** 抱团瓦解前兆：高位晋级率≥50% 且中位断层 且 高位监管占比>30%。 */
    public static boolean coalitionRisk(Double highJrPct, boolean midGap, double highSurvPct) {
        return highJrPct != null && highJrPct >= 50 && midGap && highSurvPct > 30;
    }

    /** 死亡结构：空间板唯一 且 高位监管占比≥50% 且 监管股核按钮≥1。 */
    public static boolean deathStructure(int topCount, double highSurvPct, int nuke) {
        return topCount == 1 && highSurvPct >= 50 && nuke >= 1;
    }

    /** 监管无效-情绪亢奋：监管家数≥3、均涨>5%、无核按钮。 */
    public static boolean monitorIgnored(int survCount, BigDecimal avgPct, int nuke) {
        return survCount >= 3 && avgPct != null && avgPct.doubleValue() > 5 && nuke == 0;
    }

    /** 监管生效-退潮加速：核按钮≥1 或均涨<-7%。均价未知且无核按钮时不触发。 */
    public static boolean monitorWorks(int nuke, BigDecimal avgPct) {
        return nuke >= 1 || (avgPct != null && avgPct.doubleValue() < -7);
    }

    /** 板块级监管压制：同行业监管≥3 家 且 该行业中位晋级率<15%（率未知=false）。 */
    public static boolean sectorPressure(int maxSector, Double sectorMidJrPct) {
        return maxSector >= 3 && sectorMidJrPct != null && sectorMidJrPct < 15;
    }

    /**
     * 龙头易主分级（人工阵眼 vs 市场实际最高板）：
     * 3=阵眼失效（人工阵眼今日断板/核按钮且最高板已易主）；2=龙头易主（落后≥2 板）；
     * 1=阵眼走弱（落后 1 板或同板数不同票）；0=阵眼仍是市场核心。
     *
     * @param sameCode      人工总龙头是否就是实际最高板
     * @param gap           实际最高板 - 阵眼板数
     * @param anchorBroke   人工总龙头今日是否断板/核按钮（不在 ZT 池）
     */
    public static int handoverLevel(boolean sameCode, int gap, boolean anchorBroke) {
        if (sameCode) {
            return 0;
        }
        if (anchorBroke) {
            return 3;
        }
        if (gap >= 2) {
            return 2;
        }
        return 1;
    }

    /** 2..H 板任一档位今日无人=断层。counts 键=板高，值=家数。 */
    public static boolean hasTierGap(java.util.Map<Integer, Integer> counts, int h) {
        for (int b = 2; b <= h; b++) {
            if (counts.getOrDefault(b, 0) == 0) {
                return true;
            }
        }
        return false;
    }

    /** 中位（3-4 板）断层：两档今日都无人（H>=3 才有意义）。 */
    public static boolean midGap(java.util.Map<Integer, Integer> counts, int h) {
        if (h < 3) {
            return false;
        }
        return counts.getOrDefault(3, 0) == 0 && counts.getOrDefault(4, 0) == 0;
    }

    // ---------------- 强制风控（PRD 七、节） ----------------

    /** 空间板处于 SEVERE/EXCH 监管且当日断板/核按钮 → 强制退潮。 */
    public static boolean forceMonitoredTopBreak(boolean topMonitored, boolean topBrokeOrNuke) {
        return topMonitored && topBrokeOrNuke;
    }

    /** 监管股核按钮≥1 且空间板唯一 → 死亡结构强制空仓。 */
    public static boolean forceDeath(int nuke, int topCount) {
        return nuke >= 1 && topCount == 1;
    }

    // ---------------- 加权小工具 ----------------

    /** 权重和（此处全是整数权）加权平均；全部未评=null，部分缺按已评权重归一。 */
    static Integer weighted(int[] weights, Integer[] scores) {
        int num = 0;
        int den = 0;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] != null) {
                num += weights[i] * scores[i];
                den += weights[i];
            }
        }
        return den == 0 ? null : (int) Math.round((double) num / den);
    }
}
