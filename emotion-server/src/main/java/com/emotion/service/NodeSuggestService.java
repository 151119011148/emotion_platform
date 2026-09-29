package com.emotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketDaily;
import com.emotion.entity.MarketStock;
import com.emotion.entity.NodeEvent;
import com.emotion.mapper.MarketDailyMapper;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.NodeEventMapper;
import com.emotion.vo.BreakDetailVO;
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
 *   <li>候选池按盘面明细复算，<b>不用</b>他手打的 {@code d0_candidates}，也不是写死的"D0 二板"：
 *       哪天、几板由老龙的放量日决定（{@link #pickCandidatePool}）。他手打的那份是盘中的判断，
 *       两者不一致时在这里看得出来，才谈得上核对。</li>
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
    /**
     * §三 系统A 的 D0 候选池＝D0 当天全部<b>二板</b>。
     *
     * <p>这是「老龙在断板前一日就放量」那一支的板数：钱在前一日已经下来，那天的首板到 D0 正是二板，
     * 两个取法数到的是同一批票。前一日没放量时候选池改跟放量日走、板数取首板，见
     * {@link #pickCandidatePool}——高低切切的是资金，资金没从上面下来就不许去数二板。
     *
     * <p>候选池打标（{@code NodeService.d0CandidatePools}）仍用这一个数，它标的是「D0 有候选」这件事；
     * 接位的候选池现在可能不是 D0，两处口径已不同，这一点在标说明里写明白。
     */
    static final int A_CANDIDATE_BOARD = 2;
    /**
     * 放量倍率：老龙当日成交额 ≥ 它<b>上一有成交记录日</b>的 1.5 倍即算放量。
     *
     * <p>他文档里「放量」只有定性说法（{@code 04_题材与龙头联动.md} 的「老龙头/高位放量大面」），
     * 没有数值口径，这条常量是照两个真实样本的分离点定的（金额取自生产库 {@code t_market_stock.amount}）：
     * 华瓷股份 09-22 是 0.48 亿 ÷ 09-21 0.51 亿＝0.95 倍（未放量）、09-23 炸板当天 16.26 亿＝33.59 倍（放量）；
     * 深中华Ａ 08-28 是 12.53 亿 ÷ 08-27 6.06 亿＝2.07 倍（放量）。1.2~2.0 之间任意值都判得一样，
     * <strong>要改口径只改这一个数</strong>。
     */
    static final BigDecimal HEAVY_AMOUNT_RATIO = new BigDecimal("1.5");
    /** 断板后往后找几天「资金下来」的那一天：与 {@link #KILL_WATCH_DAYS} 同一天数，断板后盯几天是一回事。 */
    static final int HEAVY_SEARCH_DAYS = 5;
    /** §三 系统A 操作流程 T+1「收盘确认：D0 二板晋级率 ≥ 30% → 节点有效」。百分数。 */
    static final BigDecimal A_MIN_RATE = new BigDecimal("30.00");
    /**
     * §四 系统B 判定规则 T+1「看该板块首板的一进二 ≥2 只晋级 → 板块节点有效」。
     *
     * <p><strong>2026-09-23 已下线</strong>：板块节点不再对外提供（NodeView 已移除全部 B 类入口，
     * 生产库 B 类历史数据 0 条）。此处分支保留只为兼容存量脏数据——只要没人再写 B，
     * systemB 恒为 false，这段就走不到。整体删除排在破局节点（V34）之后，不混在同一次改动里。
     */
    static final int B_MIN_PROMOTION = 2;

    /**
     * 高低切分流的助攻下限：接位票所在属性在分流窗口内要有 ≥2 只别的涨停票跟进才算方向立住。
     * 不足这个数就还挂在接位上，等窗口下一天。
     */
    static final int MIN_SUPPORT = 2;

    /** 类型码·接位：老龙断板当天有票接住，但方向未定，分流窗口内还在等助攻。 */
    static final String TYPE_SPLIT_PENDING = "SPLIT_PENDING";
    /** 类型码·补位：接位票与老龙同属性，借的是老龙的题材余温。 */
    static final String TYPE_FILL_SAME = "FILL_SAME";
    /** 类型码·转切：接位票与老龙异属性，借的是老龙断板腾出来的势，走的是新方向。 */
    static final String TYPE_SWITCH_CROSS = "SWITCH_CROSS";

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
    /** 板块节点类型码。已下线：仅供识别存量脏数据，不得用于新建分支。 */
    static final String SYSTEM_B = "B";

    /**
     * 采纳要落的字段：<b>指纹、比对、写库三处共用这几张表——漏一个字段就是点了个假的采纳。</b>
     *
     * <p>高低切一族十键（原样九键 + 候选池），破壁两行六键：这里没有老龙反包、没有晋级数与晋级率，
     * 多出来的是"次日续没续板"那一格 {@code repairStatus}。{@link #fpKeys} 选哪一张，
     * {@code adopt()} 就落哪几格——两边必须同时改。
     */
    private static final List<String> FP_KEYS = Collections.unmodifiableList(Arrays.asList(
            "status", "t1Date", "t1AnchorRepack", "t1PromotionCount", "t1PromotionRate",
            "nodeValid", "nodeStock", "nodeStockMaxBoard", "nodeType", "candidatePool"));
    private static final List<String> BREAK_FP_KEYS = Collections.unmodifiableList(Arrays.asList(
            "status", "t1Date", "nodeStock", "nodeStockMaxBoard", "nodeType", "repairStatus"));
    private static final Map<String, String> FP_LABELS = labels();

    /** 这一条走哪张键表：按<b>建议</b>的类型分流，破壁两行只认自己那六格。 */
    static List<String> fpKeys(NodeSuggestVO vo) {
        return NodeBreakService.isBreakType(vo.getNodeType()) ? BREAK_FP_KEYS : FP_KEYS;
    }

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
        map.put("nodeType", "节点类型");
        map.put("candidatePool", "候选池");
        map.put("repairStatus", "续板判定");
        return Collections.unmodifiableMap(map);
    }

    private final NodeEventMapper nodeEventMapper;
    private final MarketStockMapper marketStockMapper;
    private final MarketDailyMapper marketDailyMapper;
    private final ObjectMapper json;
    private final IndustryClassifyService industryClassify;
    /** 破壁两行的取数口：曲线那个点、它的次日、助攻盘口与闸门，判定一份都不在这儿重写。 */
    private final BreakDetailService breakDetailService;

    public NodeSuggestService(NodeEventMapper nodeEventMapper,
                              MarketStockMapper marketStockMapper,
                              MarketDailyMapper marketDailyMapper,
                              ObjectMapper json,
                              IndustryClassifyService industryClassify,
                              BreakDetailService breakDetailService) {
        this.nodeEventMapper = nodeEventMapper;
        this.marketStockMapper = marketStockMapper;
        this.marketDailyMapper = marketDailyMapper;
        this.json = json;
        this.industryClassify = industryClassify;
        this.breakDetailService = breakDetailService;
    }

    public NodeSuggestVO suggest(Long userId, Long id) {
        NodeEvent node = own(userId, id);
        // 破壁两行判的是"次日续没续板"，跟高低切那套晋级率没有关系：按登记的类型分流，两条路互不改
        return breakRow(node)
                ? decideBreak(node, fetchBreak(node, userId), json)
                : decide(node, fetch(node, userId), json);
    }

    private static boolean breakRow(NodeEvent node) {
        return NodeBreakService.isBreakType(node.getNodeType());
    }

    /**
     * 落库。<b>服务端重算一遍再逐字段比对</b>，不一致整条拒绝。
     *
     * <p>不能照抄前端传来的 body 落库：他点的那条建议和最后写进去的那个数必须是同一个东西，
     * 而"看到建议"和"点采纳"之间他完全可以又拉了一次行情、把那天的明细换了。
     */
    public NodeEvent adopt(Long userId, Long id, String fingerprint) {
        NodeEvent node = own(userId, id);
        boolean breakRow = breakRow(node);
        NodeSuggestVO fresh = breakRow
                ? decideBreak(node, fetchBreak(node, userId), json)
                : decide(node, fetch(node, userId), json);
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
        node.setNodeValid(fresh.getNodeValid());
        node.setNodeStock(fresh.getNodeStock());
        node.setNodeStockMaxBoard(fresh.getNodeStockMaxBoard());
        if (breakRow) {
            // 老龙反包／晋级数／晋级率是高低切的账，破壁行一次采纳都不该碰这三格
            if (fresh.getRepairStatus() != null) {
                node.setRepairStatus(fresh.getRepairStatus());
            }
        } else {
            node.setT1AnchorRepack(fresh.getT1AnchorRepack());
            node.setT1PromotionCount(fresh.getT1PromotionCount());
            node.setT1PromotionRate(fresh.getT1PromotionRate());
            // 接位认的是哪一天的几板：判据跟着老龙放量日走，这一格就是那次采纳认下来的池子
            node.setCandidatePool(fresh.getCandidatePool());
        }
        node.setStatusNote(cut(fresh.getReason(), STATUS_NOTE_MAX));
        node.setConclusionReason(fresh.getConclusionReason());
        // 没判出类型就不动他已有的标：这条采纳的是状态与晋级，不该把自己没算出来的东西抹成空
        if (fresh.getNodeType() != null) {
            node.setNodeType(fresh.getNodeType());
        }
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
        /**
         * 接位的候选池：哪一天、取几板，由老龙的放量日决定（{@link #pickCandidatePool}）。
         * 高低切分支必填；判不了放量时它是「D0 + 二板」这一支的兜底，{@code fallback} 标着兜过底。
         */
        CandidatePool pool;
        Integer limitDownCount;
        Integer limitUpCount;
        /** D0 起往前（含 D0）的连板高度，倒序：[D0, D0-1, ...]。 */
        List<Integer> heights = new ArrayList<>();
        /** 候选票（放量在前一日＝D0 全部二板；否则＝放量日的首板）。 */
        List<MarketStock> candidates = new ArrayList<>();
        /** T+1 涨停池 code → 连板数。null = 那天没明细，代表"未知"而不是"都没晋级"。 */
        Map<String, Integer> t1Boards;
        /** 候选票在<b>候选日</b>之后（含候选日）出现过的最高连板数。 */
        Map<String, Integer> maxBoards = new TreeMap<>();
        /**
         * 分流窗口（D0 之后 max(H−1,1) 个交易日）内逐日的涨停明细，行业已按 TDX 二级行业口径重映射。
         * 助攻只数从这里数，判据本身仍是纯函数。
         */
        List<MarketStock> supportRows = new ArrayList<>();
        /** 破壁分支的全部原料：曲线那一天的点、它的次日、助攻盘口与闸门；高低切分支恒 null。 */
        BreakDetailVO breakDetail;
        /** 窗口应有的交易日数：老龙断板前的板高 H 定出来的 max(H−1,1)，与破壁线钉线同一个参数。 */
        Integer splitWindowDays;
        /** 库里已经落了几天的明细。小于 {@link #splitWindowDays} 就说明窗口还没走完，接位还得等。 */
        int splitWindowLanded;
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
        readCandidatePool(r, node);
        readT1(r, node);
        readCandidates(r, node);
        readSupportWindow(r, node);
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
        if (industryClassify != null) {
            industryClassify.apply(rows);
        }
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

    // ---------- 候选池：接位跟老龙的放量日走，不跟断板日走 ----------

    /**
     * 接位候选池的四种落点。
     * <ol>
     *   <li>老龙<b>断板前一日就放量</b> → 候选＝D0 的二连板。钱在前一日已经下来，那天的首板到今天正是二板，
     *       两种数法数到的是同一批票（深中华Ａ：08-28 七板 12.53 亿对 08-27 6.06 亿，08-31 的国芳集团＝08-28 的首板）。</li>
     *   <li>前一日<b>明确没放量</b> → 往后找第一个放量日，候选＝<b>那天的首板</b>
     *       （华瓷股份：09-22 0.48 亿对 09-21 0.51 亿是缩量，09-23 炸板当天 16.26 亿才放量）。</li>
     *   <li>窗口内<b>没有一天放量</b> → 资金还没从上面下来，接位对象无从谈起：不给候选池，{@link #missing} 说话。</li>
     *   <li><b>比不了</b>（那几天没有成交额记录） → 退回第 1 条的取法，但 {@link #fallback} 标着：
     *       这是兜底不是结论，界面上必须出声。</li>
     * </ol>
     */
    static class CandidatePool {
        LocalDate date;
        Integer board;
        boolean fallback;
        /** 比的哪两天、各多少亿、几倍，全写在这儿：他得看得懂"为什么是这一天的首板"。 */
        String basis;
        /** 非空即接位不成立。 */
        String missing;

        /** 给指纹和界面用的一句：{@code 2026-09-23 首板}。判不出池时是空串。 */
        String label() {
            if (date == null || board == null) {
                return "";
            }
            return date + " " + boardName(board);
        }
    }

    /**
     * 纯函数：喂老龙的明细行和 D0 之后的交易日，吐出候选池。不打库、不看用户。
     *
     * <p>放量比的是<b>老龙自己上一有成交额记录的那一天</b>，不是全场交易日：它断板前后可能压根不在任何池里，
     * 那种日子算「未知」，既不能当没放量、也不能当放量——缺数不是结论。跌停日的大额成交一样算，
     * 他知识库里「老龙头/高位放量大面」说的就是出货也是资金下来。
     */
    static CandidatePool pickCandidatePool(LocalDate d0, List<MarketStock> anchorRows,
                                           List<LocalDate> forwardDates) {
        CandidatePool out = new CandidatePool();
        TreeMap<LocalDate, BigDecimal> amounts = new TreeMap<>();
        for (MarketStock row : anchorRows) {
            if (row.getTradeDate() == null || row.getAmount() == null) {
                continue;
            }
            BigDecimal best = amounts.get(row.getTradeDate());
            if (best == null || row.getAmount().compareTo(best) > 0) {
                amounts.put(row.getTradeDate(), row.getAmount());
            }
        }
        Map.Entry<LocalDate, BigDecimal> prev = amounts.lowerEntry(d0);
        if (prev == null) {
            return fallbackTwoBoard(out, d0, "老龙在 " + d0 + " 之前没有任何带成交额的盘面记录，"
                    + "断板前一日放没放量判不了");
        }
        Map.Entry<LocalDate, BigDecimal> base = amounts.lowerEntry(prev.getKey());
        if (base == null) {
            return fallbackTwoBoard(out, d0, prev.getKey() + " 是老龙有成交额记录里最早的一天，没有前一笔可比，"
                    + "放没放量判不了");
        }
        BigDecimal prevRatio = ratio(prev.getValue(), base.getValue());
        if (prevRatio == null) {
            return fallbackTwoBoard(out, d0, base.getKey() + " 老龙成交额是 0，倍数算不出来，放没放量判不了");
        }
        if (prevRatio.compareTo(HEAVY_AMOUNT_RATIO) >= 0) {
            out.date = d0;
            out.board = A_CANDIDATE_BOARD;
            out.basis = "老龙 " + prev.getKey() + " 成交 " + yi(prev.getValue()) + " 亿，是 " + base.getKey()
                    + " " + yi(base.getValue()) + " 亿的 " + prevRatio.toPlainString() + " 倍 ≥"
                    + HEAVY_AMOUNT_RATIO.toPlainString() + " → 钱在断板前一日已经下来，候选取 " + d0
                    + " 的二连板（＝放量日那批首板延续过来的一只不差）";
            return out;
        }
        List<String> trail = new ArrayList<>();
        for (LocalDate day : forwardDates) {
            if (day == null || day.isBefore(d0)) {
                continue;
            }
            BigDecimal amount = amounts.get(day);
            Map.Entry<LocalDate, BigDecimal> before = amounts.lowerEntry(day);
            if (amount == null || before == null) {
                trail.add(day + " 无成交额记录·未知");
                continue;
            }
            BigDecimal r = ratio(amount, before.getValue());
            if (r == null) {
                trail.add(day + " 前一笔为 0·判不了");
                continue;
            }
            trail.add(day + " " + r.toPlainString() + "倍");
            if (r.compareTo(HEAVY_AMOUNT_RATIO) >= 0) {
                out.date = day;
                out.board = 1;
                out.basis = "老龙断板前一日 " + prev.getKey() + " 只有 " + yi(prev.getValue()) + " 亿，对 "
                        + base.getKey() + " " + yi(base.getValue()) + " 亿是 " + prevRatio.toPlainString()
                        + " 倍——没放量，资金还挂在上面；到 " + day + " 才下来：" + yi(before.getValue())
                        + " 亿 → " + yi(amount) + " 亿 = " + r.toPlainString() + " 倍 ≥"
                        + HEAVY_AMOUNT_RATIO.toPlainString() + " → 候选取 " + day + " 的首板";
                return out;
            }
        }
        out.missing = "老龙断板前一日 " + prev.getKey() + " 没放量（" + yi(prev.getValue()) + " 亿对 "
                + base.getKey() + " " + yi(base.getValue()) + " 亿 = " + prevRatio.toPlainString() + " 倍），"
                + "往后 " + HEAVY_SEARCH_DAYS + " 个交易日也没有一天放量（" + join(trail, "、")
                + "）——资金还没从上面下来，高低切没开始，接位对象无从谈起";
        return out;
    }

    /** 兜底走原口径：判不了不等于没放量，但也不该让整页历史节点因为缺数而不能采纳。 */
    private static CandidatePool fallbackTwoBoard(CandidatePool out, LocalDate d0, String why) {
        out.date = d0;
        out.board = A_CANDIDATE_BOARD;
        out.fallback = true;
        out.basis = why + "——候选退回原口径取 " + d0 + " 的二连板。这是兜底，不是判出来的结论";
        return out;
    }

    /** 倍率：除不动（前一笔为 0 或缺）就返回 null，让调用方走"判不了"那一支，不兜成 0 倍。 */
    private static BigDecimal ratio(BigDecimal now, BigDecimal before) {
        if (now == null || before == null || before.signum() <= 0) {
            return null;
        }
        return now.divide(before, 2, RoundingMode.HALF_UP);
    }

    /** 成交额库里存的是元，说人话按亿。 */
    private static String yi(BigDecimal amount) {
        return amount.divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 取老龙自己的成交额序列 + D0 之后的交易日，交给 {@link #pickCandidatePool}。
     *
     * <p>单独一条查询、按代码取，<b>不动 {@code readAnchor} 那份 {@code anchorRows}</b>：那份窗口一放宽，
     * 「老龙断板后跌停」那句提醒和「最近一次涨停日」会跟着悄悄变远，那是两件不相干的事。
     */
    private void readCandidatePool(Readings r, NodeEvent node) {
        if (SYSTEM_B.equals(node.getSystemType())) {
            // 系统B 取的是板块内 D0 首板，本来就不数二板；已下线，只供存量脏数据显示建议
            CandidatePool pool = new CandidatePool();
            pool.date = r.d0;
            pool.board = 1;
            pool.basis = "系统B 按板块取 " + r.d0 + " 的首板，不走放量这条";
            r.pool = pool;
            return;
        }
        List<MarketStock> amountRows = Boolean.TRUE.equals(r.anchorMatched)
                ? marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                        .eq(MarketStock::getCode, r.anchorCode)
                        .ge(MarketStock::getTradeDate, r.d0.minusDays(ANCHOR_LOOKBACK_DAYS))
                        .le(MarketStock::getTradeDate, r.d0.plusDays(HEAVY_SEARCH_DAYS * 3L + 15)))
                : new ArrayList<>();
        List<LocalDate> dates = marketStockMapper.listDetailDatesBetween(
                r.d0, r.d0.plusDays(HEAVY_SEARCH_DAYS * 3L + 15));
        List<LocalDate> forward = dates.size() > HEAVY_SEARCH_DAYS
                ? dates.subList(0, HEAVY_SEARCH_DAYS) : dates;
        r.pool = pickCandidatePool(r.d0, amountRows, forward);
        if (r.pool.missing != null) {
            r.missing.add(r.pool.missing);
        }
    }

    /** T+1 是<b>候选池</b>的次日：候选日一挪，验证日、晋級、最高板、分流窗口都跟着它，不能还数断板日的下一天。 */
    private void readT1(Readings r, NodeEvent node) {
        if (node.getT1Date() != null) {
            r.t1 = node.getT1Date();
            return;
        }
        LocalDate from = r.pool != null && r.pool.date != null ? r.pool.date : r.d0;
        r.t1 = marketStockMapper.nextDetailDate(from);
        if (r.t1 == null) {
            r.missing.add(from + " 之后还没有任何一天的盘面明细：T+1 是验证日，没到就没有结论");
        }
    }

    /**
     * 候选池与 T+1 晋级。候选日与板数都由 {@link #pickCandidatePool} 定：
     * 老龙断板前一日就放量＝取 D0 的二连板，隔天才放量＝取那天的首板；系统B 是存量口径，取板块内 D0 首板。
     */
    private void readCandidates(Readings r, NodeEvent node) {
        boolean systemB = SYSTEM_B.equals(node.getSystemType());
        if (r.pool == null || r.pool.date == null || r.pool.board == null) {
            // 放量窗口内一天都没量：资金还没下来，接位对象无从谈起。缺数话 readCandidatePool 已经说过。
            return;
        }
        if (systemB && !notBlank(r.sector)) {
            r.missing.add("系统B 要按板块取 D0 首板，但老龙的行业没反查出来，板块范围无从定");
            return;
        }
        List<MarketStock> poolRows = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, r.pool.date)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP)
                .eq(MarketStock::getConsecutive, r.pool.board));
        if (industryClassify != null) {
            industryClassify.apply(poolRows);
        }
        for (MarketStock row : poolRows) {
            if (systemB && !r.sector.equals(row.getIndustry())) {
                continue;
            }
            r.candidates.add(row);
        }
        if (r.candidates.isEmpty()) {
            r.missing.add(emptyPoolMessage(r, systemB));
            return;
        }
        // 「候选日之后最高板数」只依赖库里已有的明细，跟 T+1 拉没拉没关系。
        // 必须在下面两个早退之前算：否则 T+1 空缺时，连当天明摆着的接位票都显示成"—"。
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

    /** 候选日：池子判得出来就跟着池子，判不出来（缺数或没放量）退到 D0，只影响查询窗口不影响结论。 */
    private static LocalDate candidateDate(Readings r) {
        return r.pool != null && r.pool.date != null ? r.pool.date : r.d0;
    }

    /** 候选票从<b>候选日</b>往后逐日的最高连板：本地一次查询，不打任何上游。 */
    private void fillMaxBoards(Readings r) {
        List<String> codes = new ArrayList<>();
        for (MarketStock row : r.candidates) {
            codes.add(row.getCode());
        }
        List<MarketStock> since = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .ge(MarketStock::getTradeDate, candidateDate(r))
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

    /**
     * 高低切的分流窗口：<b>候选日</b>之后 max(H−1, 1) 个交易日的逐日涨停明细。
     * 助攻是从接位票立住那天开始扩散的，所以窗口跟着候选日走，不跟着断板日走。
     *
     * <p>窗口长度用的是老龙断板前的板高 H，与 {@link TiantiService} 把破壁线钉住
     * {@code max(chaosH - 1, 1)} 个交易日同一个参数——一轮老龙腾出来的位置，市场认它多久，
     * 「等它几天分流」和「这道线钉几天」不该是两把尺子。
     *
     * <p>行业必须跟老龙那边一样过一遍 {@code industryClassify.apply()}：两边口径不同源时
     * 「同属性还是异属性」会答错，而答错是无声的——补位会被判成转切。
     */
    private void readSupportWindow(Readings r, NodeEvent node) {
        Integer h = node.getAnchorMaxBoard();
        if (h == null || h <= 0) {
            // 只进 warnings 不进 missing：板高缺失是分不了「补位还是转切」，
            // 不该顺手把这条节点的状态采纳一起卡死——那是一天前还能采纳、改完反而不能了。
            return;
        }
        int days = Math.max(h - 1, 1);
        r.splitWindowDays = days;
        LocalDate from = candidateDate(r).plusDays(1);
        // 交易日没法直接按个数取，先按 3 倍自然日宽取够、再由调用方按天数截断
        List<LocalDate> dates = marketStockMapper.listDetailDatesBetween(
                from, from.plusDays(days * 3L + 15));
        r.splitWindowLanded = Math.min(dates.size(), days);
        if (dates.isEmpty()) {
            return;
        }
        List<LocalDate> window = dates.subList(0, r.splitWindowLanded);
        List<MarketStock> rows = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .in(MarketStock::getTradeDate, window)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP));
        if (industryClassify != null) {
            industryClassify.apply(rows);
        }
        r.supportRows = rows;
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
        // 候选池排在指纹之前：candidatePool 是采纳要落的第十格，晚一步就进不了比对
        if (r.pool == null) {
            vo.setCandidateDate(r.d0);
            vo.setCandidateBoard(candidateBoard(r, systemB));
            vo.setCandidatePool(poolLabel(r, systemB));
        } else {
            vo.setCandidateDate(r.pool.date);
            vo.setCandidateBoard(r.pool.board);
            vo.setCandidatePool(r.pool.label());
            vo.setHeavyBasis(r.pool.basis);
            if (r.pool.fallback) {
                vo.getWarnings().add("老龙放没放量判不了｜" + r.pool.basis);
            }
        }

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
        // 类型分流排在指纹之前：nodeType 是采纳要落的第九个值，晚一步就进不了指纹
        TypeSplit split = resolveSplit(r, takerOf(cands, leader));
        vo.setNodeType(split.type);
        vo.setNodeTypeLabel(NodeService.nodeTypeLabel(split.type));
        vo.getMissing().addAll(r.missing);
        if (!decided && vo.getMissing().isEmpty()) {
            // 取数侧每种判不了都留了一句具体的，这句是兜底：不变式"判不了 ⇒ 不 ready"不能靠约定撑
            vo.getMissing().add("T+1 的晋级与老龙反包至少有一项判不了，状态留在待验证");
        }
        vo.setReady(vo.getMissing().isEmpty());
        vo.setReason(reason(r, vo, systemB, count, total, rate, status));
        if (split.type != null) {
            vo.setReason(vo.getReason() + "；类型 " + vo.getNodeTypeLabel() + "：" + split.note);
        } else {
            // 判不了也要出声：一片「未识别」里分不清"还在等窗口"和"原料就没有"，等于让人猜
            vo.getWarnings().add("节点类型未识别｜" + split.note);
        }
        addWarnings(r, vo);
        vo.setFingerprint(fingerprint(json, vo));
        return vo;
    }

    // ---------- 破壁分支：试探破壁 / 破壁成功 ----------

    /**
     * 破壁行的取数：只问 {@link BreakDetailService} 要那一天那一份。
     * <b>一条判据都不在这儿重算</b>——续没续板是 {@code TiantiService.detectBreaks} 的结论，
     * 曲线上的 ★ 与面板已经共用它；这里再算一遍就是第二份判定，两份迟早打架。
     */
    private Readings fetchBreak(NodeEvent node, Long userId) {
        Readings r = new Readings();
        r.d0 = node.getD0Date();
        if (r.d0 == null) {
            r.missing.add("D0 日期未填：没有破壁那天，续没续板判不了");
            return r;
        }
        r.breakDetail = breakDetailService.vo(userId, r.d0);
        BreakDetailVO.Outcome o = r.breakDetail.getOutcome();
        r.t1 = o == null ? null : o.getNextDate();
        return r;
    }

    /**
     * 破壁两行的判定，一句话：<b>试探行看次日续没续板，成功行看这天还是不是 ★</b>。
     *
     * <p>三种"判不了"分开说：曲线上没有点＝那天没行情（缺数，不许采纳）；有点但事件变了＝
     * 前提真的不成立了（建议作废，由他点）；试探行的次日还没明细（缺数，不许采纳）。
     *
     * <p>助攻、盘口、情绪闸门一律只进 {@code reason} 与 {@code warnings}：他定的口径是
     * "只算、只展示"，没进 ready 闸门，也就不许在这里偷偷变成否决项。
     */
    static NodeSuggestVO decideBreak(NodeEvent node, Readings r, ObjectMapper json) {
        NodeSuggestVO vo = new NodeSuggestVO();
        vo.setNodeId(node.getId());
        // 破壁节点既不是系统A也不是系统B：这一格留空，界面按破壁那一套排面显示
        vo.setSystemType(null);
        vo.setNodeType(node.getNodeType());
        vo.setNodeTypeLabel(NodeService.nodeTypeLabel(node.getNodeType()));
        vo.setT1Date(r.t1);
        vo.getMissing().addAll(r.missing);

        boolean probeRow = NodeBreakService.TYPE_PROBE.equals(node.getNodeType());
        String repair = probeRow ? NodeBreakService.REPAIR_PENDING : null;
        String status = STATUS_PENDING;
        String conclusion = null;
        BreakDetailVO d = r.breakDetail;
        if (d != null) {
            if (d.getSubject() != null) {
                vo.setNodeStock(NodeBreakService.displayOf(
                        d.getSubject().getName(), d.getSubject().getCode()));
                vo.setNodeStockMaxBoard(d.getSubject().getBoard());
            }
            boolean matches = probeRow
                    ? BreakDetailVO.EVENT_PROBE.equals(d.getEvent())
                    : BreakDetailVO.EVENT_BREAK.equals(d.getEvent());
            if (!matches) {
                if (Boolean.FALSE.equals(d.getCurvePoint())) {
                    vo.getMissing().add(r.d0 + " 这天连板高度曲线上没有点："
                            + trim(d.getDetailMissingReason()));
                } else {
                    status = STATUS_INVALID;
                    conclusion = "破壁事件已不成立";
                    vo.getWarnings().add("曲线上 " + r.d0 + " 这天"
                            + (probeRow ? "已经没有 ☆（试探破壁）" : "已经没有 ★（破壁成功）")
                            + "：这一行的前提在现在的数据里不成立。采纳就是把它作废；"
                            + "先去连板生态页看清那条线再决定");
                }
            } else if (probeRow) {
                BreakDetailVO.Outcome o = d.getOutcome();
                if (o == null || BreakDetailVO.OUTCOME_PENDING.equals(o.getResult())) {
                    vo.getMissing().add(o == null
                            ? "次一交易日的盘面明细还没落库，续没续板判不了" : o.getReason());
                } else {
                    repair = o.getResult();
                    boolean ok = BreakDetailVO.OUTCOME_SUCCESS.equals(repair);
                    status = ok ? STATUS_VALID : STATUS_INVALID;
                    conclusion = ok ? "续板成功" : "未续板失效";
                }
            } else {
                status = STATUS_VALID;
                conclusion = "破壁成功";
            }
        }
        vo.setSuggestedStatus(status);
        vo.setStatus(status);
        vo.setNodeValid(STATUS_VALID.equals(status) ? 1 : 0);
        vo.setRepairStatus(repair);
        vo.setRepairStatusLabel(repairLabel(repair));
        vo.setConclusionReason(conclusion);
        addBreakReadings(d, vo);
        vo.setReady(vo.getMissing().isEmpty());
        vo.setReason(breakReason(r, d, vo, status));
        vo.setFingerprint(fingerprint(json, vo));
        return vo;
    }

    /** 续板判定的中文标签：进指纹的是这一份，diff 出来那句"待判定 → 未续板"他才看得懂。 */
    static String repairLabel(String repair) {
        if (repair == null) {
            return null;
        }
        if (NodeBreakService.REPAIR_PENDING.equals(repair)) {
            return "待判定";
        }
        if (BreakDetailVO.OUTCOME_SUCCESS.equals(repair)) {
            return "续板成功";
        }
        return BreakDetailVO.OUTCOME_FAILED.equals(repair) ? "未续板" : repair;
    }

    /** 助攻／盘口／闸门三条只出声不否决：一条都不进 missing，ready 只由"续没续板判没判得出来"决定。 */
    private static void addBreakReadings(BreakDetailVO d, NodeSuggestVO vo) {
        if (d == null) {
            return;
        }
        // 「没有点」和「有点但只有名义天梯」是两种不同的空，两处都得把原因说出来：
        // 助攻那几格是 null 的时候，界面上留下的空白会被读成"0 只助攻"。
        if ((Boolean.FALSE.equals(d.getCurvePoint()) || Boolean.FALSE.equals(d.getDetailAvailable()))
                && notBlank(d.getDetailMissingReason())) {
            vo.getWarnings().add(d.getDetailMissingReason());
        }
        BreakDetailVO.Assist a = d.getAssist();
        if (a != null) {
            vo.getWarnings().add("同属性助攻 首" + dash(a.getSameIndustryFirst())
                    + "／二" + dash(a.getSameIndustrySecond())
                    + "／3+ " + dash(a.getSameIndustryThirdPlus())
                    + "，合计 " + dash(a.getTotal()) + " 只（梯队线 " + BreakDetailService.MIN_LADDER_ASSIST
                    + " 只）——只展示，不进闸门、不改权重");
        }
        BreakDetailVO.BoardInfo b = d.getBoard();
        if (b != null) {
            vo.getWarnings().add("盘口 形态 " + dash(b.getPattern()) + "、炸板 " + dash(b.getBreakCount())
                    + " 次、换手率 " + dash(b.getTurnoverRate()) + "%、封单/流通 " + dash(b.getSealRatio())
                    + "%" + (Boolean.TRUE.equals(b.getOneWordKilling()) ? "（一字缩量断魂刀）" : "")
                    + "——烂板与缩量他没有数值口径，这里只列数，收不收手你定");
        }
        if (d.getGateWarnings() != null) {
            for (String each : d.getGateWarnings()) {
                vo.getWarnings().add("情绪闸门｜" + each);
            }
        }
    }

    private static String breakReason(Readings r, BreakDetailVO d, NodeSuggestVO vo, String status) {
        if (d == null || !vo.getMissing().isEmpty()) {
            return "判据不齐：" + join(vo.getMissing(), "；");
        }
        StringBuilder text = new StringBuilder("破壁｜");
        text.append(NodeService.nodeTypeLabel(vo.getNodeType())).append(" ").append(r.d0);
        if (notBlank(vo.getNodeStock())) {
            text.append(" ").append(vo.getNodeStock());
            if (vo.getNodeStockMaxBoard() != null) {
                text.append(" ").append(vo.getNodeStockMaxBoard()).append(" 板");
            }
        }
        if (d.getCeiling() != null) {
            text.append("，追 ").append(d.getCeiling()).append(" 板破壁线");
            if (d.getLineOriginDate() != null) {
                text.append("（这条线来自 ").append(d.getLineOriginDate());
                if (d.getLineOriginStock() != null) {
                    text.append(" ").append(d.getLineOriginStock().getName());
                }
                text.append("）");
            }
        }
        if (notBlank(vo.getRepairStatusLabel())) {
            text.append("；续板判定 ").append(vo.getRepairStatusLabel());
        }
        if (d.getOutcome() != null && notBlank(d.getOutcome().getReason())) {
            text.append("：").append(d.getOutcome().getReason());
        }
        return text.append(" → ").append(status).toString();
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

    /** 板高降序、并列取代码小：纯为让"哪一只是它"这个结论可复现。 */
    private static final Comparator<NodeSuggestVO.Candidate> BY_BOARD_THEN_CODE =
            new Comparator<NodeSuggestVO.Candidate>() {
                @Override
                public int compare(NodeSuggestVO.Candidate a, NodeSuggestVO.Candidate b) {
                    int byBoard = b.getMaxBoard().compareTo(a.getMaxBoard());
                    return byBoard != 0 ? byBoard : a.getCode().compareTo(b.getCode());
                }
            };

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
        Collections.sort(promoted, BY_BOARD_THEN_CODE);
        return promoted.get(0);
    }

    /** 高低切分流的结果。{@link #type} 为 null 就是判不了，保持「未识别」，不兜任何反义定义。 */
    static class TypeSplit {
        String type;
        boolean sameSeat;
        int support;
        /** 怎么算的一句话，进 warnings：判不了和还在等是两种完全不同的空，得说得出差别。 */
        String note;
    }

    /**
     * 接位票：优先用挑得出的节点票；T+1 还没走完、一只都没晋级时退回 D0 候选里板高最大的那只。
     *
     * <p>接位这件事发生在断板当天，等 T+1 才有对象可比就等于窗口前几天什么都判不了。
     * 两种取法用的是同一个比较器，所以 T+1 落地之后接位票不会悄悄换人。
     */
    private static NodeSuggestVO.Candidate takerOf(List<NodeSuggestVO.Candidate> cands,
                                                  NodeSuggestVO.Candidate leader) {
        if (leader != null) {
            return leader;
        }
        List<NodeSuggestVO.Candidate> pool = new ArrayList<>();
        for (NodeSuggestVO.Candidate each : cands) {
            if (each.getMaxBoard() != null) {
                pool.add(each);
            }
        }
        if (pool.isEmpty()) {
            return null;
        }
        Collections.sort(pool, BY_BOARD_THEN_CODE);
        return pool.get(0);
    }

    /**
     * 高低切分流：补位与转切<b>只差一个题材同不同属性</b>，判据其余部分完全相同。
     *
     * <p>同属性＝借老龙的题材余温（国芳→百大），异属性＝借的是老龙「死」这件事腾出来的势
     * （金健→深中华，涨的是黄金不是粮食）。两类都要窗口内 ≥{@link #MIN_SUPPORT} 只同/异属性助攻
     * 才算方向立住；立不住就还挂接位——断板当天只有"有票接住了"这一个事实，扩散与跟进都还没发生。
     *
     * <p>助攻数的是窗口内去重后的只数，老龙自己与接位票自己都不算助攻。
     */
    static TypeSplit resolveSplit(Readings r, NodeSuggestVO.Candidate taker) {
        TypeSplit out = new TypeSplit();
        if (taker == null) {
            out.note = "D0 候选里挑不出接位票，补位还是转切无从谈起";
            return out;
        }
        if (r.splitWindowDays == null) {
            out.note = "老龙断板前的板高没登记，分流窗口有多长定不了，接位分不了流";
            return out;
        }
        if (!notBlank(r.sector) || !notBlank(taker.getIndustry())) {
            out.note = "老龙的行业或接位票 " + taker.getName() + " 的行业在明细里是空的，"
                    + "同属性/异属性判不了";
            return out;
        }
        out.sameSeat = r.sector.equals(taker.getIndustry());
        String target = out.sameSeat ? r.sector : taker.getIndustry();
        Set<String> helpers = new HashSet<>();
        for (MarketStock row : r.supportRows) {
            if (target.equals(row.getIndustry())
                    && !row.getCode().equals(taker.getCode())
                    && !row.getCode().equals(r.anchorCode)) {
                helpers.add(row.getCode());
            }
        }
        out.support = helpers.size();
        if (out.support >= MIN_SUPPORT) {
            out.type = out.sameSeat ? TYPE_FILL_SAME : TYPE_SWITCH_CROSS;
            out.note = "接位票 " + taker.getName() + "(" + taker.getCode() + ") 行业「" + taker.getIndustry()
                    + "」与老龙「" + r.sector + "」" + (out.sameSeat ? "同属性" : "异属性")
                    + "；窗口内 " + target + " 助攻 " + out.support + " 只 ≥" + MIN_SUPPORT
                    + " → " + NodeService.nodeTypeLabel(out.type);
            return out;
        }
        out.type = TYPE_SPLIT_PENDING;
        if (r.splitWindowLanded >= r.splitWindowDays) {
            out.note = "分流窗口 " + r.splitWindowDays + " 个交易日已走完，" + target + " 助攻只有 "
                    + out.support + " 只（判据 ≥" + MIN_SUPPORT + "）——按状态机这该降孤板，"
                    + "作废判定本轮未实现，先留在接位";
        } else {
            out.note = "分流窗口才走 " + r.splitWindowLanded + "/" + r.splitWindowDays + " 个交易日，"
                    + target + " 助攻 " + out.support + " 只、还差 " + (MIN_SUPPORT - out.support)
                    + " 只，方向未定 → 接位";
        }
        return out;
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
        return candidateDate(r) + " 涨停池里" + (systemB ? "没有「" + r.sector + "」板块的首板"
                : "没有" + boardName(candidateBoard(r, systemB)))
                + "：候选池是空的，晋级率无从算起";
    }

    private static String basis(Readings r, boolean systemB, int total) {
        String pool = systemB
                ? candidateDate(r) + " 涨停池「" + r.sector + "」板块的" + boardName(candidateBoard(r, systemB))
                : candidateDate(r) + " 涨停池的" + boardName(candidateBoard(r, systemB));
        return "分母＝" + pool + "复算，共 " + total + " 只；"
                + (r.t1 == null ? "T+1 未定" : "分子＝这些代码在 " + r.t1 + " 仍涨停且连板数更高的只数");
    }

    /** 候选板数：池子判得出来跟着池子；判不出来退原口径——系统B 首板、系统A 二连板。 */
    private static int candidateBoard(Readings r, boolean systemB) {
        if (r.pool != null && r.pool.board != null) {
            return r.pool.board;
        }
        return systemB ? 1 : A_CANDIDATE_BOARD;
    }

    private static String boardName(int board) {
        return board == 1 ? "首板" : (board == 2 ? "二连板" : board + " 板");
    }

    /** 候选池的一句标签：{@code 2026-09-23 首板}。reason、VO 与采纳指纹三处共用这一个写法。 */
    private static String poolLabel(Readings r, boolean systemB) {
        return candidateDate(r) + " " + boardName(candidateBoard(r, systemB));
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
        text.append("；").append(systemB ? "板块一进二 " : poolLabel(r, false) + " ").append(total)
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
        Map<String, String> all = fpValues(vo);
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : fpKeys(vo)) {
            values.put(key, all.get(key));
        }
        try {
            return json.writeValueAsString(values);
        } catch (Exception e) {
            // 一串字符串值不可能序列化失败；真出了事也不能让建议接口整个红掉
            return "";
        }
    }

    /** 两条路上可能进指纹的格子一次备齐；键表只决定这次取哪几个，值口径两边共用一份。 */
    private static Map<String, String> fpValues(NodeSuggestVO vo) {
        Map<String, String> values = new LinkedHashMap<>();
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
        // 存中文标签不是类型码：与 status 存"有效/失效"同一个道理，比对不一致时那句 diff 他才看得懂
        values.put("nodeType", text(vo.getNodeTypeLabel()));
        values.put("candidatePool", text(vo.getCandidatePool()));
        values.put("repairStatus", text(vo.getRepairStatusLabel()));
        return values;
    }

    /**
     * 他看到的那几格 vs 现在算出来的那几格，不一样的逐条报出来。取哪几格由 {@link #fpKeys} 按分支定：
     * 高低切十格、破壁两行六格。
     *
     * <p>只说"不一致"等于让他猜哪一格变了——这里报的是"晋级数量：5 → 现在算出 6"这种能当场看懂的话。
     */
    static List<String> diff(ObjectMapper json, String seen, NodeSuggestVO fresh) {
        JsonNode was;
        try {
            was = json.readTree(seen == null || seen.trim().isEmpty() ? "{}" : seen);
        } catch (Exception e) {
            throw new IllegalArgumentException("采纳请求里的指纹解析不了，请刷新建议后重试");
        }
        Map<String, String> now = fpValues(fresh);
        List<String> diffs = new ArrayList<>();
        for (String key : fpKeys(fresh)) {
            String before = was.path(key).asText("");
            String after = text(now.get(key));
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

    /** 没数就写"无"：助攻与盘口这几格 null 是"不知道"，兜成 0 等于把不知道讲成一个读数。 */
    private static String dash(Object value) {
        return value == null ? "无" : String.valueOf(value);
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
