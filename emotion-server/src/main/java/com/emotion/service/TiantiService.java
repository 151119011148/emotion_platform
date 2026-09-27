package com.emotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.market.StockPatterns;
import com.emotion.vo.TiantiVO;

/**
 * 连板生态（PRD P2）：当日涨停池按<b>板高</b>从 H 到 2 逐层组成天梯（左对齐、全宽），
 * 每层给个股（龙头分工标签 + 一字/T字/换手形态 + 封单额，同层按封单额降序）；
 * 3 板及以上层把晋级失败个股并入同层（前端灰色标注），失败去向依次查涨停/炸板/跌停池，
 * 最新交易日再用腾讯批量报价兜底"未触板"个股的当日涨跌幅。
 *
 * <p>三层动态归属（低=2/中=3-4/高=5板+）按
 * {@link LadderMetricsService#layerIndex} 打在每层上，
 * 保证天梯的层名与打分三层对得上。龙头标签与判定依据全部取自 {@link PrdMetricsService} 同一份快照。
 */
@Service
public class TiantiService {

    private static final Logger log = LoggerFactory.getLogger(TiantiService.class);

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    // 三层（2026-09-13 简化，对齐高位生态 D5）：低位=2板 / 中位=3-4板 / 高位=5板+
    private static final String[] LAYER_LABELS = {"低位", "中位", "高位"};
    /** 失败明细从 n≥3 层下探到 n≥2 层（1进2=昨首板今兑现），是低位溢价全样本的关键来源。 */
    private static final int QUOTE_FALLBACK_MIN_BOARD = 2;

    // ---- 封板形态分档边界（东财 fbt 首封时间 HHMMSS，0 炸板判"一封到底"）----
    private static final int SEAL_ONE_LINE = 92500;   // 一字：集合竞价封死
    private static final int SEAL_FLASH = 93030;      // 早盘秒板：开盘 30 秒内
    private static final int SEAL_LINE = 93500;       // 早盘直线：开盘 5 分钟内
    private static final int SEAL_EARLY = 100000;     // 早盘板
    private static final int SEAL_MORNING = 113000;   // 上午板
    private static final int SEAL_AFTERNOON = 143000; // 午后板（之后=尾盘板）

    /** 流通市值阈值：≤35 亿（东财 ltsz）。"一般不会超过20亿"是软性特征，瑞尔特22~24亿属典型小盘断魂刀，故放宽到 35 亿。 */
    private static final BigDecimal DUANDAO_FLOAT_MV_CAP = new BigDecimal("3500000000");
    /** 封单金额硬阈值：≥10 亿（东财 fund）。 */
    private static final BigDecimal DUANDAO_SEAL_MIN = new BigDecimal("1000000000");
    /** 封成比阈值：封单/成交 ≥3（封单相对成交巨大、不给上车；瑞尔特 3.88~5.67）。 */
    private static final int DUANDAO_SEAL_RATIO = 3;
    /** 换手率阈值：<5%（几乎没换手）。 */
    private static final BigDecimal DUANDAO_TURNOVER_MAX = new BigDecimal("5");
    /** "锁死不给上车"= 一字或早盘秒板（首封≤93030）且全天 0 炸板。 */
    private static final int DUANDAO_FLASH_LINE_MAX = 93030;

    /* 破壁判定 v11 的两个相位：线钉着倒计时等追平（阶梯往下降）↔ 龙在榜的周期态 */
    private static final int PHASE_LINE = 0;
    private static final int PHASE_CYCLE = 1;

    private final PrdMetricsService prdMetrics;
    private final MarketStockMapper marketStockMapper;
    private final CrossDayQuoteAugmentor crossDayQuote;
    private final ZtPerfStore ztPerfStore;
    private final ManualLeaderService manualLeaderService;
    private final IndustryClassifyService industryClassify;

    public TiantiService(PrdMetricsService prdMetrics, MarketStockMapper marketStockMapper,
                         CrossDayQuoteAugmentor crossDayQuote, ZtPerfStore ztPerfStore,
                         ManualLeaderService manualLeaderService,
                         IndustryClassifyService industryClassify) {
        this.prdMetrics = prdMetrics;
        this.marketStockMapper = marketStockMapper;
        this.crossDayQuote = crossDayQuote;
        this.ztPerfStore = ztPerfStore;
        this.manualLeaderService = manualLeaderService;
        this.industryClassify = industryClassify;
    }

    public TiantiVO vo(Long userId, LocalDate requested) {
        LocalDate date = requested != null ? requested : LocalDate.now(CN);
        PrdMetricsService.Snapshot snap = prdMetrics.snapshot(userId, date);

        List<MarketStock> zt = listPool(date, MarketStock.POOL_LIMIT_UP);
        List<MarketStock> zb = listPool(date, MarketStock.POOL_BROKEN);
        List<MarketStock> dt = listPool(date, MarketStock.POOL_LIMIT_DOWN);
        LocalDate prev = marketStockMapper.prevDetailDate(date);
        List<MarketStock> prevZT = prev == null ? new ArrayList<MarketStock>()
                : listPool(prev, MarketStock.POOL_LIMIT_UP);
        Map<String, Integer> prevBoard = new HashMap<>();
        for (MarketStock row : prevZT) {
            prevBoard.put(row.getCode(), row.getConsecutive() == null ? 1 : row.getConsecutive());
        }
        // 今日个股按代码索引（涨停 + 炸板 + 跌停），判晋级失败后的去向
        Map<String, MarketStock> todayZtByCode = indexByCode(zt);
        Map<String, MarketStock> todayZbByCode = indexByCode(zb);
        Map<String, MarketStock> todayDtByCode = indexByCode(dt);

        TiantiVO vo = new TiantiVO();
        vo.setTradeDate(date);
        vo.setMaxBoard(snap.maxBoard);
        vo.setMainIndustry(snap.mainIndustry);
        vo.setMainlineConfirmed(snap.mainlineConfirmed);
        vo.setZtGatherPct(snap.ztGatherPct);
        vo.setHeightGatherPct(snap.heightGatherPct);
        vo.setPersistenceDays(snap.persistenceDays);
        vo.setZtTotal(snap.ztTotal);
        vo.setZbTotal(snap.zbTotal);
        int lbTotal = 0;
        for (MarketStock row : zt) {
            int b = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (b >= 2) {
                lbTotal++;
            }
        }
        vo.setLbTotal(lbTotal);

        Set<String> zong = new HashSet<>();
        if (snap.zongLong != null && snap.zongLong.getCode() != null) {
            zong.add(snap.zongLong.getCode());
        }
        Set<String> zhongJun = codes(snap.zhongJun);
        Set<String> fanBao = codes(snap.fanBao);
        String kaWeiCode = snap.kaWei == null ? null : snap.kaWei.getCode();

        int h = Math.max(snap.maxBoard, 2);
        // 人工总龙头：某日用户手动指定的身份（与自动"空间板"并列，可同可异）
        String manualLeader = manualLeaderService.codeOf(userId, date);

        // 天梯：n 从 H 往下到 2，每层独立判晋级
        List<TiantiVO.Level> levels = new ArrayList<>();
        for (int n = h; n >= 2; n--) {
            levels.add(level(n, h, zt, prevZT, prevBoard, todayZtByCode, todayZbByCode, todayDtByCode, snap,
                    zong, zhongJun, kaWeiCode, fanBao, manualLeader));
        }
        // 最新交易日：三池都没覆盖到的失败股，用腾讯实时报价兜底当日涨跌幅（历史日快照回溯不了）
        fillGoneQuotes(date, levels);
        for (TiantiVO.Level lvl : levels) {
            sortFailed(lvl.getFailed());
        }
        vo.setLevels(levels);
        return vo;
    }

    /**
     * 连板高度曲线：区间内<b>每天一个点</b>，y=当日最高板，并给出当天的动态破壁线。
     *
     * <p>SQL 是按 {@code consecutive = 当日最大值} 关联的，同一天最高板常有多只并列，会一天返回多行；
     * 直接喂图会让 X 轴日期重复、折线在同一天来回跳，所以在这里按日收敛成一点。
     *
     * <p>名单给全、按代码升序，不截断：并列最高板的家数就是曲线的信息本身（前端 tooltip 可滚动，
     * 最高板只剩 2 板的退潮日并列数十只也不裁）。排序是为了两次刷新名单顺序稳定。
     */
    public List<TiantiVO.HeightPoint> heightRange(LocalDate from, LocalDate to) {
        Map<LocalDate, TiantiVO.HeightPoint> byDate = new LinkedHashMap<>();
        for (MarketStockMapper.MaxBoardRow r : marketStockMapper.listMaxBoardRange(from, to)) {
            TiantiVO.HeightPoint p = byDate.get(r.getTradeDate());
            if (p == null) {
                p = new TiantiVO.HeightPoint();
                p.setTradeDate(r.getTradeDate());
                p.setMaxHeight(r.getMaxHeight());
                p.setStocks(new ArrayList<>());
                p.setLadder(new ArrayList<>());
                byDate.put(r.getTradeDate(), p);
            }
            p.getStocks().add(new TiantiVO.HeightStock(r.getCode(), r.getName()));
        }
        for (MarketStockMapper.LadderRow r : marketStockMapper.listLadderRange(from, to)) {
            TiantiVO.HeightPoint p = byDate.get(r.getTradeDate());
            if (p != null) {
                p.getLadder().add(new TiantiVO.LadderStock(r.getBoard(), r.getCode(), r.getName()));
            }
        }
        List<TiantiVO.HeightPoint> out = new ArrayList<>(byDate.values());
        for (TiantiVO.HeightPoint p : out) {
            p.getStocks().sort(Comparator.comparing(TiantiVO.HeightStock::getCode,
                    Comparator.nullsLast(String::compareTo)));
            p.setStockCount(p.getStocks().size());
        }
        detectBreaks(out);
        return out;
    }

    /** 冰点/常态/强市三档兜底：混沌高 ≤2 要 4 板、3~4 要 5 板、≥5 要 6 板才算真破壁。 */
    static int minAbsBreak(int chaosHigh) {
        if (chaosHigh <= 2) {
            return 4;
        }
        return chaosHigh <= 4 ? 5 : 6;
    }

    /**
     * 高度曲线的破壁判定（v13）。一句话：<b>旧龙断板那天起，破壁线钉在它的高度 H 上 H−1 个交易日；
     * 钉满就把线降到这段时间里市场够到过的最高板，再按新线钉 新线−1 天——一级一级往下走，
     * 直到某只票追平并且次日续板，新龙诞生，周期归它。</b>
     *
     * <p>"够到过的最高板"记的是<b>没越过这条线的那些天</b>，外加<b>追平失败的那次试探</b>（v13）：
     * 市场既然有票站上来过又掉下来，这个高度就还在，阶梯不许降到它下面（华瓷股份 9.22 追平 6 板、
     * 9.23 没续 → 9.24 下一级还是 6 板，新华文轩的 5 板够不着，不算试探）。失败高度只许"顶住"这一级，
     * 不许把下一级抬得比这条线还高——阶梯仍然只降不升。代价是这一级会原地再钉一轮，
     * 只要还有人反复追平又反复失败，线就停在那儿不降。
     *
     * <p>8.28 深中华Ａ 7 板、8.31 断板 → 线钉在 7 板到 9.07（8.31、9.01、9.02、9.03、9.04、9.07 六个交易日；
     * 9.01 海鸥住工 7 板追平、9.02 没续＝失败，它那个 7 也记账）→ 9.08 钉满仍记不到比 7 更低的高度，
     * 原地再钉 6 个交易日（9.08~9.15 没人够到 7，最高只到 5 板）→ 9.16 降到 5 板。
     *
     * <p>钉线期间<b>照常判试探</b>，两件事按他改的规则放宽了：
     * <ul>
     *   <li><b>试探对象看连板数，不看谁最高</b>——当天任意一只 {@code 板高 ≥ L} 的票都算追平，
     *       并列时取板高最高、再取代码最小；</li>
     *   <li><b>破壁成功比的是试探股自己的板高</b>——它次日续板（≥ 试探日 + 1 板）才算，
     *       为此判定读的是 {@link TiantiVO.HeightPoint#getLadder() 当天 2 板以上的名单}。</li>
     * </ul>
     *
     * <p>v12 放宽的那格仍在：<b>定线票不过是个挂名，它自己爬到当天最高、越过这条线，照样算一次追平</b>——
     * 8.27 阶梯降到 6 板时这条线挂到了深中华Ａ名下，8.28 它自己打到 7 板就是追平，8.31 掉榜就是没兑现。
     * 只有一种越线不算试探：<b>这一级高度本来就是它自己打上去的</b>（首日的种子、它破壁成功接棒的那一级、
     * 它断板钉住的那个 H），老龙回榜再创新高还是它自己的周期在延续（爱丽家居 8.03~8.05 停牌被按断板
     * 钉在 9 板、8.06 回到 10 板就是这种）。
     *
     * <p>试探股的次日分两种收法：它<b>续板</b>=破壁成功，阶梯停住、周期归它、线跟它抬，它断板那天再起一轮；
     * 它<b>没续板</b>（还在榜滞涨，或已经掉出名单）=破壁失败，这条线还钉着的日子只许<b>换票</b>再试——
     * 同一只票在同一条线上不重复记试探；线<b>真的降到下一级</b>时这笔账才清零（原地再钉一级还是那面壁）。
     * 线悬着而名单上已经没人站得上去时，定线票改由当天站在线上那只担任——线的高度不变，只是换个名字挂着。
     *
     * <p>取数窗口首日没有旧龙可断，按"当天板高即 H"起一级。
     */
    static void detectBreaks(List<TiantiVO.HeightPoint> pts) {
        if (pts.isEmpty()) {
            return;
        }
        int phase = PHASE_LINE;
        /**
         * 当前这一级破壁线还要钉<b>交易日</b>：钉满就降到记录到的最高板，再按新线重数。
         * {@code isChaos}/{@code oldDragonHeight} 这两个字段也读它（页面已不再画色块，字段留着）。
         */
        int holdLeft = Math.max(nz(pts.get(0).getMaxHeight()) - 1, 1);
        /** 挂账的旧龙高度 H：只用于展示（色块、卡片的"旧龙高度"），阶梯每降一级都不改它。 */
        int chaosH = nz(pts.get(0).getMaxHeight());
        /**
         * 这一级钉线期间市场够到的最高板，以及定它的那只票：没越过这条线的那些天，
         * 外加追平<b>又失败</b>的那次试探（v13）。
         */
        int recordMax = 0;
        TiantiVO.HeightStock recordStock = null;
        TiantiVO.HeightStock lineStock = topStock(pts.get(0));
        int line = chaosH;
        /**
         * 这一级破壁线的高度是<b>谁自己打上去的</b>：首日种子、破壁成功接棒的那只、断板的那条龙。
         * 只有越过别人筑的壁才算试探，它自己回榜再创新高还是那个周期在延续（爱丽家居 8.03~8.05 停牌
         * 被按断板钉在 9 板、8.06 回到 10 板就是这种）。阶梯降到下一级就清空。
         */
        String lineOwner = lineStock == null ? null : lineStock.getCode();
        String dragon = null;
        String probeCode = null;
        int probeBoard = 0;
        /** 挂着待判的那次试探是<b>哪只票</b>：它失败时要把那天的高度记进降线记录，得留个名字。 */
        TiantiVO.HeightStock probeStockRef = null;
        /** 本轮这条线上已经试探失败的票：这条线还钉着的日子只许换票，不许同一只票天天追平刷标记。 */
        Set<String> failedProbes = new HashSet<>();

        for (TiantiVO.HeightPoint p : pts) {
            int h = nz(p.getMaxHeight());

            // 先结相位转移，再按新相位处理当天：旧龙断板那天就是这一级钉线的第 1 天
            if (phase == PHASE_CYCLE && !containsCode(p, dragon)) {
                // 在位龙断板：破壁线抬到它的高度钉住 H−1 个交易日，之后一级一级往下降
                chaosH = line;
                holdLeft = Math.max(chaosH - 1, 1);
                lineOwner = dragon;
                recordMax = 0;
                recordStock = null;
                failedProbes.clear();
                dragon = null;
                probeCode = null;
                probeStockRef = null;
                phase = PHASE_LINE;
            } else if (probeCode != null) {
                int nb = boardOf(p, probeCode);
                if (nb > probeBoard) {
                    // 试探股次日继续涨停 = 破壁成功：阶梯停住，周期归它，线跟它抬
                    p.setIsBreak(Boolean.TRUE);
                    p.setPrevHigh(line);
                    p.setBreakStock(stockOf(p, probeCode));
                    dragon = probeCode;
                    lineStock = p.getBreakStock();
                    line = Math.max(line, nb);
                    lineOwner = probeCode;
                    failedProbes.clear();
                    phase = PHASE_CYCLE;
                } else {
                    // 没续板就是破壁失败——掉榜也一样：这条线还钉着，只许换票再试。
                    // 但它那天<b>确实站上过这条线</b>，这个高度要算进降线记录：市场证明过能够到这里，
                    // 阶梯就不能降到比它更低（华瓷 9.22 追平 6 板、9.23 没续 → 下一级还是 6，不降到 5）。
                    failedProbes.add(probeCode);
                    // 只许"顶住"这一级：越过这条线又失败的票（4 板线上出个 5 板）不许把下一级抬得比线还高，
                    // 阶梯仍然只降不升。
                    if (probeBoard <= line && probeBoard > recordMax) {
                        recordMax = probeBoard;
                        recordStock = probeStockRef;
                    }
                }
                probeCode = null;
                probeStockRef = null;
            }

            if (phase == PHASE_CYCLE) {
                // 在位龙还在榜：守顶或继续加板都算同一次破壁的延续，线跟它一起抬，不再判试探
                line = Math.max(line, h);
                lineStock = stockOf(p, dragon);
            } else {
                // 钉线期间照常判试探；同时记下没追平这条线的那些天里市场自己爬到的最高板
                if (h < line && h > recordMax) {
                    recordMax = h;
                    recordStock = topStock(p);
                }
                TiantiVO.HeightStock cand = probeCandidate(p, line, lineStock, failedProbes);
                if (cand != null) {
                    p.setIsProbe(Boolean.TRUE);
                    p.setProbeStock(cand);
                    probeCode = cand.getCode();
                    probeStockRef = cand;
                    probeBoard = boardOf(p, cand.getCode());
                }
                if (lineStock != null && boardOf(p, lineStock.getCode()) == h && h > line) {
                    // 挂着这条线的票自己爬到当天最高、越过这条线。8.28 深中华Ａ：这条 6 板线是阶梯从爱丽家居
                    // 那一轮降下来挂在它名下的，不是它自己筑的壁，所以这一越照样算追平，
                    // 成败仍旧看它次日续不续板（它 8.31 掉榜 = 没兑现）。它自己筑的那一级不算（老龙回榜续板）。
                    if (probeCode == null && !lineStock.getCode().equals(lineOwner)
                            && !failedProbes.contains(lineStock.getCode())) {
                        p.setIsProbe(Boolean.TRUE);
                        p.setProbeStock(lineStock);
                        probeCode = lineStock.getCode();
                        probeStockRef = lineStock;
                        probeBoard = h;
                    }
                    dragon = lineStock.getCode();
                    line = h;
                    lineOwner = lineStock.getCode();
                    failedProbes.clear();
                    phase = PHASE_CYCLE;
                } else if (h >= line && topStock(p) != null
                        && boardOf(p, lineStock == null ? null : lineStock.getCode()) < line) {
                    // 名单上已经没人挂着这条线：定线票交给当天站在线上那只，线的高度不动
                    lineStock = topStock(p);
                }
            }
            boolean pinned = phase == PHASE_LINE;
            p.setCeiling(line);
            p.setLineStock(lineStock);
            p.setIsChaos(pinned ? Boolean.TRUE : null);
            p.setOldDragonHeight(pinned ? chaosH : null);
            if (pinned) {
                if (holdLeft <= 1) {
                    int prevLine = line;
                    // 这一级钉满：线降到记录到的最高板，从明天起按新线再钉 新线−1 天。
                    // 这几天全都追平或越过了这条线（没记下更低的高度）就原地再钉一级——线不许悬空。
                    if (recordMax > 0) {
                        line = recordMax;
                        lineStock = recordStock;
                        // 降下来的这一级是退潮期市场自己爬出来的高度，不算谁筑的壁：谁越过来都算试探
                        lineOwner = null;
                    }
                    holdLeft = Math.max(line - 1, 1);
                    recordMax = 0;
                    recordStock = null;
                    // 只有真的降了一级才清换票账。失败试探的高度现在会把下一级顶回原高度，
                    // 原地再钉还是那面壁——账一清，同一只票就能反复追平同一级把线钉死。
                    if (line < prevLine) {
                        failedProbes.clear();
                    }
                } else {
                    holdLeft--;
                }
            }
        }
    }

    /**
     * 合规试探股：当天连板名单上有<b>板高 ≥ 破壁线</b>的票（不比谁最高，够线就算追平），
     * 且既不是定线票本人（同票续板只是周期延续，不算换龙）、也不是这条线上已经试探失败的票
     * （重试只许换票）。并列取板高最高的，再并列取代码最小那只。
     */
    private static TiantiVO.HeightStock probeCandidate(TiantiVO.HeightPoint p, int line,
                                                       TiantiVO.HeightStock lineStock,
                                                       Set<String> failedProbes) {
        if (lineStock == null || nz(p.getMaxHeight()) < line) {
            return null;
        }
        TiantiVO.HeightStock best = null;
        int bestBoard = 0;
        for (String code : codesOfDay(p)) {
            int b = boardOf(p, code);
            if (b < line || code.equals(lineStock.getCode()) || failedProbes.contains(code)) {
                continue;
            }
            if (best == null || b > bestBoard || (b == bestBoard && code.compareTo(best.getCode()) < 0)) {
                best = stockOf(p, code);
                bestBoard = b;
            }
        }
        return best;
    }

    /**
     * 当天要扫的连板名单：有 2 板以上的全名单就读它（试探对象只看板高够不够线，不看谁最高——
     * 当天最高 6 板时，追平 5 板线的那只未必在最高板名单上），没名单时退回并列最高板那几只。
     */
    private static List<String> codesOfDay(TiantiVO.HeightPoint p) {
        List<String> codes = new ArrayList<>();
        if (p.getLadder() != null) {
            for (TiantiVO.LadderStock s : p.getLadder()) {
                codes.add(s.getCode());
            }
        } else if (p.getStocks() != null) {
            for (TiantiVO.HeightStock s : p.getStocks()) {
                codes.add(s.getCode());
            }
        }
        return codes;
    }

    /** 这只票当天的连板数：读了 2 板以上名单就是它自己的板高，没名单时按"在最高板名单上＝当天最高板"退化。 */
    private static int boardOf(TiantiVO.HeightPoint p, String code) {
        if (code == null) {
            return 0;
        }
        if (p.getLadder() != null) {
            for (TiantiVO.LadderStock s : p.getLadder()) {
                if (code.equals(s.getCode())) {
                    return nz(s.getBoard());
                }
            }
            return 0;
        }
        return containsCode(p, code) ? nz(p.getMaxHeight()) : 0;
    }

    private static TiantiVO.HeightStock stockOf(TiantiVO.HeightPoint p, String code) {
        if (p.getStocks() != null) {
            for (TiantiVO.HeightStock s : p.getStocks()) {
                if (s.getCode().equals(code)) {
                    return s;
                }
            }
        }
        if (p.getLadder() != null) {
            for (TiantiVO.LadderStock s : p.getLadder()) {
                if (code.equals(s.getCode())) {
                    return new TiantiVO.HeightStock(s.getCode(), s.getName());
                }
            }
        }
        return null;
    }

    private static TiantiVO.HeightStock topStock(TiantiVO.HeightPoint p) {
        return p.getStocks() == null || p.getStocks().isEmpty() ? null : p.getStocks().get(0);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static boolean containsCode(TiantiVO.HeightPoint p, String code) {
        for (TiantiVO.HeightStock s : p.getStocks()) {
            if (s.getCode().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private TiantiVO.Level level(int n, int h,
                                 List<MarketStock> zt,
                                 List<MarketStock> prevZT,
                                 Map<String, Integer> prevBoard,
                                 Map<String, MarketStock> todayZtByCode,
                                 Map<String, MarketStock> todayZbByCode,
                                 Map<String, MarketStock> todayDtByCode,
                                 PrdMetricsService.Snapshot snap,
                                 Set<String> zong, Set<String> zhongJun, String kaWeiCode, Set<String> fanBao,
                                 String manualLeader) {
        TiantiVO.Level lvl = new TiantiVO.Level();
        lvl.setBoard(n);
        lvl.setLayerLabel(LAYER_LABELS[LadderMetricsService.layerIndex(n, h)]);

        // 今日 n 板个股，同层按封单金额从大到小（null 垫后），同额取涨幅大、代码小保证确定性
        List<MarketStock> onBoard = new ArrayList<>();
        for (MarketStock row : zt) {
            int b = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (b == n) {
                onBoard.add(row);
            }
        }
        onBoard.sort((a, b) -> {
            int bySeal = Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())
                    .compare(a.getSealAmount(), b.getSealAmount());
            if (bySeal != 0) {
                return bySeal;
            }
            int byPct = Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())
                    .compare(a.getChangePct(), b.getChangePct());
            if (byPct != 0) {
                return byPct;
            }
            return Comparator.nullsLast(String::compareTo).compare(a.getCode(), b.getCode());
        });

        List<TiantiVO.Row> rows = new ArrayList<>();
        Set<String> successCodes = new HashSet<>();
        for (MarketStock row : onBoard) {
            TiantiVO.Row r = rowOf(row, n, snap, zong, zhongJun, kaWeiCode, fanBao, manualLeader);
            Integer prevN = prevBoard.get(row.getCode());
            r.setPromoted(prevN == null ? null : prevN == n - 1);
            r.setFloatMv(row.getFloatMv());
            r.setTurnoverRate(row.getTurnoverRate());
            r.setSealForm(sealForm(row.getFirstSealTime(), row.getBreakCount()));
            r.setSealRatio(sealRatio(row.getSealAmount(), row.getAmount()));
            r.setOneWordKilling(isDuanDao(row, prevZT));
            rows.add(r);
            if (prevN != null && prevN == n - 1) {
                successCodes.add(r.getCode());
            }
        }
        lvl.setRows(rows);
        lvl.setCount(rows.size());

        // 失败：昨日 n-1 板，今日没封住 n 板。注意 n=2 时昨日 1 板=首板
        List<TiantiVO.FailedRow> failed = new ArrayList<>();
        for (MarketStock prev : prevZT) {
            int prevN = prev.getConsecutive() == null ? 1 : prev.getConsecutive();
            if (prevN != n - 1 || successCodes.contains(prev.getCode())) {
                continue;
            }
            failed.add(failedRow(prev, n - 1, todayZtByCode.get(prev.getCode()),
                    todayZbByCode.get(prev.getCode()), todayDtByCode.get(prev.getCode())));
        }
        lvl.setFailed(failed);
        return lvl;
    }

    private TiantiVO.Row rowOf(MarketStock row, int n, PrdMetricsService.Snapshot snap,
                                Set<String> zong, Set<String> zhongJun, String kaWeiCode, Set<String> fanBao,
                                String manualLeader) {
        TiantiVO.Row r = new TiantiVO.Row();
        r.setCode(row.getCode());
        r.setName(row.getName());
        r.setIndustry(row.getIndustry());
        r.setBoard(n);
        r.setChangePct(row.getChangePct());
        r.setBreakCount(row.getBreakCount());
        r.setSealAmount(row.getSealAmount());
        r.setFirstSealTime(row.getFirstSealTime());
        r.setPattern(StockPatterns.of(row));
        r.setRole(roleOf(row, snap.mainIndustry, zong, zhongJun, kaWeiCode, fanBao));
        // 人工总龙头与自动"空间板"并列：同只可同时持有两个标签，不同只则分开标
        r.setManualLeader(manualLeader != null && manualLeader.equals(row.getCode()));
        return r;
    }

    /**
     * 一字断魂刀打标（2026-09-17 修正）：抓"三板组"链条的<b>锁仓不给上车</b>本质，
     * 不再卡死"恰 3 板 + 三根全一字"。直线拉升是分时斜率（锁仓手法），启动板常是
     * 秒板/直线而非一字，故以「今日 + 昨日连续两天锁死(首封≤93030 且 0 炸板)」识别，
     * 叠加 流通市值≤35亿 且 (封单≥10亿 或 封成比≥3) 且 换手率<5%。
     * 「一般流通≤20亿」是软性特征，放宽到 35 亿以纳入瑞尔特(22~24亿)这类典型小盘断魂刀。
     * 「从水下直线拉升(午后突袭)」因本机日K主机均空响应无法回溯，不做判定（见类/字段注释）。
     */
    static boolean isDuanDao(MarketStock row, List<MarketStock> prevZT) {
        if (row == null) {
            return false;
        }
        if ((row.getConsecutive() == null ? 1 : row.getConsecutive()) < 2) {
            return false;
        }
        // 今日不给上车（一字/早盘秒板，一封到底）——启动板可能就是今天的秒板
        if (!isLocked(row) || !isPrevLocked(prevZT, row.getCode())) {
            return false;
        }
        BigDecimal mv = row.getFloatMv();
        BigDecimal seal = row.getSealAmount();
        if (mv == null || seal == null
                || mv.compareTo(BigDecimal.ZERO) <= 0
                || mv.compareTo(DUANDAO_FLOAT_MV_CAP) > 0) {
            return false;
        }
        // 封单金额 ≥10亿，或封成比(封单/成交额) ≥3
        boolean sealOk = seal.compareTo(DUANDAO_SEAL_MIN) >= 0;
        BigDecimal amount = row.getAmount();
        if (!sealOk && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            sealOk = seal.divide(amount, 6, RoundingMode.HALF_UP)
                    .compareTo(BigDecimal.valueOf(DUANDAO_SEAL_RATIO)) >= 0;
        }
        if (!sealOk) {
            return false;
        }
        // 换手率 <5%（几乎没换手）
        BigDecimal hs = row.getTurnoverRate();
        return hs != null && hs.compareTo(DUANDAO_TURNOVER_MAX) < 0;
    }

    /** 今日"锁死不给上车"：首封 ≤93030（一字或早盘秒板）且全天 0 炸板。 */
    private static boolean isLocked(MarketStock r) {
        Integer fbt = r == null ? null : r.getFirstSealTime();
        if (fbt == null) {
            return false;
        }
        int brk = r.getBreakCount() == null ? 0 : r.getBreakCount();
        return fbt <= DUANDAO_FLASH_LINE_MAX && brk == 0;
    }

    /** 昨日池里同代码任意板数只要当日锁死即可（连续≥2日不给上车）。 */
    private static boolean isPrevLocked(List<MarketStock> pool, String code) {
        if (pool == null) {
            return false;
        }
        for (MarketStock r : pool) {
            if (code.equals(r.getCode()) && isLocked(r)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 给定交易日与一批代码，挑出其中命中「一字断魂刀」的代码。
     *
     * <p>天梯页是「按板数分层、逐只判」，候选池是「给一批票、问哪些判中」——入口不同，
     * 判据同一个（{@link #isDuanDao}）。判据要看「今日 + 昨日连续锁死」，所以两天都得取。
     *
     * <p>两处有意取舍：
     * <ul>
     *   <li>只走 {@code selectList}，不用 {@link #listPool}——后者会顺带跑行业归类，
     *       而断魂刀判据一个字都不看行业，没必要为此多干一份活。</li>
     *   <li>前一日取不到（库里最早那天）直接返回空集，<b>不把「昨日锁死」当默认成立</b>：
     *       判不出来就说判不出来。这里宁可漏标，也不要凭空标出一个「买得进」的假信号。</li>
     * </ul>
     */
    public Set<String> duanDaoCodes(LocalDate date, Collection<String> codes) {
        Set<String> hit = new HashSet<>();
        if (date == null || codes == null || codes.isEmpty()) {
            return hit;
        }
        Set<String> want = new HashSet<>(codes);
        List<MarketStock> zt = poolOn(date, MarketStock.POOL_LIMIT_UP);
        LocalDate prev = marketStockMapper.prevDetailDate(date);
        List<MarketStock> prevZT = prev == null ? new ArrayList<MarketStock>()
                : poolOn(prev, MarketStock.POOL_LIMIT_UP);
        if (prevZT.isEmpty()) {
            return hit;
        }
        for (MarketStock row : zt) {
            if (want.contains(row.getCode()) && isDuanDao(row, prevZT)) {
                hit.add(row.getCode());
            }
        }
        return hit;
    }

    /** 只取池子、不做行业归类。给不看行业的判据用（见 {@link #duanDaoCodes}）。 */
    private List<MarketStock> poolOn(LocalDate date, String pool) {
        return marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, pool));
    }

    /** 封板形态分档：一字/早盘秒板/早盘直线/早盘板/上午板/午后板/尾盘板；炸板 n 次回显"(回头n)"。首封时间缺失返回 null。 */
    static String sealForm(Integer firstSealTime, Integer breakCount) {
        if (firstSealTime == null) {
            return null;
        }
        int fbt = firstSealTime;
        String form;
        if (fbt <= SEAL_ONE_LINE) {
            form = "一字";
        } else if (fbt <= SEAL_FLASH) {
            form = "早盘秒板";
        } else if (fbt <= SEAL_LINE) {
            form = "早盘直线";
        } else if (fbt <= SEAL_EARLY) {
            form = "早盘板";
        } else if (fbt <= SEAL_MORNING) {
            form = "上午板";
        } else if (fbt <= SEAL_AFTERNOON) {
            form = "午后板";
        } else {
            form = "尾盘板";
        }
        int brk = breakCount == null ? 0 : breakCount;
        return brk > 0 ? form + "(回头" + brk + ")" : form;
    }

    /** 封成比 = 封单额 / 成交额（元/元，无量纲），防除零返回 null。 */
    private static BigDecimal sealRatio(BigDecimal seal, BigDecimal amount) {
        if (seal == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return seal.divide(amount, 2, RoundingMode.HALF_UP);
    }

    private TiantiVO.FailedRow failedRow(MarketStock prev, int prevBoardN,
                                         MarketStock todayRow, MarketStock bombRow, MarketStock downRow) {
        TiantiVO.FailedRow f = new TiantiVO.FailedRow();
        f.setCode(prev.getCode());
        f.setName(prev.getName());
        f.setIndustry(prev.getIndustry());
        f.setPrevBoard(prevBoardN);
        if (todayRow != null) {
            // 今日仍封住涨停但没到 n 板（如昨 2 板今天还是 2 板/退回 1 板）
            f.setTodayStatus("ZT");
            f.setChangePct(todayRow.getChangePct());
            f.setPattern(StockPatterns.of(todayRow));
        } else if (bombRow != null) {
            f.setTodayStatus("ZB");
            f.setChangePct(bombRow.getChangePct());
            f.setPullbackPct(bombRow.getPullbackPct());
        } else if (downRow != null) {
            // 今日跌停：此前只查涨停/炸板池，这批个股被误报成"明细未覆盖"
            f.setTodayStatus("DT");
            f.setChangePct(downRow.getChangePct());
        } else {
            // 今日没进任何池：免费源没有全市场逐只行情，最新交易日再尝试腾讯报价兜底
            f.setTodayStatus("GONE");
        }
        return f;
    }

    /**
     * 最新交易日才补腾讯（库里没有更晚明细日）；先落与打分同源的当日三池 ∪ t_zt_perf（快照日批量采集），
     * 历史日只能靠这两级，最新交易日再把腾讯报价兜到底。现在 1进2（n=2 层，昨首板今未触板）也覆盖，
     * 不再出现"打分引擎有低位大面、天梯名单却标明细未覆盖"的口径分裂。
     */
    private void fillGoneQuotes(LocalDate date, List<TiantiVO.Level> levels) {
        LocalDate next;
        try {
            next = marketStockMapper.nextDetailDate(date);
        } catch (RuntimeException e) {
            next = null;
        }
        // 与打分引擎同源：当日三池（ZT/ZB/DT） ∪ t_zt_perf（T-1 涨停种子今表现全样本）
        Map<String, BigDecimal> resolved = new HashMap<>(crossDayQuote.todayPoolPct(date));
        for (Map.Entry<String, BigDecimal> e : ztPerfStore.readPctByCode(date).entrySet()) {
            resolved.putIfAbsent(e.getKey(), e.getValue());
        }
        List<TiantiVO.FailedRow> gone = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (TiantiVO.Level lvl : levels) {
            if (lvl.getBoard() < QUOTE_FALLBACK_MIN_BOARD) {
                continue;
            }
            for (TiantiVO.FailedRow f : lvl.getFailed()) {
                if ("GONE".equals(f.getTodayStatus()) && f.getChangePct() == null && f.getCode() != null) {
                    gone.add(f);
                    codes.add(f.getCode());
                }
            }
        }
        if (gone.isEmpty()) {
            return;
        }
        // 先落两库级的价格；历史交易日到此为止
        for (TiantiVO.FailedRow f : gone) {
            BigDecimal p = resolved.get(f.getCode());
            if (p != null) {
                f.setChangePct(p);
            }
        }
        if (next != null) {
            return; // 还有更晚明细日 → 腾讯快照给的不是这一天
        }
        // 最新交易日才用腾讯兜底仍未覆盖的缺口（复用打分同一条 augmentGone 路径）
        Map<String, BigDecimal> aug = crossDayQuote.augmentGone(date, codes, resolved);
        int filled = 0;
        for (TiantiVO.FailedRow f : gone) {
            if (f.getChangePct() == null) {
                BigDecimal p = aug.get(f.getCode());
                if (p != null) {
                    f.setChangePct(p);
                    filled++;
                }
            }
        }
        if (filled > 0) {
            log.info("{} 天梯晋级失败股腾讯报价补全 {}/{} 只", date, filled, gone.size());
        }
    }

    /** 失败名单按当日涨幅升序（最惨在前），取不到涨幅的垫后；同涨幅按代码保证确定性。 */
    private static void sortFailed(List<TiantiVO.FailedRow> failed) {
        if (failed == null || failed.size() <= 1) {
            return;
        }
        failed.sort((a, b) -> {
            int byPct = Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder())
                    .compare(a.getChangePct(), b.getChangePct());
            if (byPct != 0) {
                return byPct;
            }
            return Comparator.nullsLast(String::compareTo).compare(a.getCode(), b.getCode());
        });
    }

    /** 标签优先级：空间板(自动最高板) > 中军 > 卡位 > 反包 > 跟风；"总龙头"走人工 {@code manualLeader} 另标。 */
    private static String roleOf(MarketStock row, String mainIndustry, Set<String> zong, Set<String> zhongJun,
                                 String kaWeiCode, Set<String> fanBao) {
        String code = row.getCode();
        if (code != null && zong.contains(code)) {
            // zong=全市场最高连板（PrdMetricsService.zongLong）；自动标"空间板"
            return "空间板";
        }
        if (code != null && zhongJun.contains(code)) {
            return "中军";
        }
        if (code != null && code.equals(kaWeiCode)) {
            return "卡位";
        }
        if (code != null && fanBao.contains(code)) {
            return "反包";
        }
        if (mainIndustry != null && mainIndustry.equals(row.getIndustry())) {
            return "跟风";
        }
        return null;
    }

    private static Set<String> codes(List<MarketStock> rows) {
        Set<String> out = new HashSet<>();
        if (rows != null) {
            for (MarketStock row : rows) {
                if (row.getCode() != null) {
                    out.add(row.getCode());
                }
            }
        }
        return out;
    }

    private static Map<String, MarketStock> indexByCode(List<MarketStock> rows) {
        Map<String, MarketStock> map = new LinkedHashMap<>();
        for (MarketStock row : rows) {
            if (row.getCode() != null) {
                map.putIfAbsent(row.getCode(), row);
            }
        }
        return map;
    }

    private List<MarketStock> listPool(LocalDate date, String pool) {
        List<MarketStock> rows = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, pool));
        industryClassify.apply(rows);
        return rows;
    }
}
