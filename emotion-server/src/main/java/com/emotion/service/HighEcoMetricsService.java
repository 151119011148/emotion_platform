package com.emotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.Anchor;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.market.HighEcoMetrics;
import com.emotion.market.MarketMetrics;
import com.emotion.market.SurvivalMember;
import com.emotion.vo.HighEcoVO;

/**
 * D5 高位生态的取数聚合器：人工阵眼（{@code t_anchor}）+ 高位抱团（三池/档位）+ 监管名单
 * （{@link SurvivalMember}，调用方共享同一份 listOn 结果，不再额外打上游）。
 *
 * <p>与 {@link LadderMetricsService}/{@link PrdMetricsService} 同一哲学：取不到的键不进 metrics（=未评），
 * 绝不兜 0——没刷过监管公告和"今天确实没人被监管"是两件事。所有判据在 {@link HighEcoMetrics}，
 * 本类只做 DB 行 → 判据输入的拼装，{@link #aggregate} 是纯函数，单测直接喂内存 fixture。
 */
@Service
public class HighEcoMetricsService {

    private static final Logger log = LoggerFactory.getLogger(HighEcoMetricsService.class);

    /** 监管股行业回填窗口：断板出池后仍需要知道它属于哪个板块，取近 14 个自然日池内最近记录。 */
    private static final int INDUSTRY_LOOKBACK_DAYS = 14;

    // ---------------- metrics 键（BoardScoreCalculator high 维叶子消费） ----------------
    public static final String M_ANCHOR_ACTION = "d5a_action";
    public static final String M_ANCHOR_HEIGHT = "d5a_height";
    public static final String M_ANCHOR_SEAL = "d5a_seal";
    public static final String M_ANCHOR_CONSIST = "d5a_consist";
    public static final String M_RATIO = "d5c_ratio";
    public static final String M_SEAL_RATIO = "d5c_seal";
    public static final String M_TOP = "d5c_top";
    public static final String M_TIER = "d5c_tier";
    public static final String M_PREM = "d5c_prem";
    public static final String M_JR = "d5c_jr";
    public static final String M_P_COUNT = "d5p_count";
    public static final String M_P_HIGH_RATIO = "d5p_high_ratio";
    public static final String M_P_SPREAD = "d5p_spread";
    public static final String M_F_NUKE = "d5f_nuke";
    public static final String M_F_AVG = "d5f_avg";

    // 交叉信号旗标（值=1 或档位），引擎翻译成 signal_flags 中文标签
    public static final String M_SIG_COALITION_RISK = "d5_sig_coalition_risk";
    public static final String M_SIG_DEATH = "d5_sig_death";
    public static final String M_SIG_MONITOR_IGNORED = "d5_sig_monitor_ignored";
    public static final String M_SIG_MONITOR_WORKS = "d5_sig_monitor_works";
    public static final String M_SIG_SECTOR_PRESS = "d5_sig_sector_press";
    /** 1=阵眼走弱 / 2=龙头易主 / 3=阵眼失效。 */
    public static final String M_SIG_HANDOVER = "d5_sig_handover";
    // 强制风控旗标
    public static final String M_FORCE_TOP_BREAK = "d5_force_top_break";
    public static final String M_FORCE_DEATH = "d5_force_death";

    private final MarketStockMapper marketStockMapper;
    private final AnchorService anchorService;

    public HighEcoMetricsService(MarketStockMapper marketStockMapper, AnchorService anchorService) {
        this.marketStockMapper = marketStockMapper;
        this.anchorService = anchorService;
    }

    /** 取数结果：metrics 进引擎，vo 直接给 /api/d5/high。 */
    public static final class Build {
        private final HighEcoVO vo;
        private final Map<String, BigDecimal> metrics;

        Build(HighEcoVO vo, Map<String, BigDecimal> metrics) {
            this.vo = vo;
            this.metrics = metrics;
        }

        public HighEcoVO getVo() {
            return vo;
        }

        public Map<String, BigDecimal> getMetrics() {
            return metrics;
        }
    }

    // ---------------- DB 拼装 ----------------

    /**
     * 读当天三池、前一交易日涨停池、近窗行业回填行、在位人工阵眼，然后交给 {@link #aggregate}。
     *
     * @param members      当日在列且进分（SEVERE/EXCH）的监管股；由调用方从同一次 listOn 共享传入
     * @param survAvailable 监管事件窗内是否有事件（false=从没回补过公告，压制/反馈整支未评）
     */
    public Build build(Long userId, LocalDate date, PrdMetricsService.Snapshot snap,
                       MarketMetrics.PremiumTiers tiers, List<SurvivalMember> members,
                       boolean survAvailable) {
        List<MarketStock> todayZt = listPool(date, MarketStock.POOL_LIMIT_UP);
        List<MarketStock> todayZb = listPool(date, MarketStock.POOL_BROKEN);
        List<MarketStock> todayDt = listPool(date, MarketStock.POOL_LIMIT_DOWN);
        LocalDate prev = safePrevDate(date);
        List<MarketStock> prevZt = prev == null ? Collections.<MarketStock>emptyList()
                : listPool(prev, MarketStock.POOL_LIMIT_UP);
        List<MarketStock> recent = safeRecent(date);
        List<Anchor> anchors = userId == null ? Collections.<Anchor>emptyList()
                : anchorService.listInPosition(userId, date);
        Integer h = snap == null ? null : snap.maxBoard;
        String mainIndustry = snap == null ? null : snap.mainIndustry;
        return aggregate(date, h, mainIndustry, todayZt, todayZb, todayDt, prevZt, recent,
                tiers, anchors, members == null ? Collections.<SurvivalMember>emptyList() : members,
                survAvailable, snap == null ? null : snap.zongLong);
    }

    private List<MarketStock> listPool(LocalDate date, String pool) {
        if (date == null) {
            return Collections.emptyList();
        }
        try {
            return marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                    .eq(MarketStock::getTradeDate, date)
                    .eq(MarketStock::getPool, pool));
        } catch (RuntimeException e) {
            log.warn("D5 读池失败 date={} pool={}：{}", date, pool, e.toString());
            return Collections.emptyList();
        }
    }

    private LocalDate safePrevDate(LocalDate date) {
        try {
            return marketStockMapper.prevDetailDate(date);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 近 14 自然日全部池行：监管股行业回填 + 阵眼生命周期回溯共用。 */
    private List<MarketStock> safeRecent(LocalDate date) {
        try {
            return marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                    .ge(MarketStock::getTradeDate, date.minusDays(INDUSTRY_LOOKBACK_DAYS))
                    .le(MarketStock::getTradeDate, date));
        } catch (RuntimeException e) {
            log.warn("D5 近窗池行读取失败 date={}：{}", date, e.toString());
            return Collections.emptyList();
        }
    }

    // ---------------- 纯聚合 ----------------

    /** 测试可见：全部推导在这里，入参都是内存行，不碰任何外部状态。 */
    Build aggregate(LocalDate date, Integer hRaw, String mainIndustry,
                    List<MarketStock> todayZt, List<MarketStock> todayZb, List<MarketStock> todayDt,
                    List<MarketStock> prevZt, List<MarketStock> recentRows,
                    MarketMetrics.PremiumTiers tiers, List<Anchor> anchors,
                    List<SurvivalMember> scoredMembers, boolean survAvailable,
                    MarketStock realTop) {
        int h = hRaw == null ? 0 : hRaw;
        int threshold = HighEcoMetrics.highThreshold(Math.max(h, 2));
        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        HighEcoVO vo = new HighEcoVO();
        vo.setDate(date);
        vo.setH(h);
        List<String> notes = new ArrayList<>();

        Map<Integer, Integer> todayByBoard = countByBoard(todayZt);
        Map<String, MarketStock> ztByCode = byCode(todayZt);
        Map<String, MarketStock> zbByCode = byCode(todayZb);
        Map<String, MarketStock> dtByCode = byCode(todayDt);
        Map<String, MarketStock> prevZtByCode = byCode(prevZt);
        Map<String, String> industryByCode = latestIndustry(recentRows, todayZt, todayZb, todayDt, prevZt);
        Set<LocalDate> tradingDays = tradingDates(recentRows);

        // ---------- 子项1：阵眼个体 ----------
        AnchorBlockResult anchorResult = buildAnchorBlock(anchors, h, mainIndustry,
                ztByCode, zbByCode, dtByCode, prevZtByCode, industryByCode, date, recentRows, tradingDays);
        vo.setAnchor(anchorResult.block);
        metrics.putAll(anchorResult.metrics);
        if (!anchorResult.block.isConfigured()) {
            notes.add("当日无在位人工阵眼：阵眼个体(35%)未评，去 /api/anchors 登记后计入");
        }

        // ---------- 子项2：抱团与资金 ----------
        if (h >= 2) {
            CoalitionResult c = buildCoalition(h, threshold, todayZt, todayByBoard,
                    prevZtByCode, tiers, metrics);
            vo.setCoalition(c.block);
        }

        // ---------- 子项3/4：监管压制 + 反馈 ----------
        PressureResult p = buildPressureFeedback(h, threshold, scoredMembers, survAvailable,
                ztByCode, zbByCode, dtByCode, prevZtByCode, industryByCode,
                todayZt, prevZtByCode, metrics, vo, notes);
        vo.setPressure(p.pressure);
        vo.setFeedback(p.feedback);
        for (HighEcoVO.MonitorItem item : p.items) {
            vo.getMonitorPool().add(item);
        }

        // ---------- 交叉信号 + 强制风控 ----------
        buildSignalsAndForce(h, threshold, anchorResult, realTop, metrics, vo, p, todayByBoard);

        // ---------- 维分（引擎口径同构：35/30/20/15，未评子项剔除） ----------
        Integer anchorSc = anchorResult.block.getScore();
        Integer coalitionSc = vo.getCoalition() == null ? null : vo.getCoalition().getScore();
        Integer pressureSc = p.pressure == null ? null : p.pressure.getScore();
        Integer feedbackSc = p.feedback == null ? null : p.feedback.getScore();

        // 守卫① 监管反馈否决权：监管股核按钮(跌停/大面)≥1 → 压制分减半。
        //   否则"票少压制轻(88)"会把"被监管股集体崩盘(反馈0)"这支队最硬的信号盖住。
        int nuke = p.nuke;
        Integer vetoed = HighEcoMetrics.pressureAfterNukeVeto(pressureSc, nuke);
        if (pressureSc != null && !pressureSc.equals(vetoed)) {
            pressureSc = vetoed;
            vo.getPressure().setScore(pressureSc);
            vo.getPressure().setAdjust("监管股核按钮 " + nuke + " 只，" + plain(HighEcoMetrics.GUARD_NUKE_VETO_MULT) + " 计");
            notes.add("守卫·否决权：监管股核按钮 " + nuke + " 只，监管压制 " + plain(HighEcoMetrics.GUARD_NUKE_VETO_MULT));
        }
        // 守卫② 无头抱团折扣：总龙头失效(龙头易主 level≥3) → 抱团分 ×0.85。
        BigDecimal handover = metrics.get(M_SIG_HANDOVER);
        int handoverLevel = handover == null ? 0 : handover.intValue();
        Integer discounted = HighEcoMetrics.coalitionAfterHeadless(coalitionSc, handoverLevel);
        if (coalitionSc != null && !coalitionSc.equals(discounted)) {
            coalitionSc = discounted;
            vo.getCoalition().setScore(coalitionSc);
            vo.getCoalition().setAdjust("总龙头失效，无头抱团 " + plain(HighEcoMetrics.GUARD_HEADLESS_MULT) + " 计");
            notes.add("守卫·无头折扣：总龙头失效，抱团 " + plain(HighEcoMetrics.GUARD_HEADLESS_MULT));
        }
        Integer score = dimScore(anchorSc, coalitionSc, pressureSc, feedbackSc);

        // 守卫③ 强制风控封顶：引擎 force flag（死亡结构/监管龙头断板）触发 → 总分封到崩塌顶 + 红条。
        boolean force = metrics.containsKey(M_FORCE_DEATH) || metrics.containsKey(M_FORCE_TOP_BREAK);
        if (force) {
            if (score != null) {
                Integer capped = HighEcoMetrics.cappedByForce(score, true);
                if (!capped.equals(score)) {
                    score = capped;
                    notes.add("守卫·封顶：强制风控触发，D5 总分封至崩塌级 ≤" + HighEcoMetrics.GUARD_FORCE_CAP);
                }
            }
            vo.setForceRisk(forceRisk(score));
        }
        vo.setScore(score);
        vo.setLevel(levelOf(score));
        vo.setNotes(notes);
        return new Build(vo, metrics);
    }

    private static HighEcoVO.ForceRisk forceRisk(Integer cappedScore) {
        HighEcoVO.ForceRisk fr = new HighEcoVO.ForceRisk();
        fr.setTriggered(true);
        fr.setReason("死亡结构强制空仓 / 监管龙头断板强制退潮：强信号触发，D5 总分封至崩塌级 "
                + (cappedScore == null ? "" : "≤" + HighEcoMetrics.GUARD_FORCE_CAP));
        fr.setCapScore(cappedScore);
        return fr;
    }

    private Integer dimScore(Integer anchor, Integer coalition, Integer pressure, Integer feedback) {
        int[] w = {35, 30, 20, 15};
        Integer[] s = {anchor, coalition, pressure, feedback};
        int num = 0;
        int den = 0;
        for (int i = 0; i < s.length; i++) {
            if (s[i] != null) {
                num += w[i] * s[i];
                den += w[i];
            }
        }
        return den == 0 ? null : (int) Math.round((double) num / den);
    }

    static String levelOf(Integer score) {
        if (score == null) {
            return null;
        }
        if (score >= 80) {
            return "健康";
        }
        if (score >= 60) {
            return "可控";
        }
        if (score >= 40) {
            return "警戒";
        }
        if (score >= 20) {
            return "危险";
        }
        return "崩塌";
    }

    // ---------------- 阵眼个体 ----------------

    private static final class AnchorBlockResult {
        HighEcoVO.AnchorBlock block;
        Map<String, BigDecimal> metrics;
        /** 总龙头挑中的那只（人工指定），供龙头易主信号；无= null。 */
        AnchorEval primary;
        List<AnchorEval> evals;
    }

    private static final class AnchorEval {
        Anchor anchor;
        HighEcoVO.AnchorItem item;
        Integer actionScore;
        Integer heightScore;
        Integer sealScore;
        Integer consistScore;
        Integer score;
        String action;
        Integer board;
        boolean todayZt;
    }

    private AnchorBlockResult buildAnchorBlock(List<Anchor> anchors, int h, String mainIndustry,
                                               Map<String, MarketStock> ztByCode,
                                               Map<String, MarketStock> zbByCode,
                                               Map<String, MarketStock> dtByCode,
                                               Map<String, MarketStock> prevZtByCode,
                                               Map<String, String> industryByCode,
                                               LocalDate date, List<MarketStock> recentRows,
                                               Set<LocalDate> tradingDays) {
        AnchorBlockResult r = new AnchorBlockResult();
        r.block = new HighEcoVO.AnchorBlock();
        r.metrics = new LinkedHashMap<>();
        r.evals = new ArrayList<>();
        r.block.setConfigured(!anchors.isEmpty());
        if (anchors.isEmpty()) {
            return r;
        }

        for (Anchor anchor : anchors) {
            MarketStock zt = ztByCode.get(anchor.getStockCode());
            MarketStock zb = zbByCode.get(anchor.getStockCode());
            MarketStock dt = dtByCode.get(anchor.getStockCode());
            MarketStock prev = prevZtByCode.get(anchor.getStockCode());

            Integer todayBoard = zt == null ? null : nz(zt.getConsecutive());
            Integer prevBoard = prev == null ? null : nz(prev.getConsecutive());
            // 断板日仍跟踪：今日板数取昨日高度（它从哪一档掉下来的）
            Integer board = todayBoard != null ? todayBoard : prevBoard;
            boolean bigLoss = zb != null && zb.getBigLoss() != null && zb.getBigLoss() == 1;
            String pool = zt != null ? MarketStock.POOL_LIMIT_UP
                    : zb != null ? MarketStock.POOL_BROKEN
                    : dt != null ? MarketStock.POOL_LIMIT_DOWN : null;

            String action = HighEcoMetrics.actionOf(zt != null, todayBoard, zb != null, dt != null,
                    bigLoss, prev != null, prevBoard);
            Integer actionScore = HighEcoMetrics.actionScore(action);
            Integer heightScore = HighEcoMetrics.heightScore(board, h);
            Integer sealScore = pool == null ? 0
                    : HighEcoMetrics.sealScore(pool,
                        (zt != null ? zt.getBreakCount() : zb == null ? null : zb.getBreakCount()),
                        zt == null ? null : zt.getFirstSealTime(),
                        poolRow(zt, zb, dt) == null ? null : poolRow(zt, zb, dt).getBigLoss());
            String industry = industryByCode.get(anchor.getStockCode());
            Integer consistScore = null;
            if (mainIndustry != null && industry != null) {
                consistScore = HighEcoMetrics.consistScore(mainIndustry.equals(industry));
            }
            Integer score = HighEcoMetrics.anchorOneScore(actionScore, heightScore, sealScore, consistScore);

            AnchorEval eval = new AnchorEval();
            eval.anchor = anchor;
            eval.actionScore = actionScore;
            eval.heightScore = heightScore;
            eval.sealScore = sealScore;
            eval.consistScore = consistScore;
            eval.score = score;
            eval.action = action;
            eval.board = board;
            eval.todayZt = zt != null;
            eval.item = toItem(anchor, action, board, pool, zt, zb, dt, industry, mainIndustry,
                    actionScore, heightScore, sealScore, consistScore, score,
                    date, recentRows, tradingDays);
            r.evals.add(eval);
            r.block.getItems().add(eval.item);
        }

        // 多阵眼按角色加权（叶子各自按"有该叶评分的阵眼"归一），子项分按有总分的阵眼归一
        double wAction = 0, nAction = 0, wHeight = 0, nHeight = 0, wSeal = 0, nSeal = 0;
        double wConsist = 0, nConsist = 0, wScore = 0, nScore = 0;
        for (AnchorEval e : r.evals) {
            double w = AnchorService.roleWeight(e.anchor.getRole());
            if (e.actionScore != null) { nAction += w * e.actionScore; wAction += w; }
            if (e.heightScore != null) { nHeight += w * e.heightScore; wHeight += w; }
            if (e.sealScore != null) { nSeal += w * e.sealScore; wSeal += w; }
            if (e.consistScore != null) { nConsist += w * e.consistScore; wConsist += w; }
            if (e.score != null) { nScore += w * e.score; wScore += w; }
        }
        if (wAction > 0) r.metrics.put(M_ANCHOR_ACTION, rounded(nAction / wAction));
        if (wHeight > 0) r.metrics.put(M_ANCHOR_HEIGHT, rounded(nHeight / wHeight));
        if (wSeal > 0) r.metrics.put(M_ANCHOR_SEAL, rounded(nSeal / wSeal));
        if (wConsist > 0) r.metrics.put(M_ANCHOR_CONSIST, rounded(nConsist / wConsist));
        if (wScore > 0) r.block.setScore((int) Math.round(nScore / wScore));

        // 总龙头：优先 ZONG/新角色，其次 CYCLE/LEADER；同权取起始日早、id 小
        AnchorEval primary = null;
        for (AnchorEval e : r.evals) {
            if (primary == null || primaryRank(primary) > primaryRank(e)
                    || (primaryRank(primary) == primaryRank(e) && earlier(e.anchor, primary.anchor))) {
                primary = e;
            }
        }
        r.primary = primary;
        return r;
    }

    private static int primaryRank(AnchorEval e) {
        String role = e.anchor.getRole();
        if (AnchorService.ROLE_FENZHI.equals(role)) {
            return 2;
        }
        if (AnchorService.ROLE_BUZHANG.equals(role)) {
            return 3;
        }
        if (AnchorService.ROLE_FANBAO.equals(role)) {
            return 4;
        }
        return 1; // ZONG/CYCLE/LEADER/null
    }

    private static boolean earlier(Anchor a, Anchor b) {
        if (a.getStartDate() == null) {
            return false;
        }
        if (b.getStartDate() == null) {
            return true;
        }
        int c = a.getStartDate().compareTo(b.getStartDate());
        return c < 0 || (c == 0 && a.getId() != null && b.getId() != null && a.getId() < b.getId());
    }

    private static MarketStock poolRow(MarketStock zt, MarketStock zb, MarketStock dt) {
        return zt != null ? zt : zb != null ? zb : dt;
    }

    private HighEcoVO.AnchorItem toItem(Anchor anchor, String action, Integer board, String pool,
                                        MarketStock zt, MarketStock zb, MarketStock dt,
                                        String industry, String mainIndustry,
                                        Integer actionScore, Integer heightScore, Integer sealScore,
                                        Integer consistScore, Integer score,
                                        LocalDate date, List<MarketStock> recentRows,
                                        Set<LocalDate> tradingDays) {
        HighEcoVO.AnchorItem item = new HighEcoVO.AnchorItem();
        item.setId(anchor.getId());
        item.setCode(anchor.getStockCode());
        item.setName(anchor.getStockName());
        item.setIndustry(industry);
        item.setRole(anchor.getRole());
        item.setRoleLabel(AnchorService.roleLabel(anchor.getRole()));
        item.setStartDate(anchor.getStartDate());
        item.setEndDate(anchor.getEndDate());
        item.setActiveDays(activeDays(anchor, date, tradingDays));
        item.setConsecutive(board);
        MarketStock row = poolRow(zt, zb, dt);
        if (row != null) {
            item.setChg(row.getChangePct());
        }
        if (zt != null) {
            item.setSealAmount(zt.getSealAmount());
        }
        item.setAction(action);
        item.setActionLabel(HighEcoMetrics.actionLabel(action));
        item.setActionScore(actionScore);
        item.setHeightScore(heightScore);
        item.setSealScore(sealScore);
        item.setConsistScore(consistScore);
        item.setScore(score);
        if (mainIndustry != null && industry != null && !mainIndustry.equals(industry)) {
            item.setConsistWarn("龙头在" + industry + "≠日内核心" + mainIndustry);
        }
        item.setLifecycle(lifecycle(anchor, date, recentRows));
        return item;
    }

    /** 生效第几天：区间内的明细交易日计数（池数据覆盖到的日期），两端含；无明细时退自然日。 */
    private Integer activeDays(Anchor anchor, LocalDate date, Set<LocalDate> tradingDays) {
        if (anchor.getStartDate() == null) {
            return null;
        }
        int n = 0;
        for (LocalDate d : tradingDays) {
            if (!d.isBefore(anchor.getStartDate()) && !d.isAfter(date)) {
                n++;
            }
        }
        if (n == 0) {
            return (int) java.time.temporal.ChronoUnit.DAYS.between(anchor.getStartDate(), date) + 1;
        }
        return n;
    }

    private HighEcoVO.Lifecycle lifecycle(Anchor anchor, LocalDate date, List<MarketStock> recentRows) {
        HighEcoVO.Lifecycle lc = new HighEcoVO.Lifecycle();
        int maxBoard = 0;
        int breaks = 0;
        int bigLossTimes = 0;
        for (MarketStock row : recentRows) {
            if (!anchor.getStockCode().equals(row.getCode()) || row.getTradeDate() == null) {
                continue;
            }
            if (row.getTradeDate().isBefore(anchor.getStartDate()) || row.getTradeDate().isAfter(date)) {
                continue;
            }
            if (MarketStock.POOL_LIMIT_UP.equals(row.getPool()) && nz(row.getConsecutive()) > maxBoard) {
                maxBoard = nz(row.getConsecutive());
            }
            if (MarketStock.POOL_BROKEN.equals(row.getPool())) {
                breaks++;
                if (row.getBigLoss() != null && row.getBigLoss() == 1) {
                    bigLossTimes++;
                }
            }
        }
        lc.setMaxConsecutive(maxBoard > 0 ? maxBoard : null);
        lc.setBreakTimes(breaks);
        lc.setBigLossTimes(bigLossTimes);
        return lc;
    }

    // ---------------- 抱团与资金 ----------------

    private static final class CoalitionResult {
        HighEcoVO.CoalitionBlock block;
    }

    private CoalitionResult buildCoalition(int h, int threshold, List<MarketStock> todayZt,
                                           Map<Integer, Integer> todayByBoard,
                                           Map<String, MarketStock> prevZtByCode,
                                           MarketMetrics.PremiumTiers tiers,
                                           Map<String, BigDecimal> metrics) {
        HighEcoVO.CoalitionBlock b = new HighEcoVO.CoalitionBlock();
        b.setHighThreshold(threshold);
        int highCount = 0;
        int connectedCount = 0;
        BigDecimal highSeal = BigDecimal.ZERO;
        BigDecimal allSeal = BigDecimal.ZERO;
        for (MarketStock row : todayZt) {
            int n = nz(row.getConsecutive());
            if (n >= 2) {
                connectedCount++;
            }
            if (n >= threshold) {
                highCount++;
            }
            if (row.getSealAmount() != null) {
                allSeal = allSeal.add(row.getSealAmount());
                if (n >= threshold) {
                    highSeal = highSeal.add(row.getSealAmount());
                }
            }
        }
        int topCount = todayByBoard.getOrDefault(h, 0);
        boolean gap = HighEcoMetrics.hasTierGap(todayByBoard, h);
        b.setHighCount(highCount);
        b.setMidCount(todayByBoard.getOrDefault(3, 0) + todayByBoard.getOrDefault(4, 0));
        b.setLowCount(todayByBoard.getOrDefault(2, 0));
        b.setTopCount(topCount);
        b.setHasGap(gap);

        metrics.put(M_RATIO, pct(highCount, connectedCount));
        if (allSeal.signum() > 0) {
            BigDecimal ratio = highSeal.divide(allSeal, 4, RoundingMode.HALF_UP);
            b.setHighSealRatio(ratio);
            metrics.put(M_SEAL_RATIO, ratio.multiply(HUNDRED));
        }
        metrics.put(M_TOP, BigDecimal.valueOf(topCount));
        metrics.put(M_TIER, BigDecimal.valueOf(HighEcoMetrics.tierScore(gap)));

        // 高位溢价：今高位股对应昨 board >= threshold-1 的档位，按档位家数加权
        BigDecimal prem = highPremium(tiers, threshold);
        if (prem != null) {
            b.setHighPrem(prem);
            metrics.put(M_PREM, prem);
        }

        // 高位晋级率：今高位封板且昨=今板-1 的家数 ÷ 昨 ≥threshold-1 板家数
        int num = 0;
        int den = 0;
        for (MarketStock row : prevZtByCode.values()) {
            if (nz(row.getConsecutive()) >= threshold - 1) {
                den++;
            }
        }
        for (MarketStock row : todayZt) {
            int n = nz(row.getConsecutive());
            if (n >= threshold) {
                MarketStock prev = prevZtByCode.get(row.getCode());
                if (prev != null && nz(prev.getConsecutive()) == n - 1) {
                    num++;
                }
            }
        }
        if (den > 0) {
            BigDecimal jr = pct(num, den);
            b.setHighJr(jr.divide(HUNDRED, 4, RoundingMode.HALF_UP));
            metrics.put(M_JR, jr);
        }

        // VO 子分（与引擎阶梯同口径，纯展示）
        int ratioScore = HighEcoMetrics.ratioScore(highCount * 100.0 / Math.max(connectedCount, 1));
        int sealScore = b.getHighSealRatio() == null ? 0
                : HighEcoMetrics.sealRatioScore(b.getHighSealRatio().doubleValue() * 100);
        int structure = (int) Math.round(
                0.30 * ratioScore + 0.25 * sealScore
                + 0.25 * HighEcoMetrics.topUniqueScore(topCount)
                + 0.20 * HighEcoMetrics.tierScore(gap));
        b.setStructureScore(structure);
        Integer strength = null;
        if (prem != null && b.getHighJr() != null) {
            strength = (int) Math.round(0.50 * HighEcoMetrics.premiumScore(prem.doubleValue())
                    + 0.50 * HighEcoMetrics.jrScore(b.getHighJr().doubleValue() * 100));
        } else if (prem != null) {
            strength = HighEcoMetrics.premiumScore(prem.doubleValue());
        } else if (b.getHighJr() != null) {
            strength = HighEcoMetrics.jrScore(b.getHighJr().doubleValue() * 100);
        }
        b.setStrengthScore(strength);
        if (strength != null) {
            b.setScore(HighEcoMetrics.coalitionScore(structure, strength));
        } else {
            b.setScore((int) Math.round(0.60 * structure)); // 强度未评时按结构已评权 0.6 归一
        }
        CoalitionResult r = new CoalitionResult();
        r.block = b;
        return r;
    }

    private static BigDecimal highPremium(MarketMetrics.PremiumTiers tiers, int threshold) {
        if (tiers == null || tiers.getTiers() == null) {
            return null;
        }
        BigDecimal num = BigDecimal.ZERO;
        int den = 0;
        for (MarketMetrics.TierPremium t : tiers.getTiers()) {
            if (t.getBoard() < threshold - 1 || t.getAvgPct() == null) {
                continue;
            }
            int w = Math.max(1, t.getStockCount());
            num = num.add(t.getAvgPct().multiply(BigDecimal.valueOf(w)));
            den += w;
        }
        return den == 0 ? null : num.divide(BigDecimal.valueOf(den), 2, RoundingMode.HALF_UP);
    }

    // ---------------- 监管压制 + 反馈 ----------------

    private static final class PressureResult {
        HighEcoVO.PressureBlock pressure;
        HighEcoVO.FeedbackBlock feedback;
        List<HighEcoVO.MonitorItem> items;
        int nuke;
        int survCount;
        int highSurvCount;
        double highSurvPct;
        int maxSector;
        String maxSectorName;
        BigDecimal avgPct;
        Double sectorMidJr;
    }

    private PressureResult buildPressureFeedback(int h, int threshold,
                                                List<SurvivalMember> members, boolean survAvailable,
                                                Map<String, MarketStock> ztByCode,
                                                Map<String, MarketStock> zbByCode,
                                                Map<String, MarketStock> dtByCode,
                                                Map<String, MarketStock> prevZtByCode,
                                                Map<String, String> industryByCode,
                                                List<MarketStock> todayZt,
                                                Map<String, MarketStock> prevZtByCode2,
                                                Map<String, BigDecimal> metrics,
                                                HighEcoVO vo, List<String> notes) {
        PressureResult r = new PressureResult();
        r.items = new ArrayList<>();
        r.pressure = new HighEcoVO.PressureBlock();
        r.feedback = new HighEcoVO.FeedbackBlock();
        if (!survAvailable) {
            notes.add("监管事件窗为空（公告未回补）：监管压制/反馈整支未评，不是「今天没人被监管」");
            return r;
        }

        int count = 0;
        int highCount = 0;
        int nuke = 0;
        BigDecimal sum = null;
        int matched = 0;
        Map<String, Integer> sectorCount = new HashMap<>();
        for (SurvivalMember m : members) {
            if (!m.scored()) {
                continue;
            }
            count++;
            MarketStock zt = ztByCode.get(m.getCode());
            MarketStock zb = zbByCode.get(m.getCode());
            MarketStock dt = dtByCode.get(m.getCode());
            MarketStock prev = prevZtByCode.get(m.getCode());
            // 断板/出池的监管股今日可能不在任何池里：zt/prev 都无板数→null（用 if/else 而非三元，
            // 因为「nz() 返回 int 拼接 : null」会让 javac 走 intValue() 对 null 拆箱 → NPE
            Integer board = null;
            if (zt != null) {
                board = nz(zt.getConsecutive());
            } else if (prev != null) {
                board = nz(prev.getConsecutive());
            }
            boolean high = board != null && board >= threshold;
            if (high) {
                highCount++;
            }
            boolean nuked = dt != null || (zb != null && zb.getBigLoss() != null && zb.getBigLoss() == 1);
            if (nuked) {
                nuke++;
            }
            String industry = industryByCode.get(m.getCode());
            if (industry != null && industry.trim().isEmpty() == false) {
                sectorCount.merge(industry, 1, Integer::sum);
            }
            if (m.getPct() != null) {
                sum = sum == null ? m.getPct() : sum.add(m.getPct());
                matched++;
            }
            r.items.add(monitorItem(m, industry, board, zt, zb, dt, nuked));
        }
        BigDecimal avg = matched == 0 ? null : sum.divide(BigDecimal.valueOf(matched), 2, RoundingMode.HALF_UP);

        r.survCount = count;
        r.highSurvCount = highCount;
        r.highSurvPct = count == 0 ? 0 : highCount * 100.0 / count;
        r.nuke = nuke;
        r.avgPct = avg;
        String maxSectorName = null;
        int maxSector = 0;
        for (Map.Entry<String, Integer> e : sectorCount.entrySet()) {
            if (e.getValue() > maxSector) {
                maxSector = e.getValue();
                maxSectorName = e.getKey();
            }
        }
        r.maxSector = maxSector;
        r.maxSectorName = maxSectorName;

        // 该行业中位（昨2-3板 → 今3-4板）晋级率
        r.sectorMidJr = sectorMidJr(maxSectorName, todayZt, prevZtByCode2);

        metrics.put(M_P_COUNT, BigDecimal.valueOf(count));
        metrics.put(M_P_HIGH_RATIO, BigDecimal.valueOf(Math.round(r.highSurvPct * 100) / 100.0));
        metrics.put(M_P_SPREAD, BigDecimal.valueOf(maxSector));
        metrics.put(M_F_NUKE, BigDecimal.valueOf(nuke));
        if (avg != null) {
            metrics.put(M_F_AVG, avg);
        }

        r.pressure.setSurvCount(count);
        r.pressure.setHighSurvCount(highCount);
        r.pressure.setHighSurvRatio(BigDecimal.valueOf(r.highSurvPct / 100.0)
                .setScale(4, RoundingMode.HALF_UP));
        r.pressure.setMaxSectorSurv(maxSector);
        r.pressure.setMaxSectorName(maxSectorName);
        int pressureScore = (int) Math.round(
                0.40 * HighEcoMetrics.pressureCountScore(count)
                + 0.35 * HighEcoMetrics.highSurvRatioScore(r.highSurvPct)
                + 0.25 * HighEcoMetrics.spreadScore(maxSector));
        r.pressure.setScore(pressureScore);

        r.feedback.setSurvAvgChg(avg);
        r.feedback.setSurvNuke(nuke);
        r.feedback.setScore(HighEcoMetrics.feedbackScore(nuke, avg));
        return r;
    }

    /** 行业中位晋级率（%）：今 3-4 板且晋级成功的本行业个股 ÷ 昨 2-3 板本行业个股；基数 0=null。 */
    private static Double sectorMidJr(String sector, List<MarketStock> todayZt,
                                      Map<String, MarketStock> prevZtByCode) {
        if (sector == null) {
            return null;
        }
        int den = 0;
        int num = 0;
        for (MarketStock prev : prevZtByCode.values()) {
            int b = nz(prev.getConsecutive());
            if ((b == 2 || b == 3) && sector.equals(prev.getIndustry())) {
                den++;
            }
        }
        for (MarketStock row : todayZt) {
            int b = nz(row.getConsecutive());
            if ((b == 3 || b == 4) && sector.equals(row.getIndustry())) {
                MarketStock prev = prevZtByCode.get(row.getCode());
                if (prev != null && nz(prev.getConsecutive()) == b - 1) {
                    num++;
                }
            }
        }
        return den == 0 ? null : num * 100.0 / den;
    }

    private HighEcoVO.MonitorItem monitorItem(SurvivalMember m, String industry, Integer board,
                                              MarketStock zt, MarketStock zb, MarketStock dt,
                                              boolean nuked) {
        HighEcoVO.MonitorItem item = new HighEcoVO.MonitorItem();
        item.setCode(m.getCode());
        item.setName(m.getName());
        item.setIndustry(industry);
        item.setConsecutive(board);
        if (!m.getEvents().isEmpty()) {
            item.setKind(m.getEvents().get(0).getKind().name());
            item.setAnnDate(m.getEvents().get(0).getAnnDate());
        }
        item.setChg(m.getPct());
        item.setStatus(monitorStatus(m.getPct(), zt, zb, dt, nuked));
        return item;
    }

    private static String monitorStatus(BigDecimal pct, MarketStock zt, MarketStock zb,
                                        MarketStock dt, boolean nuked) {
        if (nuked) {
            return "核按钮/跌停";
        }
        if (zt != null) {
            if (pct != null && pct.doubleValue() >= 5) {
                return "涨停(无视监管)";
            }
            return "涨停";
        }
        if (zb != null) {
            return "断板";
        }
        if (pct == null) {
            return "缺价";
        }
        return pct.doubleValue() >= 0 ? "红盘震荡" : "绿盘分歧";
    }

    // ---------------- 交叉信号 + 强制风控 ----------------

    private void buildSignalsAndForce(int h, int threshold, AnchorBlockResult anchorResult,
                                      MarketStock realTop, Map<String, BigDecimal> metrics,
                                      HighEcoVO vo, PressureResult p,
                                      Map<Integer, Integer> todayByBoard) {
        List<HighEcoVO.Signal> signals = new ArrayList<>();

        // 1. 抱团瓦解前兆
        BigDecimal jr = metrics.get(M_JR);
        boolean midGap = HighEcoMetrics.midGap(todayByBoard, h);
        if (HighEcoMetrics.coalitionRisk(jr == null ? null : jr.doubleValue(), midGap, p.highSurvPct)) {
            metrics.put(M_SIG_COALITION_RISK, BigDecimal.ONE);
            signals.add(new HighEcoVO.Signal("COALITION_RISK",
                    "抱团瓦解前兆：高位晋级" + plain(jr) + "%+中位断层+高位监管"
                            + Math.round(p.highSurvPct) + "%", "danger"));
        }
        // 2. 死亡结构
        int topCount = todayByBoard.getOrDefault(h, 0);
        if (HighEcoMetrics.deathStructure(topCount, p.highSurvPct, p.nuke)) {
            metrics.put(M_SIG_DEATH, BigDecimal.ONE);
            signals.add(new HighEcoVO.Signal("DEATH_STRUCTURE",
                    "死亡结构：空间板唯一+高位监管≥50%+监管股核按钮" + p.nuke + "只", "danger"));
        }
        // 3. 监管无效-情绪亢奋
        if (HighEcoMetrics.monitorIgnored(p.survCount, p.avgPct, p.nuke)) {
            metrics.put(M_SIG_MONITOR_IGNORED, BigDecimal.ONE);
            signals.add(new HighEcoVO.Signal("MONITOR_IGNORED",
                    "监管无效-情绪亢奋：" + p.survCount + "只在列且均涨" + plain(p.avgPct)
                            + "%（随时反转）", "warn"));
        }
        // 4. 监管生效-退潮加速
        if (HighEcoMetrics.monitorWorks(p.nuke, p.avgPct)) {
            metrics.put(M_SIG_MONITOR_WORKS, BigDecimal.ONE);
            signals.add(new HighEcoVO.Signal("MONITOR_WORKS",
                    "监管生效-退潮加速：核按钮" + p.nuke + "只"
                            + (p.avgPct != null ? "，均涨" + plain(p.avgPct) + "%" : ""), "danger"));
        }
        // 5. 龙头与主线错位（信号由 PrdMetrics 的 dragon_misalign 控制量在引擎统一出标签，
        //    这里把阵眼卡上的事实也作为 D5 信号列一条明细）
        for (AnchorEval e : anchorResult.evals) {
            if (e.item.getConsistWarn() != null) {
                signals.add(new HighEcoVO.Signal("ANCHOR_MISMATCH",
                        "龙头与主线错位：" + e.item.getName() + "（" + e.item.getConsistWarn() + "）",
                        "warn"));
                break;
            }
        }
        // 6. 板块级监管压制
        if (HighEcoMetrics.sectorPressure(p.maxSector, p.sectorMidJr)) {
            metrics.put(M_SIG_SECTOR_PRESS, BigDecimal.ONE);
            signals.add(new HighEcoVO.Signal("SECTOR_PRESS",
                    "板块级监管压制：" + p.maxSectorName + " " + p.maxSector
                            + "只在列且中位晋级" + String.format("%.0f", p.sectorMidJr) + "%<15%", "danger"));
        }
        // 7. 龙头易主（人工阵眼 vs 市场实际最高板）
        if (anchorResult.primary != null && realTop != null) {
            AnchorEval pe = anchorResult.primary;
            boolean sameCode = realTop.getCode() != null
                    && realTop.getCode().equals(pe.anchor.getStockCode());
            Integer realBoard = nz(realTop.getConsecutive());
            int gap = (pe.board == null || realBoard == null) ? 0 : realBoard - pe.board;
            boolean broke = !pe.todayZt;
            int level = HighEcoMetrics.handoverLevel(sameCode, gap, broke && !sameCode);
            if (level > 0) {
                metrics.put(M_SIG_HANDOVER, BigDecimal.valueOf(level));
                if (level == 3) {
                    signals.add(new HighEcoVO.Signal("ANCHOR_DEAD",
                            "阵眼失效：" + pe.item.getName() + "今日断板，市场高度易主 "
                                    + realTop.getName() + " " + realBoard + "板", "danger"));
                } else if (level == 2) {
                    signals.add(new HighEcoVO.Signal("ANCHOR_TAKEOVER",
                            "龙头易主：" + pe.item.getName() + "落后市场最高板≥2板（"
                                    + realTop.getName() + " " + realBoard + "板）", "danger"));
                } else {
                    signals.add(new HighEcoVO.Signal("ANCHOR_WEAK",
                            "阵眼走弱：" + pe.item.getName() + " 被新空间板超越（"
                                    + realTop.getName() + " " + realBoard + "板）", "warn"));
                }
                pe.item.setRealTop(false);
                pe.item.setRealTopCode(realTop.getCode());
            } else {
                pe.item.setRealTop(true);
                pe.item.setRealTopCode(realTop.getCode());
            }
        }
        for (AnchorEval e : anchorResult.evals) {
            if (e.item.getRealTopCode() == null) {
                e.item.setRealTop(anchorResult.primary == e);
            }
        }
        vo.setSignals(signals);

        // ---- 强制风控（不看分数） ----
        // ① 空间板处于 SEVERE/EXCH 且当日断板/核按钮（在列名单一查：仍涨停=未断；缺价不判，不凭空强制）
        boolean topMonitored = false;
        boolean topBrokeOrNuke = false;
        if (realTop != null && topCount >= 1) {
            for (HighEcoVO.MonitorItem item : p.items) {
                if (realTop.getCode() != null && realTop.getCode().equals(item.getCode())) {
                    topMonitored = true;
                    String status = item.getStatus();
                    topBrokeOrNuke = status != null
                            && !status.startsWith("涨停") && !"缺价".equals(status);
                    break;
                }
            }
        }
        if (HighEcoMetrics.forceMonitoredTopBreak(topMonitored, topBrokeOrNuke)) {
            metrics.put(M_FORCE_TOP_BREAK, BigDecimal.ONE);
        }
        // ② 核按钮 + 空间板唯一
        if (HighEcoMetrics.forceDeath(p.nuke, topCount)) {
            metrics.put(M_FORCE_DEATH, BigDecimal.ONE);
        }
    }

    // ---------------- 小工具 ----------------

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static BigDecimal pct(int part, int total) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal rounded(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    private static Map<Integer, Integer> countByBoard(List<MarketStock> rows) {
        Map<Integer, Integer> m = new HashMap<>();
        for (MarketStock s : rows) {
            if (s.getConsecutive() != null && s.getConsecutive() >= 1) {
                m.merge(s.getConsecutive(), 1, Integer::sum);
            }
        }
        return m;
    }

    private static Map<String, MarketStock> byCode(List<MarketStock> rows) {
        Map<String, MarketStock> m = new HashMap<>();
        for (MarketStock s : rows) {
            if (s.getCode() != null) {
                m.put(s.getCode(), s);
            }
        }
        return m;
    }

    /** 行业回填：今日三池优先，其次昨日 ZT，最后近窗池内最近一条（按日期倒序）。 */
    private static Map<String, String> latestIndustry(List<MarketStock> recent,
                                                      List<MarketStock>... todayPools) {
        Map<String, String> current = new HashMap<>();
        for (List<MarketStock> pool : todayPools) {
            for (MarketStock row : pool) {
                if (row.getCode() != null && row.getIndustry() != null) {
                    current.put(row.getCode(), row.getIndustry());
                }
            }
        }
        List<MarketStock> sorted = new ArrayList<>(recent == null
                ? Collections.<MarketStock>emptyList() : recent);
        sorted.sort(Comparator.comparing(MarketStock::getTradeDate,
                Comparator.nullsLast(Comparator.reverseOrder())));
        Map<String, String> fallback = new HashMap<>();
        for (MarketStock row : sorted) {
            if (row.getCode() != null && row.getIndustry() != null) {
                fallback.putIfAbsent(row.getCode(), row.getIndustry());
            }
        }
        Map<String, String> all = new HashMap<>(fallback);
        all.putAll(current);
        return all;
    }

    private static Set<LocalDate> tradingDates(List<MarketStock> rows) {
        Set<LocalDate> days = new TreeSet<>();
        if (rows != null) {
            for (MarketStock row : rows) {
                if (row.getTradeDate() != null) {
                    days.add(row.getTradeDate());
                }
            }
        }
        return days;
    }
}
