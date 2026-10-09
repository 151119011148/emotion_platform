package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.DailyRecord;
import com.emotion.entity.MarketDaily;
import com.emotion.entity.NodeEvent;
import com.emotion.mapper.DailyRecordMapper;
import com.emotion.mapper.MarketDailyMapper;
import com.emotion.mapper.NodeEventMapper;
import com.emotion.vo.BreakDetailVO;
import com.emotion.vo.ShoubanVO;
import com.emotion.vo.TiantiVO;
import com.emotion.waverider.WaveRiderConfig;

/**
 * 破壁详情的供数口：曲线上一颗 ☆/★，点开要看的那几块数（线是谁钉的、助攻、盘口、情绪闸门、次日结算）。
 *
 * <p><b>判定一份都不重写</b>。哪天是试探、哪天破壁成功，读的是 {@link TiantiService#breakDay}
 * 里已经跑完的 {@code detectBreaks}；助攻与盘口读的是 {@link TiantiService#vo} 和
 * {@link ShoubanService#vo} 现成的两份当日快照，行业两边都过了
 * {@link IndustryClassifyService#apply}，同属性/异属性才谈得上比对。
 *
 * <p>取数与判定分开（{@link #compose} 是纯函数）只有一个理由：助攻怎么数、结算怎么判，
 * 要能拿数组单测跑，不打库也不起 Spring。
 */
@Service
public class BreakDetailService {

    /** 他写的试探降级线：当天炸板率 &gt; 50% 就不开仓。等于 50 不算过。 */
    static final BigDecimal GATE_MAX_BOMB_RATE = new BigDecimal("50");
    /** 他写的另一条：昨日涨停溢价 &lt; 0 同样把试探信号降级。 */
    static final BigDecimal GATE_MIN_PREMIUM = BigDecimal.ZERO;
    /** 助攻合计到这个数就算"题材有梯队"；只展示，不进 ready。 */
    static final int MIN_LADDER_ASSIST = 2;

    private static final String EVENT_NONE = BreakDetailVO.EVENT_NONE;
    private static final String EVENT_PROBE = BreakDetailVO.EVENT_PROBE;
    private static final String EVENT_BREAK = BreakDetailVO.EVENT_BREAK;
    private static final String OUTCOME_SUCCESS = BreakDetailVO.OUTCOME_SUCCESS;
    private static final String OUTCOME_FAILED = BreakDetailVO.OUTCOME_FAILED;
    private static final String OUTCOME_PENDING = BreakDetailVO.OUTCOME_PENDING;

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final TiantiService tiantiService;
    private final ShoubanService shoubanService;
    private final MarketDailyMapper marketDailyMapper;
    private final DailyRecordMapper dailyRecordMapper;
    /** 只为读"这只票现在挂在哪个节点下"，好把立节点会动谁的分数说一句；不写库。 */
    private final NodeEventMapper nodeEventMapper;

    public BreakDetailService(TiantiService tiantiService,
                              ShoubanService shoubanService,
                              MarketDailyMapper marketDailyMapper,
                              DailyRecordMapper dailyRecordMapper,
                              NodeEventMapper nodeEventMapper) {
        this.tiantiService = tiantiService;
        this.shoubanService = shoubanService;
        this.marketDailyMapper = marketDailyMapper;
        this.dailyRecordMapper = dailyRecordMapper;
        this.nodeEventMapper = nodeEventMapper;
    }

    /**
     * 一次点击的全部读数：三条已有查询（曲线全窗口 / 当日天梯 / 当日首板池）+ 两条闸门小查。
     * 日期不传按今天，与天梯、首板池两个接口同一个约定。
     */
    public BreakDetailVO vo(LocalDate requested) {
        LocalDate date = requested != null ? requested : LocalDate.now(CN);
        Source s = new Source();
        s.date = date;
        TiantiService.BreakDay day = tiantiService.breakDay(date);
        s.point = day.getPoint();
        s.prevPoint = day.getPrev();
        s.nextPoint = day.getNext();
        s.subjectCode = codeOf(subjectOf(s.point));

        if (s.point != null) {
            TiantiVO tv = tiantiService.vo(date);
            s.ladderRows = flatten(tv.getLevels());
            s.subjectRow = rowOf(s.ladderRows, s.subjectCode);
            ShoubanVO sv = shoubanService.vo(date);
            s.sealed = sv.getSealed() == null ? new ArrayList<ShoubanVO.Row>() : sv.getSealed();
            s.detailAvailable = hasQuoteDetail(s.ladderRows, s.sealed);
        }
        s.day = marketDailyMapper.selectOne(new LambdaQueryWrapper<MarketDaily>()
                .eq(MarketDaily::getTradeDate, date)
                .last("LIMIT 1"));
        s.record = dailyRecordMapper.selectOne(new LambdaQueryWrapper<DailyRecord>()
                .eq(DailyRecord::getTradeDate, date)
                .last("LIMIT 1"));
        BreakDetailVO vo = compose(s);
        // 立节点之前就得让他看清会动谁的分数，不能等写完再回头说
        vo.setScoreImpact(scoreImpact(date,
                vo.getSubject() == null ? null : vo.getSubject().getCode(),
                NodeBreakService.typeOfEvent(vo.getEvent())));
        return vo;
    }

    /**
     * 立成节点会动谁的分数：节点票 {@code node_stock} 撞进既有打分索引，同股只认 D0 最近的那条。
     * 现在挂着的那条更早、这次立的更晚，就会顶掉它；反过来这次立的行压根进不了打分。
     *
     * <p>权重读 {@link WaveRiderConfig#defaultNodeTypeWeights()}：破壁这两个键是新的，
     * 存量策略版本里不可能写过，读取时 {@code fillMissingNodeTypeWeights()} 补的就是这个默认值。
     */
    public String scoreImpact(LocalDate date, String code, String nodeType) {
        if (code == null || nodeType == null) {
            return null;
        }
        List<NodeEvent> rows = nodeEventMapper.selectList(new LambdaQueryWrapper<NodeEvent>()
                .ne(NodeEvent::getStatus, "失效"));
        return scoreImpactNote(rows, date, code, nodeType);
    }

    /** {@link #scoreImpact} 的纯函数半边：原料是这只票在库里的节点行，单测直接搓数组。 */
    static String scoreImpactNote(List<NodeEvent> sameStockRows, LocalDate date,
                                  String code, String nodeType) {
        Map<String, Double> weights = WaveRiderConfig.defaultNodeTypeWeights();
        double fresh = weightOf(weights, nodeType);
        NodeEvent newest = null;
        for (NodeEvent e : orEmpty(sameStockRows)) {
            if (e == null || e.getD0Date() == null
                    || !code.equals(NodeService.stockCodeOf(e.getNodeStock()))) {
                continue;
            }
            if (newest == null || e.getD0Date().isAfter(newest.getD0Date())) {
                newest = e;
            }
        }
        if (newest == null) {
            return "「" + code + "」现在不是任何在册节点的节点票："
                    + "立完这一行，它在候选窗口里按 " + decimal(fresh) + " 计节点分。";
        }
        double held = weightOf(weights, newest.getNodeType());
        boolean takesOver = date != null && date.isAfter(newest.getD0Date());
        String label = NodeService.nodeTypeLabel(newest.getNodeType());
        return "「" + code + "」已经挂在 " + newest.getD0Date() + " 的「"
                + (label == null ? "未识别类型" : label) + "」节点下，节点分 " + decimal(held) + "。"
                + "同股只认 D0 最近的那条，这次立的 " + date + "（" + decimal(fresh) + "）"
                + (takesOver ? "会顶掉它。" : "排在它后面，不改变现在的分。");
    }

    /** 与 {@code WaveRiderEngine.nodePart} 同一条规则：没识别出类型按满分，有类型查不到权重按 0 分。 */
    private static double weightOf(Map<String, Double> weights, String nodeType) {
        if (nodeType == null) {
            return 1.0;
        }
        Double w = weights.get(nodeType);
        return w == null ? 0 : w;
    }

    private static String decimal(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ---------- 取数原料 ----------

    /** 一次面板打开拿到的全部原料；单测直接搓这个，不打库。 */
    static class Source {
        LocalDate date;
        TiantiVO.HeightPoint point;
        TiantiVO.HeightPoint prevPoint;
        TiantiVO.HeightPoint nextPoint;
        String subjectCode;
        TiantiVO.Row subjectRow;
        List<TiantiVO.Row> ladderRows;
        List<ShoubanVO.Row> sealed;
        boolean detailAvailable;
        MarketDaily day;
        DailyRecord record;
    }

    // ---------- 判定（纯函数） ----------

    static BreakDetailVO compose(Source s) {
        BreakDetailVO vo = new BreakDetailVO();
        vo.setTradeDate(s.date);
        vo.setEvent(EVENT_NONE);
        vo.setCurvePoint(Boolean.FALSE);
        TiantiVO.HeightPoint p = s.point;
        if (p == null) {
            vo.setDetailAvailable(Boolean.FALSE);
            vo.setDetailMissingReason("这天的连板高度曲线上没有点：那天没有涨停明细，破壁判不起来");
            return vo;
        }
        // 有点但没有 ☆/★，和压根没有点，是两种完全不同的空：前者是判定变了，后者只是没数据
        vo.setCurvePoint(Boolean.TRUE);
        vo.setCeiling(p.getCeiling());
        vo.setLineStock(ref(p.getLineStock(), TiantiService.boardOf(p, codeOf(p.getLineStock()))));
        vo.setLineOriginDate(p.getLineOriginDate());
        vo.setLineOriginStock(ref(p.getLineOriginStock(),
                TiantiService.boardOf(p, codeOf(p.getLineOriginStock()))));
        vo.setOldDragonHeight(p.getOldDragonHeight());
        vo.setChaos(Boolean.TRUE.equals(p.getIsChaos()));
        vo.setDetailAvailable(s.detailAvailable ? Boolean.TRUE : Boolean.FALSE);
        if (!s.detailAvailable) {
            vo.setDetailMissingReason("这天只有名义天梯：板上名单有、逐只涨跌与封单明细没有"
                    + "（2026-08-03 之前的历史都是这种）——首板助攻与盘口判不了，留空不是 0 只");
        }

        TiantiVO.HeightStock subject = subjectOf(p);
        if (Boolean.TRUE.equals(p.getIsBreak())) {
            vo.setEvent(EVENT_BREAK);
            vo.setPrevHigh(p.getPrevHigh());
            vo.setSubject(ref(p.getBreakStock(), TiantiService.boardOf(p, codeOf(p.getBreakStock()))));
            vo.setProbeDate(probeDateOf(s.prevPoint, codeOf(p.getBreakStock())));
        } else if (Boolean.TRUE.equals(p.getIsProbe())) {
            vo.setEvent(EVENT_PROBE);
            // 追平追的是<b>进入这天时挂着的那条线</b>：追平当天线就跟着它的板高抬（8.28 深中华Ａ 追 6 板线、
            // 当天抬到 7）。拿当天的 ceiling 说"追 7 板线"是倒过来说，所以这一格读前一天。
            vo.setCeiling(TiantiService.chasedLine(s.prevPoint, p));
            vo.setSubject(ref(p.getProbeStock(), TiantiService.boardOf(p, codeOf(p.getProbeStock()))));
        }
        if (vo.getSubject() != null && s.subjectRow != null) {
            vo.getSubject().setIndustry(s.subjectRow.getIndustry());
        }
        vo.setAssist(assist(s, vo.getSubject()));
        vo.setBoard(s.detailAvailable ? board(s.subjectRow) : null);
        vo.setGate(gate(s));
        vo.setGateWarnings(gateWarnings(s));
        vo.setOutcome(outcome(s, subject));
        return vo;
    }

    /**
     * 助攻：同属性首板 / 二板 / 三板以上，加当天首板的<b>广度</b>。
     * 主角自己不数进去——它是被助攻的对象，算进去等于自己给自己助攻。
     *
     * <p>名义天梯那批日子只数得出二板/三板以上（板上名单带行业与板高），首板那半边必须留 null：
     * 首板在明细里靠 {@code consecutive} 兜成 1，兜出来的"0 只首板"是名义名单的副作用，不是市场没有首板。
     */
    static BreakDetailVO.Assist assist(Source s, BreakDetailVO.StockRef subject) {
        if (subject == null || subject.getCode() == null) {
            return null;
        }
        String industry = subject.getIndustry();
        BreakDetailVO.Assist a = new BreakDetailVO.Assist();
        if (!notBlank(industry)) {
            return a;
        }
        int second = 0;
        int thirdPlus = 0;
        List<BreakDetailVO.StockRef> follow = new ArrayList<>();
        for (TiantiVO.Row r : orEmpty(s.ladderRows)) {
            if (subject.getCode().equals(r.getCode()) || !industry.equals(r.getIndustry())) {
                continue;
            }
            int board = nz(r.getBoard());
            if (board == 2) {
                second++;
            } else if (board >= 3) {
                thirdPlus++;
            }
            if (board >= 2) {
                follow.add(ref2(r));
            }
        }
        a.setSameIndustrySecond(second);
        a.setSameIndustryThirdPlus(thirdPlus);
        a.setFollowStocks(follow);
        if (s.detailAvailable) {
            int first = 0;
            List<BreakDetailVO.StockRef> firstStocks = new ArrayList<>();
            Set<String> industries = new HashSet<>();
            int total = 0;
            for (ShoubanVO.Row r : orEmpty(s.sealed)) {
                total++;
                if (notBlank(r.getIndustry())) {
                    industries.add(r.getIndustry());
                }
                if (industry.equals(r.getIndustry())) {
                    first++;
                    firstStocks.add(ref2(r));
                }
            }
            a.setSameIndustryFirst(first);
            a.setFirstStocks(firstStocks);
            a.setFirstBoardTotal(total);
            a.setFirstBoardIndustries(industries.size());
        }
        a.setTotal(sum(a));
        a.setLadderOk(a.getTotal() == null ? null : a.getTotal() >= MIN_LADDER_ASSIST);
        return a;
    }

    /** 三项都数得过来才合计；首板留 null 的日子给一个加了两项的数会看着像"助攻就这么多"。 */
    private static Integer sum(BreakDetailVO.Assist a) {
        if (a.getSameIndustryFirst() == null
                || a.getSameIndustrySecond() == null || a.getSameIndustryThirdPlus() == null) {
            return null;
        }
        return a.getSameIndustryFirst() + a.getSameIndustrySecond() + a.getSameIndustryThirdPlus();
    }

    private static BreakDetailVO.BoardInfo board(TiantiVO.Row row) {
        if (row == null) {
            return null;
        }
        BreakDetailVO.BoardInfo b = new BreakDetailVO.BoardInfo();
        b.setPattern(row.getPattern());
        b.setSealForm(row.getSealForm());
        b.setFirstSealTime(row.getFirstSealTime());
        b.setBreakCount(row.getBreakCount());
        b.setTurnoverRate(row.getTurnoverRate());
        b.setSealRatio(row.getSealRatio());
        b.setSealAmount(row.getSealAmount());
        b.setFloatMv(row.getFloatMv());
        b.setChangePct(row.getChangePct());
        b.setOneWordKilling(row.getOneWordKilling());
        return b;
    }

    private static BreakDetailVO.MarketGate gate(Source s) {
        BreakDetailVO.MarketGate g = new BreakDetailVO.MarketGate();
        if (s.day != null) {
            g.setBrokenBoardRate(s.day.getBrokenBoardRate());
            g.setYesterdayLimitPremium(s.day.getYesterdayLimitPremium());
            g.setLimitUpCount(s.day.getLimitUpCount());
        }
        if (s.record != null) {
            g.setStage(s.record.getStage());
        }
        return g;
    }

    /** 只有他写了数的三条进这里；盘口那两条（放量换手不烂板、一字缩量不追）是给人看的数，不兜判。 */
    static List<String> gateWarnings(Source s) {
        List<String> out = new ArrayList<>();
        MarketDaily day = s.day;
        if (day != null && day.getBrokenBoardRate() != null
                && day.getBrokenBoardRate().compareTo(GATE_MAX_BOMB_RATE) > 0) {
            out.add("当天炸板率 " + plain(day.getBrokenBoardRate()) + "% > 50%：按你的口径试探信号降级，不开仓");
        }
        if (day != null && day.getYesterdayLimitPremium() != null
                && day.getYesterdayLimitPremium().compareTo(GATE_MIN_PREMIUM) < 0) {
            out.add("昨日涨停溢价 " + plain(day.getYesterdayLimitPremium()) + "% < 0：赚钱效应没起来，试探只观察");
        }
        if (s.record != null && notBlank(s.record.getStage()) && s.record.getStage().contains("退潮")) {
            out.add("阶段「" + s.record.getStage() + "」：退潮期不看首板、不接试探");
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 试探日的次日结算：成没成<b>直接读次一日那个点的 {@code isBreak}</b>，不在这儿重算一遍续板——
     * 重算就是第二份判定，会和曲线上的 ★ 打架。板高只拿来把话说明白。
     *
     * <p>破壁成功日不在这块里判：那天已经是结论本身，它的次日是新一轮周期的事。
     */
    static BreakDetailVO.Outcome outcome(Source s, TiantiVO.HeightStock subject) {
        if (subject == null || s.point == null || Boolean.TRUE.equals(s.point.getIsBreak())) {
            return null;
        }
        BreakDetailVO.Outcome o = new BreakDetailVO.Outcome();
        if (s.nextPoint == null) {
            o.setResult(OUTCOME_PENDING);
            o.setReason("次一交易日的盘面明细还没落库，续没续板判不了");
            return o;
        }
        o.setNextDate(s.nextPoint.getTradeDate());
        int todayBoard = TiantiService.boardOf(s.point, subject.getCode());
        o.setNextBoard(TiantiService.boardOf(s.nextPoint, subject.getCode()));
        boolean settled = Boolean.TRUE.equals(s.nextPoint.getIsBreak())
                && subject.getCode().equals(codeOf(s.nextPoint.getBreakStock()));
        if (settled) {
            o.setResult(OUTCOME_SUCCESS);
            o.setReason("次日续到 " + o.getNextBoard() + " 板（试探日 " + todayBoard + " 板）→ 破壁成功");
        } else {
            o.setResult(OUTCOME_FAILED);
            o.setReason(o.getNextBoard() <= 0
                    ? "次日掉出连板名单（试探日 " + todayBoard + " 板），这次破壁没兑现"
                    : "次日滞涨在 " + o.getNextBoard() + " 板、没有加板（试探日 " + todayBoard + " 板），这次破壁没兑现");
        }
        return o;
    }

    // ---------- 小工具 ----------

    /** 破壁事件当天的主角：成功日看新龙，试探日看追平的那只，其余 null。 */
    private static TiantiVO.HeightStock subjectOf(TiantiVO.HeightPoint p) {
        if (p == null) {
            return null;
        }
        if (Boolean.TRUE.equals(p.getIsBreak())) {
            return p.getBreakStock();
        }
        return Boolean.TRUE.equals(p.getIsProbe()) ? p.getProbeStock() : null;
    }

    /** 破壁成功日往前找那次试探：判定是"试探股<b>次日</b>续板"，所以前一个点就是试探日。 */
    private static LocalDate probeDateOf(TiantiVO.HeightPoint prev, String breakCode) {
        if (prev == null || breakCode == null || !Boolean.TRUE.equals(prev.getIsProbe())) {
            return null;
        }
        return breakCode.equals(codeOf(prev.getProbeStock())) ? prev.getTradeDate() : null;
    }

    private static List<TiantiVO.Row> flatten(List<TiantiVO.Level> levels) {
        List<TiantiVO.Row> rows = new ArrayList<>();
        for (TiantiVO.Level lvl : orEmpty(levels)) {
            rows.addAll(orEmpty(lvl.getRows()));
        }
        return rows;
    }

    private static TiantiVO.Row rowOf(List<TiantiVO.Row> rows, String code) {
        if (code == null) {
            return null;
        }
        for (TiantiVO.Row r : orEmpty(rows)) {
            if (code.equals(r.getCode())) {
                return r;
            }
        }
        return null;
    }

    /**
     * 逐只盘口明细在不在：涨停池里有任意一行带涨跌幅就算在。
     * 名义天梯那批行的 {@code change_pct} 全空，这正是它跟真明细的分界。
     */
    private static boolean hasQuoteDetail(List<TiantiVO.Row> ladderRows, List<ShoubanVO.Row> sealed) {
        for (TiantiVO.Row r : orEmpty(ladderRows)) {
            if (r.getChangePct() != null) {
                return true;
            }
        }
        for (ShoubanVO.Row r : orEmpty(sealed)) {
            if (r.getChangePct() != null) {
                return true;
            }
        }
        return false;
    }

    private static BreakDetailVO.StockRef ref(TiantiVO.HeightStock stock, Integer board) {
        if (stock == null) {
            return null;
        }
        BreakDetailVO.StockRef r = new BreakDetailVO.StockRef();
        r.setCode(stock.getCode());
        r.setName(stock.getName());
        r.setBoard(board);
        return r;
    }

    private static BreakDetailVO.StockRef ref2(TiantiVO.Row row) {
        BreakDetailVO.StockRef r = new BreakDetailVO.StockRef();
        r.setCode(row.getCode());
        r.setName(row.getName());
        r.setBoard(row.getBoard());
        r.setIndustry(row.getIndustry());
        return r;
    }

    private static BreakDetailVO.StockRef ref2(ShoubanVO.Row row) {
        BreakDetailVO.StockRef r = new BreakDetailVO.StockRef();
        r.setCode(row.getCode());
        r.setName(row.getName());
        r.setBoard(1);
        r.setIndustry(row.getIndustry());
        return r;
    }

    private static String codeOf(TiantiVO.HeightStock stock) {
        return stock == null ? null : stock.getCode();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? Collections.<T>emptyList() : list;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.trim().isEmpty();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
