package com.emotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketDaily;
import com.emotion.entity.MarketStock;
import com.emotion.entity.NodeEvent;
import com.emotion.mapper.MarketDailyMapper;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.NodeEventMapper;
import com.emotion.vo.NodeSuggestVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 节点状态复算：把《节点理论交易Skill v2.0（双轨版）》那套判据按盘面明细跑一遍，给出建议，
 * 由他点头才落库。
 *
 * <p>取数<b>全部本地</b>——{@code t_market_stock}（三池逐只明细）+ {@code t_market_daily}（涨跌停家数、
 * 连板高度，全局客观、与用户无关）。一次复算打的是几条 SQL，不碰上游：他已经拉过那天的行情，
 * 判据就该立刻出来，回看 14 天历史时这里一次都不该花钱。
 *
 * <p>三件事是这套判据的底线，改动前先读：
 * <ol>
 *   <li>判据不齐就说"不齐"。{@link NodeSuggestVO#getMissing()} 非空即不 ready，前端不给采纳按钮。
 *       缺数时最诱人的写法是把晋级数兜成 0，而那正好撞上他文档里"0 只 → 失败"这条真结论——
 *       一个没拉过行情的日子会被判成失效节点，这是这套系统能犯的最坏错误。</li>
 *   <li>候选池按明细复算，<b>不用</b>他手打的 {@code d0_candidates}。那份名单是盘中的判断，
 *       而判据要的是"D0 那天所有的二板"；两者不一致时在这里看得出来，才谈得上核对。</li>
 *   <li>近似判据一律标在 {@code source} 与 {@code warnings} 上。明细里跌停价是空的，
 *       "未一字跌停"这条就只能退化成"那天有没有跌停"，说清楚比装准有价值。</li>
 * </ol>
 */
@Service
public class NodeSuggestService {

    // ---------- 判据阈值：每条一个出处，改判据只改这一处 ----------

    /** §二 前置过滤器「跌停家数 &lt; 10家」。等于 10 不算过。 */
    static final int MAX_LIMIT_DOWN = 10;
    /** §二 前置过滤器「涨停家数 &gt; 60家」。等于 60 不算过。 */
    static final int MIN_LIMIT_UP = 60;
    /** §二 前置过滤器「连板高度未连续压缩 / 连续 3 天压缩 → 退潮期」。要 3 次下降，即 4 个读数。 */
    static final int HEIGHT_COMPRESS_DAYS = 3;
    /** §三 系统A 判定规则「D0 二板晋级数量 ≥3 只 → 强节点；1-2 只 → 中等；0 只 → 失败」。 */
    static final int A_STRONG_PROMOTION = 3;
    /** §三 系统A 操作流程 T+1「收盘确认：D0 二板晋级率 ≥ 30% → 节点有效」。百分数。 */
    static final BigDecimal A_MIN_RATE = new BigDecimal("30.00");
    /** §四 系统B 判定规则 T+1「看该板块首板的一进二 ≥2 只晋级 → 板块节点有效」。 */
    static final int B_MIN_PROMOTION = 2;

    /** 老龙反查的左边界：往前 30 天足够覆盖一轮 7 板周期的起爆。 */
    private static final int ANCHOR_LOOKBACK_DAYS = 30;
    /** 老龙反查的右边界：D0 之后多看 5 天，§三 失效信号里的"连续跌停"通常不在断板当天发生。 */
    private static final int KILL_WATCH_DAYS = 5;
    /** status_note 是 VARCHAR(300)，留 5 字给截断标记。 */
    private static final int STATUS_NOTE_MAX = 295;

    static final String STATUS_PENDING = "待验证";
    static final String STATUS_VALID = "有效";
    static final String STATUS_INVALID = "失效";
    static final String SYSTEM_A = "A";
    static final String SYSTEM_B = "B";

    /** 采纳要落的八个字段。指纹、比对、写库三处共用这一张表——漏一个字段就是点了个假的采纳。 */
    private static final List<String> FP_KEYS = Collections.unmodifiableList(Arrays.asList(
            "status", "t1Date", "t1AnchorRepack", "t1PromotionCount", "t1PromotionRate",
            "nodeValid", "nodeStock", "nodeStockMaxBoard"));
    private static final Map<String, String> FP_LABELS = labels();

    private static Map<String, String> labels() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("status", "状态");
        map.put("t1Date", "T+1 日期");
        map.put("t1AnchorRepack", "T+1 老龙反包");
        map.put("t1PromotionCount", "晋级数量");
        map.put("t1PromotionRate", "晋级率");
        map.put("nodeValid", "节点是否有效");
        map.put("nodeStock", "节点票");
        map.put("nodeStockMaxBoard", "节点票最高板数");
        return Collections.unmodifiableMap(map);
    }

    private final NodeEventMapper nodeEventMapper;
    private final MarketStockMapper marketStockMapper;
    private final MarketDailyMapper marketDailyMapper;
    private final ObjectMapper json;

    public NodeSuggestService(NodeEventMapper nodeEventMapper,
                             MarketStockMapper marketStockMapper,
                             MarketDailyMapper marketDailyMapper,
                             ObjectMapper json) {
        this.nodeEventMapper = nodeEventMapper;
        this.marketStockMapper = marketStockMapper;
        this.marketDailyMapper = marketDailyMapper;
        this.json = json;
    }

    public NodeSuggestVO suggest(Long userId, Long id) {
        NodeEvent node = own(userId, id);
        return decide(node, fetch(node, userId), json);
    }

    /**
     * 落库。<b>服务端重算一遍再逐字段比对</b>，不一致整条拒绝。
     *
     * <p>不能照抄前端传来的 body 落库：他点的那条建议和最后写进去的那个数必须是同一个东西，
     * 而"看到建议"和"点采纳"之间他完全可以又拉了一次行情、把那天的明细换了。
     */
    public NodeEvent adopt(Long userId, Long id, String fingerprint) {
        NodeEvent node = own(userId, id);
        NodeSuggestVO fresh = decide(node, fetch(node, userId), json);
        if (!fresh.isReady()) {
            throw new IllegalArgumentException("这条节点还不能采纳：" + join(fresh.getMissing(), "；"));
        }
        List<String> diffs = diff(json, fingerprint, fresh);
        if (!diffs.isEmpty()) {
            throw new IllegalArgumentException("盘面在出建议之后又变了，这次采纳已作废，请刷新看新建议："
                    + join(diffs, "；"));
        }
        node.setStatus(fresh.getStatus());
        node.setT1Date(fresh.getT1Date());
        node.setT1AnchorRepack(fresh.getT1AnchorRepack());
        node.setT1PromotionCount(fresh.getT1PromotionCount());
        node.setT1PromotionRate(fresh.getT1PromotionRate());
        node.setNodeValid(fresh.getNodeValid());
        node.setNodeStock(fresh.getNodeStock());
        node.setNodeStockMaxBoard(fresh.getNodeStockMaxBoard());
        node.setStatusNote(cut(fresh.getReason(), STATUS_NOTE_MAX));
        node.setConclusionReason(fresh.getConclusionReason());
        node.setLastRecalcAt(LocalDateTime.now());
        nodeEventMapper.updateById(node);
        return node;
    }

    // ---------- 取数 ----------

    /** 一次复算要的全部原料。取数与判定分开的唯一理由：判定那半边要能拿数组单测，不打库。 */
    static class Readings {
        LocalDate d0;
        LocalDate t1;
        /** 系统 B 用老龙的行业当板块，反查不到就是 null。 */
        String sector;
        Boolean anchorMatched;
        /** 他登记的老龙原文，与 {@link #anchorName} 并排显示才看得出匹配对不对。 */
        String anchorInput;
        String anchorCode;
        String anchorName;
        /** 名字没对上、靠 LIKE 兜住的那只，要在界面上说清楚。 */
        boolean anchorFuzzy;
        /** 老龙在窗口内的全部明细行，D0 是否跌停 / T+1 是否反包 / 之后有没有跌停都从这几行读。 */
        List<MarketStock> anchorRows = new ArrayList<>();
        Integer limitDownCount;
        Integer limitUpCount;
        /** D0 起往前（含 D0）的连板高度，倒序：[D0, D0-1, ...]。 */
        List<Integer> heights = new ArrayList<>();
        /** D0 候选票（A＝全部二板，B＝板块内首板）。 */
        List<MarketStock> candidates = new ArrayList<>();
        /** T+1 涨停池 code → 连板数。null = 那天没明细，代表"未知"而不是"都没晋级"。 */
        Map<String, Integer> t1Boards;
        /** 候选票在 D0 之后（含 D0）出现过的最高连板数。 */
        Map<String, Integer> maxBoards = new TreeMap<>();
        List<String> missing = new ArrayList<>();
    }

    private Readings fetch(NodeEvent node, Long userId) {
        Readings r = new Readings();
        r.d0 = node.getD0Date();
        if (r.d0 == null) {
            r.missing.add("D0 日期未填：没有断板日，整套判据一条都判不起来");
            return r;
        }
        readFilterRecord(r, r.d0);
        readAnchor(r, node);
        readT1(r, node);
        readCandidates(r, node);
        return r;
    }

    /**
     * 前置过滤器四项里的两个家数，加连板高度序列，全在 {@code t_market_daily}——全局客观读数，
     * 不按账号区分（隔离前寄生在 t_daily_record，按用户查会漏掉没复盘的日子）。
     */
    private void readFilterRecord(Readings r, LocalDate d0) {
        MarketDaily day = marketDailyMapper.selectOne(new LambdaQueryWrapper<MarketDaily>()
                .eq(MarketDaily::getTradeDate, d0)
                .last("LIMIT 1"));
        if (day != null) {
            r.limitDownCount = day.getLimitDownCount();
            r.limitUpCount = day.getLimitUpCount();
        }
        List<MarketDaily> back = marketDailyMapper.selectList(new LambdaQueryWrapper<MarketDaily>()
                .le(MarketDaily::getTradeDate, d0)
                .orderByDesc(MarketDaily::getTradeDate)
                .last("LIMIT " + (HEIGHT_COMPRESS_DAYS + 1)));
        for (MarketDaily each : back) {
            r.heights.add(each.getMaxConsecutiveLimit());
        }
    }

    /**
     * 老龙按名字反查明细表。他手打的是简称，明细里的名字可能略有出入，所以精确匹配优先、
     * 退一步用 LIKE；LIKE 命中多只不同代码时<b>不猜</b>，直接报缺数。
     */
    private void readAnchor(Readings r, NodeEvent node) {
        String input = trim(node.getAnchorStock());
        r.anchorInput = input;
        if (input.isEmpty()) {
            r.anchorMatched = false;
            r.missing.add("锚定龙头未登记：老龙反不反包是系统A 的一票否决项，判不了就没有结论");
            return;
        }
        LocalDate from = r.d0.minusDays(ANCHOR_LOOKBACK_DAYS);
        LocalDate to = r.d0.plusDays(KILL_WATCH_DAYS);
        List<MarketStock> rows = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .ge(MarketStock::getTradeDate, from)
                .le(MarketStock::getTradeDate, to)
                .and(w -> w.eq(MarketStock::getName, input).or().like(MarketStock::getName, input)));
        Map<String, List<MarketStock>> byCode = new TreeMap<>();
        for (MarketStock row : rows) {
            byCode.computeIfAbsent(row.getCode(), key -> new ArrayList<>()).add(row);
        }
        String exact = exactCodeOf(rows, input);
        if (byCode.isEmpty()) {
            r.anchorMatched = false;
            r.missing.add("「" + input + "」在 " + from + "~" + to + " 的盘面明细里找不到："
                    + "老龙那几天的涨跌都没记录，反包与 A 杀都判不了（先去拉那几天的行情）");
            return;
        }
        if (exact == null && byCode.size() > 1) {
            r.anchorMatched = false;
            r.missing.add("「" + input + "」匹配到 " + byCode.size() + " 只不同的票（"
                    + join(new ArrayList<>(byCode.keySet()), "、") + "），不知道你说的是哪一只");
            return;
        }
        List<MarketStock> picked = exact == null ? byCode.values().iterator().next() : byCode.get(exact);
        r.anchorMatched = true;
        r.anchorFuzzy = exact == null;
        r.anchorRows = picked;
        r.anchorCode = picked.get(0).getCode();
        r.anchorName = picked.get(0).getName();
        r.sector = industryOf(picked, r.d0);
    }

    /** 有没有哪一行的名字正好等于他打的字；有就只认那一行的代码。 */
    private static String exactCodeOf(List<MarketStock> rows, String input) {
        for (MarketStock row : rows) {
            if (input.equals(row.getName())) {
                return row.getCode();
            }
        }
        return null;
    }

    /** 板块跟着断板那天取：那几天老龙一直在同一个行业里，D0 那行最贴近"它代表哪个板块"。 */
    private static String industryOf(List<MarketStock> rows, LocalDate d0) {
        for (MarketStock row : rows) {
            if (d0.isEqual(row.getTradeDate()) && notBlank(row.getIndustry())) {
                return row.getIndustry();
            }
        }
        for (MarketStock row : rows) {
            if (notBlank(row.getIndustry())) {
                return row.getIndustry();
            }
        }
        return null;
    }

    private void readT1(Readings r, NodeEvent node) {
        if (node.getT1Date() != null) {
            r.t1 = node.getT1Date();
            return;
        }
        r.t1 = marketStockMapper.nextDetailDate(r.d0);
        if (r.t1 == null) {
            r.missing.add("D0 之后还没有任何一天的盘面明细：T+1 是验证日，没到就没有结论");
        }
    }

    /**
     * 候选池与 T+1 晋级。A 取"D0 全部二板"，B 取"板块内 D0 首板"——
     * 两套判据唯一一处实质差别（§五 双轨对照表「D0核心标的」那一行）。
     */
    private void readCandidates(Readings r, NodeEvent node) {
        boolean systemB = SYSTEM_B.equals(node.getSystemType());
        int wantBoard = systemB ? 1 : 2;
        if (systemB && !notBlank(r.sector)) {
            r.missing.add("系统B 要按板块取 D0 首板，但老龙的行业没反查出来，板块范围无从定");
            return;
        }
        List<MarketStock> d0Rows = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, r.d0)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP)
                .eq(MarketStock::getConsecutive, wantBoard));
        for (MarketStock row : d0Rows) {
            if (systemB && !r.sector.equals(row.getIndustry())) {
                continue;
            }
            r.candidates.add(row);
        }
        if (r.candidates.isEmpty()) {
            r.missing.add(emptyPoolMessage(r, systemB));
            return;
        }
        // 「D0 之后最高板数」只依赖库里已有的明细，跟 T+1 拉没拉没关系。
        // 必须在下面两个早退之前算：否则 T+1 空缺时，连当天明摆着的二板都显示成"—"。
        fillMaxBoards(r);
        if (r.t1 == null) {
            return;
        }
        List<MarketStock> t1Rows = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, r.t1)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP));
        if (t1Rows.isEmpty()) {
            r.missing.add(r.t1 + " 没有任何盘面明细：T+1 的晋级判不了，先去拉那天的行情");
            return;
        }
        Map<String, Integer> boards = new TreeMap<>();
        for (MarketStock row : t1Rows) {
            if (row.getConsecutive() != null) {
                boards.put(row.getCode(), row.getConsecutive());
            }
        }
        r.t1Boards = boards;
    }

    /** 候选票从 D0 往后逐日的最高连板：本地一次查询，不打任何上游。 */
    private void fillMaxBoards(Readings r) {
        List<String> codes = new ArrayList<>();
        for (MarketStock row : r.candidates) {
            codes.add(row.getCode());
        }
        List<MarketStock> since = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .ge(MarketStock::getTradeDate, r.d0)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP)
                .in(MarketStock::getCode, codes));
        for (MarketStock row : since) {
            if (row.getConsecutive() != null) {
                Integer best = r.maxBoards.get(row.getCode());
                if (best == null || row.getConsecutive() > best) {
                    r.maxBoards.put(row.getCode(), row.getConsecutive());
                }
            }
        }
    }

    // ---------- 判定 ----------

    /** 纯函数：原料齐了就能跑，单测直接手搓 {@link Readings} 加一个新 ObjectMapper，不打库也不起 Spring。 */
    static NodeSuggestVO decide(NodeEvent node, Readings r, ObjectMapper json) {
        NodeSuggestVO vo = new NodeSuggestVO();
        vo.setNodeId(node.getId());
        boolean systemB = SYSTEM_B.equals(node.getSystemType());
        vo.setSystemType(systemB ? SYSTEM_B : SYSTEM_A);
        vo.setT1Date(r.t1);
        if (r.d0 == null) {
            vo.setSuggestedStatus(STATUS_PENDING);
            vo.setStatus(STATUS_PENDING);
            vo.getMissing().addAll(r.missing);
            vo.setReady(false);
            vo.setReason("判据不齐：" + join(vo.getMissing(), "；"));
            vo.setFingerprint("");
            return vo;
        }
        if (!notBlank(node.getSystemType())) {
            vo.getWarnings().add("系统类型没填，按系统A（市场总节点）判");
        }
        vo.setFilter(buildFilter(r));
        vo.setFilterAllPass(allPass(vo.getFilter()));
        vo.setAnchor(anchorVO(r));

        List<NodeSuggestVO.Candidate> cands = candidates(r);
        int total = cands.size();
        int count = 0;
        for (NodeSuggestVO.Candidate each : cands) {
            if (Boolean.TRUE.equals(each.getPromoted())) {
                count++;
            }
        }
        // T+1 没有明细时这个 count=0 是"还没数过"，不是"一只都没晋级"。
        // 数量和率绑在一起走：只把数留空、率却算出个 0.00%，等于用一半的未知去坐实另一半。
        boolean promotable = r.t1Boards != null && total > 0;
        BigDecimal rate = promotable ? percentage(count, total) : null;
        Integer repack = repackFlag(r);

        NodeSuggestVO.Promotion promotion = new NodeSuggestVO.Promotion();
        promotion.setTotal(total);
        promotion.setCount(promotable ? count : null);
        promotion.setRate(rate);
        promotion.setItems(cands);
        promotion.setThreshold(systemB
                ? "§四 板块节点：T+1 一进二 ≥" + B_MIN_PROMOTION + " 只 → 有效"
                : "§三 市场总节点：T+1 晋级 ≥" + A_STRONG_PROMOTION + " 只 且 晋级率 ≥"
                        + A_MIN_RATE.toPlainString() + "% → 有效");
        promotion.setBasis(basis(r, systemB, total));
        vo.setPromotion(promotion);
        vo.setT1PromotionCount(promotion.getCount());
        vo.setT1PromotionRate(rate);
        vo.setT1AnchorRepack(repack);

        boolean decided = repack != null && r.t1Boards != null && total > 0;
        String status = decided
                ? verdict(systemB, repack == 1, count, rate) : STATUS_PENDING;
        vo.setSuggestedStatus(status);
        vo.setStatus(status);
        vo.setNodeValid(STATUS_VALID.equals(status) ? 1 : 0);
        vo.setConclusionReason(conclusionReason(systemB, repack != null && repack == 1, count, rate, status));

        NodeSuggestVO.Candidate leader = strongest(cands);
        if (leader != null) {
            vo.setNodeStock(leader.getName() + "(" + leader.getCode() + ")");
            vo.setNodeStockMaxBoard(leader.getMaxBoard());
        } else {
            // 挑不出节点票时不动他已有的登记：采纳一次不该把自己没算出来的东西写成空
            vo.setNodeStock(node.getNodeStock() == null ? "" : node.getNodeStock());
            vo.setNodeStockMaxBoard(node.getNodeStockMaxBoard() == null ? 0 : node.getNodeStockMaxBoard());
        }
        vo.getMissing().addAll(r.missing);
        if (!decided && vo.getMissing().isEmpty()) {
            // 取数侧每种判不了都留了一句具体的，这句是兜底：不变式"判不了 ⇒ 不 ready"不能靠约定撑
            vo.getMissing().add("T+1 的晋级与老龙反包至少有一项判不了，状态留在待验证");
        }
        vo.setReady(vo.getMissing().isEmpty());
        vo.setReason(reason(r, vo, systemB, count, total, rate, status));
        addWarnings(r, vo);
        vo.setFingerprint(fingerprint(json, vo));
        return vo;
    }

    /**
     * §三 判定规则与 §三 操作流程 T+1 那两条合起来的一句话：反包或 0 只即作废，齐了才谈得上有效。
     *
     * <p>系统B 不看反包——「次日龙反包」只出现在系统A 那张表里，板块节点的失效信号是另外三条
     * （§四 失效信号）。这里按原文的字面范围判，反包在 B 路径上只作为提示出现在 warnings。
     */
    static String verdict(boolean systemB, boolean repack, int count, BigDecimal rate) {
        if (count == 0 || (repack && !systemB)) {
            return STATUS_INVALID;
        }
        if (systemB) {
            return count >= B_MIN_PROMOTION ? STATUS_VALID : STATUS_PENDING;
        }
        boolean enough = count >= A_STRONG_PROMOTION && rate != null && rate.compareTo(A_MIN_RATE) >= 0;
        return enough ? STATUS_VALID : STATUS_PENDING;
    }

    /**
     * 细分原因（写出即权威）：失效优先按「老龙反包作废」，否则按「晋级清零」；
     * 有效按判据档位标强/中。与 {@link #verdict} 一一对应，不能出现判定之外的理由。
     */
    static String conclusionReason(boolean systemB, boolean repack, int count, BigDecimal rate, String status) {
        if (STATUS_INVALID.equals(status)) {
            return !systemB && repack ? "反包失效" : "晋级清零失效";
        }
        if (STATUS_VALID.equals(status)) {
            if (systemB) {
                return "有效·板块达标";
            }
            return count > A_STRONG_PROMOTION ? "有效·强" : "有效·中等";
        }
        return null;
    }

    /** 最强节点票：D0 之后最高板数最大的那只；并列取代码小的，纯为让结论可复现。 */
    private static NodeSuggestVO.Candidate strongest(List<NodeSuggestVO.Candidate> cands) {
        List<NodeSuggestVO.Candidate> promoted = new ArrayList<>();
        for (NodeSuggestVO.Candidate each : cands) {
            if (Boolean.TRUE.equals(each.getPromoted()) && each.getMaxBoard() != null) {
                promoted.add(each);
            }
        }
        if (promoted.isEmpty()) {
            return null;
        }
        Collections.sort(promoted, new Comparator<NodeSuggestVO.Candidate>() {
            @Override
            public int compare(NodeSuggestVO.Candidate a, NodeSuggestVO.Candidate b) {
                int byBoard = b.getMaxBoard().compareTo(a.getMaxBoard());
                return byBoard != 0 ? byBoard : a.getCode().compareTo(b.getCode());
            }
        });
        return promoted.get(0);
    }

    private static List<NodeSuggestVO.Candidate> candidates(Readings r) {
        List<NodeSuggestVO.Candidate> out = new ArrayList<>();
        for (MarketStock row : r.candidates) {
            NodeSuggestVO.Candidate each = new NodeSuggestVO.Candidate();
            each.setCode(row.getCode());
            each.setName(row.getName());
            each.setIndustry(row.getIndustry());
            Integer d0Board = row.getConsecutive();
            Integer t1Board = r.t1Boards == null ? null : r.t1Boards.get(row.getCode());
            each.setT1Consecutive(t1Board);
            each.setPromoted(t1Board == null || d0Board == null ? null : t1Board > d0Board);
            each.setMaxBoard(r.maxBoards.get(row.getCode()));
            out.add(each);
        }
        return out;
    }

    /** 1=反包（节点作废），0=没反包，null=判不了。兜成 0 会把"没拉行情"讲成"老龙没反包，可以出手"。 */
    private static Integer repackFlag(Readings r) {
        if (!Boolean.TRUE.equals(r.anchorMatched) || r.t1 == null || r.t1Boards == null) {
            return null;
        }
        for (MarketStock row : r.anchorRows) {
            if (r.t1.isEqual(row.getTradeDate()) && MarketStock.POOL_LIMIT_UP.equals(row.getPool())) {
                return 1;
            }
        }
        return 0;
    }

    private static List<NodeSuggestVO.FilterItem> buildFilter(Readings r) {
        List<NodeSuggestVO.FilterItem> out = new ArrayList<>();
        out.add(item("limitDownCount", "跌停 < " + MAX_LIMIT_DOWN + "家",
                r.limitDownCount == null ? null : r.limitDownCount + " 家",
                r.limitDownCount == null ? null : r.limitDownCount < MAX_LIMIT_DOWN,
                r.d0 + " 的复盘记录 limit_down_count"));
        out.add(item("limitUpCount", "涨停 > " + MIN_LIMIT_UP + "家",
                r.limitUpCount == null ? null : r.limitUpCount + " 家",
                r.limitUpCount == null ? null : r.limitUpCount > MIN_LIMIT_UP,
                r.d0 + " 的复盘记录 limit_up_count"));
        Boolean killed = anchorKilled(r);
        out.add(item("anchorKill", "老龙非A杀",
                killed == null ? null : (killed ? "D0 进了跌停池" : "D0 未跌停"), killed == null ? null : !killed,
                "近似判据：只看老龙 D0 有没有跌停。明细里跌停价是空的，「未一字跌停」判不了字面意思"));
        Boolean compressed = heightCompressed(r.heights);
        out.add(item("heightCompress", "高度未连续压缩",
                compressed == null ? "读数不足" : (compressed ? "连降 " + HEIGHT_COMPRESS_DAYS + " 天" : "正常"),
                compressed == null ? null : !compressed,
                "连板高度从 D0 往前共 " + (HEIGHT_COMPRESS_DAYS + 1) + " 个读数逐日严格走低即判压缩，"
                        + "实际序列 " + heights(r.heights)));
        return out;
    }

    private static String heights(List<Integer> heights) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < heights.size(); i++) {
            if (i > 0) {
                text.append("→");
            }
            text.append(heights.get(i) == null ? "?" : heights.get(i).toString());
        }
        return text.append("]").toString();
    }

    private static NodeSuggestVO.FilterItem item(String key, String label, String actual,
                                                 Boolean pass, String source) {
        NodeSuggestVO.FilterItem each = new NodeSuggestVO.FilterItem();
        each.setKey(key);
        each.setLabel(label);
        each.setActual(actual == null ? "无读数" : actual);
        each.setPass(pass);
        each.setSource(source);
        return each;
    }

    /** 三态与门：任何一条没有读数就是"未知"，不能因为别的都过就报通过。 */
    private static Boolean allPass(List<NodeSuggestVO.FilterItem> items) {
        for (NodeSuggestVO.FilterItem each : items) {
            if (!Boolean.TRUE.equals(each.getPass())) {
                return each.getPass() == null ? null : false;
            }
        }
        return items.isEmpty() ? null : true;
    }

    private static Boolean anchorKilled(Readings r) {
        if (!Boolean.TRUE.equals(r.anchorMatched)) {
            return null;
        }
        for (MarketStock row : r.anchorRows) {
            if (r.d0.isEqual(row.getTradeDate()) && MarketStock.POOL_LIMIT_DOWN.equals(row.getPool())) {
                return true;
            }
        }
        return false;
    }

    /** 连续 3 天压缩 = 连降三天，需要 4 个读数。任一读数缺席只能报未知，兜成"没压缩"等于让缺数过过滤器。 */
    static Boolean heightCompressed(List<Integer> heights) {
        if (heights == null || heights.size() < HEIGHT_COMPRESS_DAYS + 1) {
            return null;
        }
        for (int i = 1; i <= HEIGHT_COMPRESS_DAYS; i++) {
            Integer newer = heights.get(i - 1);
            Integer older = heights.get(i);
            if (newer == null || older == null) {
                return null;
            }
            if (newer >= older) {
                return false;
            }
        }
        return true;
    }

    private static NodeSuggestVO.AnchorReadings anchorVO(Readings r) {
        NodeSuggestVO.AnchorReadings out = new NodeSuggestVO.AnchorReadings();
        out.setInput(r.anchorInput);
        out.setMatched(r.anchorMatched);
        out.setCode(r.anchorCode);
        out.setName(r.anchorName);
        out.setIndustry(r.sector);
        MarketStock last = null;
        for (MarketStock row : r.anchorRows) {
            if (MarketStock.POOL_LIMIT_UP.equals(row.getPool()) && row.getConsecutive() != null
                    && (last == null || row.getTradeDate().isAfter(last.getTradeDate()))) {
                last = row;
            }
        }
        if (last != null) {
            out.setLastLimitDate(last.getTradeDate().toString());
            out.setLastConsecutive(last.getConsecutive());
        }
        return out;
    }

    /**
     * 候选池为空这句必须说的是"分母是空的"，不能是"0 只晋级"：后者是一个完全正常的结论，
     * 会直接撞进 §三 判定规则里"0 只 → 失败"那一条，把一个没拉过行情的日子判成失效节点。
     */
    static String emptyPoolMessage(Readings r, boolean systemB) {
        return r.d0 + " 涨停池里" + (systemB ? "没有「" + r.sector + "」板块的首板" : "没有二连板")
                + "：D0 候选池是空的，晋级率无从算起";
    }

    private static String basis(Readings r, boolean systemB, int total) {
        String pool = systemB
                ? r.d0 + " 涨停池「" + r.sector + "」板块的首板"
                : r.d0 + " 涨停池的二连板";
        return "分母＝" + pool + "复算，共 " + total + " 只；"
                + (r.t1 == null ? "T+1 未定" : "分子＝这些代码在 " + r.t1 + " 仍涨停且连板数更高的只数");
    }

    private static String reason(Readings r, NodeSuggestVO vo, boolean systemB,
                                 int count, int total, BigDecimal rate, String status) {
        if (!vo.isReady()) {
            return "判据不齐：" + join(vo.getMissing(), "；");
        }
        String anchor = vo.getAnchor().getName() == null
                ? "老龙未匹配" : vo.getAnchor().getName() + "(" + vo.getAnchor().getCode() + ")";
        StringBuilder text = new StringBuilder();
        text.append(systemB ? "系统B｜" : "系统A｜");
        text.append("D0 ").append(r.d0).append(" ").append(anchor);
        text.append("；T+1 ").append(r.t1).append(" 老龙").append(
                vo.getT1AnchorRepack() == null ? "反包未知"
                        : (vo.getT1AnchorRepack() == 1 ? "反包" : "未反包"));
        text.append("；").append(systemB ? "板块一进二 " : "D0 二板 ").append(total)
                .append(" 只晋级 ").append(count).append(" 只");
        if (rate != null) {
            text.append("（").append(rate.toPlainString()).append("%，").append(systemB
                    ? "判据 ≥" + B_MIN_PROMOTION + " 只"
                    : "判据 ≥" + A_STRONG_PROMOTION + " 只且 ≥" + A_MIN_RATE.toPlainString() + "%").append("）");
        }
        text.append(" → ").append(status);
        if (!systemB && STATUS_VALID.equals(status)) {
            text.append(count > A_STRONG_PROMOTION ? "（强节点）" : "（中等）");
        }
        if (notBlank(vo.getNodeStock())) {
            text.append("；节点票 ").append(vo.getNodeStock())
                    .append(" 最高 ").append(vo.getNodeStockMaxBoard()).append(" 板");
        }
        text.append("；前置过滤器").append(vo.getFilterAllPass() == null ? "读数不全"
                : (vo.getFilterAllPass() ? "全过" : "未全过"));
        return text.toString();
    }

    /** 不参与判定的提醒都堆在这里：他文档里"提一句"的话，平台不该悄悄吞掉，也不该越权拿去出结论。 */
    private static void addWarnings(Readings r, NodeSuggestVO vo) {
        if (r.anchorFuzzy) {
            vo.getWarnings().add("锚定龙头你写的是「" + r.anchorInput + "」，按名字模糊匹配到了 "
                    + r.anchorName + "(" + r.anchorCode + ")。不是这一只的话说一声，判据得重跑");
        }
        List<String> killed = new ArrayList<>();
        for (MarketStock row : r.anchorRows) {
            if (MarketStock.POOL_LIMIT_DOWN.equals(row.getPool()) && row.getTradeDate().isAfter(r.d0)) {
                killed.add(row.getTradeDate().toString());
            }
        }
        if (!killed.isEmpty()) {
            vo.getWarnings().add("老龙在 D0 之后进了跌停池：" + join(killed, "、")
                    + "。§三 失效信号里的「老龙A杀（连续跌停）」按原文不参与状态判定，这里只提一句，"
                    + "要不要就此收手你定");
        }
        if (Boolean.FALSE.equals(vo.getFilterAllPass())) {
            vo.getWarnings().add("前置过滤器未全过：§八 使用说明第 2 条写的是部分通过只走系统B、"
                    + "不通过空仓。这条状态只回答\"节点成不成立\"，不回答\"该不该下手\"");
        }
        if (notBlank(vo.getNodeStock()) && vo.getPromotion() != null
                && vo.getPromotion().getCount() != null && vo.getPromotion().getCount() > 1) {
            vo.getWarnings().add("节点票取的是晋级票里 D0 之后最高板数最大的那一只，其余晋级票在下方逐只列出——"
                    + "谁是切换核心是你的判断，平台只做排序");
        }
    }

    // ---------- 指纹 ----------

    static String fingerprint(ObjectMapper json, NodeSuggestVO vo) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", text(vo.getStatus()));
        values.put("t1Date", vo.getT1Date() == null ? "" : vo.getT1Date().toString());
        values.put("t1AnchorRepack", vo.getT1AnchorRepack() == null ? "" : vo.getT1AnchorRepack().toString());
        values.put("t1PromotionCount",
                vo.getT1PromotionCount() == null ? "" : vo.getT1PromotionCount().toString());
        values.put("t1PromotionRate",
                vo.getT1PromotionRate() == null ? "" : vo.getT1PromotionRate().toPlainString());
        values.put("nodeValid", vo.getNodeValid() == null ? "" : vo.getNodeValid().toString());
        values.put("nodeStock", text(vo.getNodeStock()));
        values.put("nodeStockMaxBoard",
                vo.getNodeStockMaxBoard() == null ? "" : vo.getNodeStockMaxBoard().toString());
        try {
            return json.writeValueAsString(values);
        } catch (Exception e) {
            // 一串字符串值不可能序列化失败；真出了事也不能让建议接口整个红掉
            return "";
        }
    }

    /**
     * 他看到的那八个值 vs 现在算出来的八个值，不一样的逐条报出来。
     *
     * <p>只说"不一致"等于让他猜哪一格变了——这里报的是"晋级数量：5 → 现在算出 6"这种能当场看懂的话。
     */
    static List<String> diff(ObjectMapper json, String seen, NodeSuggestVO fresh) {
        JsonNode was;
        JsonNode now;
        try {
            was = json.readTree(seen == null || seen.trim().isEmpty() ? "{}" : seen);
            now = json.readTree(fingerprint(json, fresh));
        } catch (Exception e) {
            throw new IllegalArgumentException("采纳请求里的指纹解析不了，请刷新建议后重试");
        }
        List<String> diffs = new ArrayList<>();
        for (String key : FP_KEYS) {
            String before = was.path(key).asText("");
            String after = now.path(key).asText("");
            if (!before.equals(after)) {
                diffs.add(FP_LABELS.get(key) + "：" + (before.isEmpty() ? "无" : before)
                        + " → 现在算出 " + (after.isEmpty() ? "无" : after));
            }
        }
        return diffs;
    }

    // ---------- 杂项 ----------

    private NodeEvent own(Long userId, Long id) {
        NodeEvent node = nodeEventMapper.selectOne(new LambdaQueryWrapper<NodeEvent>()
                .eq(NodeEvent::getId, id)
                .eq(NodeEvent::getUserId, userId));
        if (node == null) {
            throw new IllegalArgumentException("节点事件不存在：" + id);
        }
        return node;
    }

    static BigDecimal percentage(int count, int total) {
        return new BigDecimal(count * 100).divide(new BigDecimal(total), 2, RoundingMode.HALF_UP);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(sep);
            }
            text.append(part);
        }
        return text.toString();
    }

    private static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }
}
