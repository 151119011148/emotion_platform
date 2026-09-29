package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.Anchor;
import com.emotion.entity.CandidateStock;
import com.emotion.entity.DailyRecord;
import com.emotion.entity.MarketStock;
import com.emotion.entity.NodeEvent;
import com.emotion.entity.Surveillance;
import com.emotion.mapper.AnchorMapper;
import com.emotion.mapper.DailyRecordMapper;
import com.emotion.mapper.MarketDailyMapper;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.NodeEventMapper;
import com.emotion.mapper.SurveillanceMapper;
import com.emotion.vo.NodePrefillVO;
import com.emotion.vo.NodeSuggestVO;
import com.emotion.vo.NodeTagVO;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 节点事件：增删改查 + 读侧富化（阵眼血缘、节点票存活、监管标记、T+1 自动判定）。 */
@Service
public class NodeService {

    private static final String STATUS_PENDING = "待验证";
    private static final int STATUS_NOTE_MAX = 295;

    private final NodeEventMapper nodeEventMapper;
    private final AnchorMapper anchorMapper;
    private final DailyRecordMapper dailyRecordMapper;
    private final MarketStockMapper marketStockMapper;
    private final MarketDailyMapper marketDailyMapper;
    private final SurveillanceMapper surveillanceMapper;
    private final NodeSuggestService nodeSuggestService;

    public NodeService(NodeEventMapper nodeEventMapper,
                       AnchorMapper anchorMapper,
                       DailyRecordMapper dailyRecordMapper,
                       MarketStockMapper marketStockMapper,
                       MarketDailyMapper marketDailyMapper,
                       SurveillanceMapper surveillanceMapper,
                       NodeSuggestService nodeSuggestService) {
        this.nodeEventMapper = nodeEventMapper;
        this.anchorMapper = anchorMapper;
        this.dailyRecordMapper = dailyRecordMapper;
        this.marketStockMapper = marketStockMapper;
        this.marketDailyMapper = marketDailyMapper;
        this.surveillanceMapper = surveillanceMapper;
        this.nodeSuggestService = nodeSuggestService;
    }

    /**
     * 历史节点：按 D0 倒排（同 D0 再按创建时间倒排）。富化到"删一眼就知道这行还活不活"的粒度即可，
     * 不复算 suggestion（那要打 N 次 SQL，历史行没这个必要）。
     */
    public List<com.emotion.vo.NodeVO> listByUser(Long userId) {
        List<NodeEvent> rows = nodeEventMapper.selectList(
                new LambdaQueryWrapper<NodeEvent>()
                        .eq(NodeEvent::getUserId, userId)
                        .orderByDesc(NodeEvent::getD0Date)
                        .orderByDesc(NodeEvent::getCreatedAt));
        List<com.emotion.vo.NodeVO> out = new ArrayList<>();
        for (NodeEvent row : rows) {
            out.add(enrichLite(row, userId));
        }
        return out;
    }

    /** 待验证的那一个（按 D0 取最近）。富化完整并挂上 T+1 自动判定。 */
    public com.emotion.vo.NodeVO getCurrent(Long userId) {
        NodeEvent node = nodeEventMapper.selectOne(
                new LambdaQueryWrapper<NodeEvent>()
                        .eq(NodeEvent::getUserId, userId)
                        .eq(NodeEvent::getStatus, STATUS_PENDING)
                        .orderByDesc(NodeEvent::getD0Date)
                        .orderByDesc(NodeEvent::getCreatedAt)
                        .last("LIMIT 1"));
        if (node == null) {
            return null;
        }
        com.emotion.vo.NodeVO vo = enrichLite(node, userId);
        NodeSuggestVO suggestion = nodeSuggestService.suggest(userId, node.getId());
        vo.setSuggestion(suggestion);
        // T+1 自动判定：平台把结论算好写进 status_note，但状态仍留"待验证"，等你采纳才落库。
        if (suggestion.isReady()) {
            if (!STATUS_PENDING.equals(suggestion.getSuggestedStatus())) {
                node.setStatusNote(cut("平台自动判定：@" + nowText() + " " + suggestion.getReason()
                        + "（待你采纳）", STATUS_NOTE_MAX));
            }
            node.setLastRecalcAt(LocalDateTime.now());
            nodeEventMapper.updateById(node);
            vo.setStatusNote(node.getStatusNote());
            vo.setLastRecalcAt(node.getLastRecalcAt());
        }
        return vo;
    }

    /**
     * 新建节点：关联阵眼时按 t_anchor 回填龙头（名称反查，不信前端手填）、自动补 D0 情绪分/周期。
     * 新节点一律"待验证"。
     */
    public com.emotion.vo.NodeVO create(Long userId, NodeEvent event) {
        checkNodeType(event.getNodeType());
        event.setUserId(userId);
        if (event.getStatus() == null || event.getStatus().trim().isEmpty()) {
            event.setStatus(STATUS_PENDING);
        }
        resolveAnchor(event, userId);
        fillD0Emotion(event, userId);
        event.setLastRecalcAt(null);
        nodeEventMapper.insert(event);
        return enrichLite(event, userId);
    }

    public NodeEvent update(Long userId, Long id, NodeEvent event) {
        NodeEvent existing = nodeEventMapper.selectOne(
                new LambdaQueryWrapper<NodeEvent>()
                        .eq(NodeEvent::getId, id)
                        .eq(NodeEvent::getUserId, userId));
        if (existing == null) throw new RuntimeException("节点事件不存在");

        if (event.getT1Date() != null) existing.setT1Date(event.getT1Date());
        if (event.getT1AnchorRepack() != null) existing.setT1AnchorRepack(event.getT1AnchorRepack());
        if (event.getT1PromotionCount() != null) existing.setT1PromotionCount(event.getT1PromotionCount());
        if (event.getT1PromotionRate() != null) existing.setT1PromotionRate(event.getT1PromotionRate());
        if (event.getNodeValid() != null) existing.setNodeValid(event.getNodeValid());
        if (event.getNodeStock() != null) existing.setNodeStock(event.getNodeStock());
        if (event.getNodeStockMaxBoard() != null) existing.setNodeStockMaxBoard(event.getNodeStockMaxBoard());
        if (event.getStatus() != null) existing.setStatus(event.getStatus());
        if (event.getNote() != null) existing.setNote(event.getNote());
        if (event.getD0Candidates() != null) existing.setD0Candidates(event.getD0Candidates());
        if (event.getTheme() != null) existing.setTheme(event.getTheme());
        if (event.getNodeType() != null) {
            checkNodeType(event.getNodeType());
            existing.setNodeType(event.getNodeType());
        }
        if (event.getBreakStockCode() != null) existing.setBreakStockCode(event.getBreakStockCode());
        if (event.getBreakBoard() != null) existing.setBreakBoard(event.getBreakBoard());
        if (event.getBreakForm() != null) existing.setBreakForm(event.getBreakForm());
        if (event.getRepairStatus() != null) existing.setRepairStatus(event.getRepairStatus());
        if (event.getLastRecalcAt() != null) existing.setLastRecalcAt(event.getLastRecalcAt());

        nodeEventMapper.updateById(existing);
        return existing;
    }

    /** 单个节点的富化视图；不存在或非本人则抛错。 */
    public com.emotion.vo.NodeVO detail(Long userId, Long id) {
        NodeEvent node = nodeEventMapper.selectOne(new LambdaQueryWrapper<NodeEvent>()
                .eq(NodeEvent::getId, id)
                .eq(NodeEvent::getUserId, userId));
        if (node == null) {
            throw new IllegalArgumentException("节点事件不存在：" + id);
        }
        return enrichLite(node, userId);
    }

    /**
     * 类型轴是闭集：认不出来的值不收。留空（null 或空串）是合法状态——策略没识别出来的节点
     * 就该是 NULL，界面显示「未识别」；但拼错的、前端乱发的必须挡下来，
     * 否则它会被 {@code nodePart} 按 0 分算，一个字母的错就是一次无声的降权。
     */
    static void checkNodeType(String nodeType) {
        if (nodeType == null || nodeType.trim().isEmpty()) {
            return;
        }
        if (nodeTypeLabel(nodeType) == null) {
            throw new IllegalArgumentException("未知的节点类型：" + nodeType
                    + "，只能是 START/DIVERGE/SWITCH/SPLIT_PENDING/FILL_SAME/SWITCH_CROSS/"
                    + "SPACE_BREAK/SPACE_BREAK_NEXT 之一，或留空表示未识别");
        }
    }

    /** 删除自己的一个节点事件；不存在/非本人则抛错。 */
    public void deleteNode(Long userId, Long id) {
        int n = nodeEventMapper.delete(new LambdaQueryWrapper<NodeEvent>()
                .eq(NodeEvent::getId, id)
                .eq(NodeEvent::getUserId, userId));
        if (n == 0) throw new RuntimeException("节点事件不存在或无权操作");
    }

    // ---------- 读侧富化 ----------

    /** 唯一的富化入口：拷贝 + 阵眼血缘 + 节点票存活 + 监管标记。 */
    private com.emotion.vo.NodeVO enrichLite(NodeEvent node, Long userId) {
        com.emotion.vo.NodeVO vo = new com.emotion.vo.NodeVO();
        BeanUtils.copyProperties(node, vo);
        fillAnchor(vo, userId);
        fillNodeStockLive(vo);
        fillSurveillance(vo);
        fillCauseTags(vo);
        vo.setNodeTypeLabel(nodeTypeLabel(node.getNodeType()));
        return vo;
    }

    /** 失效节点的细分标签：主因(反包/晋级清零) + 叠加监管 + 叠加情绪退潮。读时拼、显示用、不入库。 */
    private void fillCauseTags(com.emotion.vo.NodeVO vo) {
        if (!"失效".equals(vo.getStatus())) {
            return;
        }
        List<String> tags = new ArrayList<>();
        if (vo.getConclusionReason() != null) {
            tags.add(vo.getConclusionReason());
        }
        if (Boolean.TRUE.equals(vo.getSurveillance())) {
            tags.add("叠加监管");
        }
        if (vo.getD0Cycle() != null && vo.getD0Cycle().startsWith("退潮")) {
            tags.add("情绪退潮");
        }
        vo.setCauseTags(tags);
    }

    /**
     * 节点类型 → 中文展示名（类型轴的闭集）。
     *
     * <p>高低切一族（接位/补位/转切）看的是老龙断板之后资金去了哪儿：接位是方向未定的临时态；
     * 补位与转切判据完全相同，只按接位票与老龙的题材同不同属性分流——同属性借的是老龙的题材余温，
     * 异属性借的是老龙「死」这件事腾出来的势。周期一族（启动/分歧/切换）与空间一族（破局）各走各的。
     *
     * <p>查不到一律返回 null，由前端显示「未识别」：<strong>绝不回落成「普通节点」</strong>——
     * 反义定义只说明它不是什么，而且每加一个 node_type 这个词的所指就要变一次（PRD §2 命名约定）。
     */
    static String nodeTypeLabel(String nodeType) {
        if (nodeType == null) {
            return null;
        }
        switch (nodeType) {
            case "SPLIT_PENDING":
                return "接位 · 待定";
            case "FILL_SAME":
                return "补位节点";
            case "SWITCH_CROSS":
                return "转切节点";
            case "SPACE_BREAK":
                return "试探破壁 · 观察";
            case "SPACE_BREAK_NEXT":
                return "破壁成功 · 出手";
            case "START":
                return "启动日";
            case "SWITCH":
                return "切换日";
            case "DIVERGE":
                return "分歧日";
            default:
                return null;
        }
    }

    /** 阵眼血缘：由 anchor_id 取 t_anchor 回填名称、角色码/标签、跨度。 */
    private void fillAnchor(com.emotion.vo.NodeVO vo, Long userId) {
        if (vo.getAnchorId() == null) {
            return;
        }
        Anchor anchor = anchorMapper.selectOne(new LambdaQueryWrapper<Anchor>()
                .eq(Anchor::getId, vo.getAnchorId())
                .eq(Anchor::getUserId, userId));
        if (anchor == null) {
            return;
        }
        vo.setAnchorCode(anchor.getStockCode());
        vo.setAnchorName(anchor.getStockName());
        vo.setAnchorRole(anchor.getRole());
        vo.setAnchorRoleLabel(AnchorService.roleLabel(anchor.getRole()));
        vo.setAnchorStartDate(anchor.getStartDate());
        vo.setAnchorEndDate(anchor.getEndDate());
    }

    /**
     * 节点票今天还活不活：取该代码最近一次盘面明细（早于今天，含今天）。在涨停池且有连板数 →
     * "在梯·N板"；否则"断板·不在梯"。没确认过节点票就如实说。
     */
    private void fillNodeStockLive(com.emotion.vo.NodeVO vo) {
        String code = stockCodeOf(vo.getNodeStock());
        if (code == null) {
            vo.setNodeStockBoard(null);
            vo.setNodeStockStatus("未确认节点票");
            return;
        }
        MarketStock last = marketStockMapper.selectOne(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getCode, code)
                .le(MarketStock::getTradeDate, LocalDate.now())
                .orderByDesc(MarketStock::getTradeDate)
                .last("LIMIT 1"));
        if (last == null) {
            vo.setNodeStockBoard(null);
            vo.setNodeStockStatus("无明细");
            return;
        }
        boolean inLadder = MarketStock.POOL_LIMIT_UP.equals(last.getPool()) && last.getConsecutive() != null;
        vo.setNodeStockBoard(inLadder ? last.getConsecutive() : 0);
        vo.setNodeStockStatus(inLadder
                ? "在梯·" + last.getConsecutive() + "板（" + last.getTradeDate() + "）"
                : "断板·不在梯（" + last.getTradeDate() + "）");
    }

    /**
     * 监管小红标：只标 SEVERE/EXCH（ZD 是例行公告，二维两码没压力）。窗口按公告日 + 交易日数×2 自然日近似，
     * 节点页是一线告警，不必为精确买卖日数去逐只打日 K——真到那天高分位生态页会细算。
     */
    private void fillSurveillance(com.emotion.vo.NodeVO vo) {
        List<String> codes = new ArrayList<>();
        String nodeCode = stockCodeOf(vo.getNodeStock());
        if (nodeCode != null) {
            codes.add(nodeCode);
        }
        if (vo.getAnchorCode() != null) {
            codes.add(vo.getAnchorCode());
        }
        if (codes.isEmpty()) {
            vo.setSurveillance(false);
            return;
        }
        List<Surveillance> rows = surveillanceMapper.selectList(new LambdaQueryWrapper<Surveillance>()
                .in(Surveillance::getStockCode, codes));
        LocalDate today = LocalDate.now();
        Surveillance worst = null;
        for (Surveillance s : rows) {
            if ("ZD".equals(s.getKind())) {
                continue;
            }
            int window = "SEVERE".equals(s.getKind()) ? 20 : 20; // SEVERE/EXCH 都是 10 交易日，×2≈自然日
            if (!today.isBefore(s.getAnnDate()) && !today.isAfter(s.getAnnDate().plusDays(window))) {
                if (worst == null || s.getAnnDate().isAfter(worst.getAnnDate())) {
                    worst = s;
                }
            }
        }
        vo.setSurveillance(worst != null);
        if (worst != null) {
            String who = worst.getStockCode().equals(vo.getAnchorCode()) ? "锚定龙头" : "节点票";
            vo.setSurveillanceDesc(who + "被"
                    + ("EXCH".equals(worst.getKind()) ? "交易所监管" : "严重异常波动监管")
                    + "，公告日 " + worst.getAnnDate() + "（SEVERE/EXCH 可能提前失效）");
        }
    }

    // ---------- 写侧辅助 ----------

    /** 关联阵眼：只认同账号的 t_anchor，名称一律反查回填（和 t_stock 一致才能被复算的老龙明细匹配上）。 */
    private void resolveAnchor(NodeEvent event, Long userId) {
        if (event.getAnchorId() == null) {
            return;
        }
        Anchor anchor = anchorMapper.selectOne(new LambdaQueryWrapper<Anchor>()
                .eq(Anchor::getId, event.getAnchorId())
                .eq(Anchor::getUserId, userId));
        if (anchor == null) {
            throw new IllegalArgumentException("关联的阵眼不存在或不属于你：" + event.getAnchorId());
        }
        event.setAnchorStock(anchor.getStockName());
        if (event.getAnchorMaxBoard() == null) {
            event.setAnchorMaxBoard(0);
        }
    }

    /** D0 当日情绪分/周期：从用户那天的日记录取 five_dim 总分与阶段；没复盘就留空（节点照建）。 */
    private void fillD0Emotion(NodeEvent event, Long userId) {
        if (event.getD0Date() == null) {
            return;
        }
        DailyRecord rec = dailyRecordMapper.selectOne(new LambdaQueryWrapper<DailyRecord>()
                .eq(DailyRecord::getUserId, userId)
                .eq(DailyRecord::getTradeDate, event.getD0Date())
                .last("LIMIT 1"));
        if (rec == null) {
            event.setD0Score(null);
            event.setD0Cycle(null);
            return;
        }
        if (rec.getTotalScore() != null) {
            event.setD0Score(new BigDecimal(rec.getTotalScore()));
        } else {
            event.setD0Score(rec.getTemperature());
        }
        event.setD0Cycle(rec.getStage());
    }

    // ---------- 候选池打标 ----------

    /**
     * 给候选行打「来自节点追踪」的标，写进 {@link CandidateStock#setNodeTags}（瞬态，不落库）。
     *
     * <p>三种角色：节点票（{@code node_stock}，带代码）、锚定龙头（{@code anchor_stock}，只有名称）、
     * D0 候选。前两种是身份标，不限日期：一只票是不是某节点的节点票/锚定龙头，跟它哪天涨停无关。
     *
     * <p><b>D0 候选按明细复算「D0 当天全部二板」，不吃 {@code d0_candidates} 字段。</b>那份名单是
     * 他盘中的判断，存量还是老版本的「当日全部 ≥2 板」；而 §三 定的 D0 候选池是二板，判据侧
     * {@code NodeSuggestService.readCandidates} 一直是按明细复算的。两边共用一条筛法，否则就是
     * 屏幕上 12 只带标、判定却按 3 只算的那种双口径。手打名单原样留在事件里，节点追踪页照旧显示，
     * 只是不再拿来给候选打标。
     *
     * <p>日期闸门不变：复算出来的池子仍只描述 D0 → 次一交易日这一跳，所以只在候选行的
     * 上一个有明细的交易日 {@code == } 事件的 {@code d0_date} 时才挂；{@code d0_date} 为空的事件
     * 没有 D0 锚点。{@code prevDetailDate} 用的是明细而不是日历，池子本来就从这份明细里拉——
     * 明细缺的那天既当不上 D0、也不出标，那是数据缺口的真实反映，不是匹配写错。
     *
     * <p>系统B 的池子是「板块内 D0 首板」，要按老龙行业再切一刀；B 已下线（生产库 0 条），
     * 这里就不为它复算，避免把 A 的二板池错挂到 B 事件上。
     *
     * <p>复算之后按<b>代码</b>撞，不再按名称去空白：那一步是为了对上只有名称的手打名单才存在的，
     * 现在两边都是明细行，代码是唯一的。
     */
    public void tagCandidates(List<CandidateStock> rows, Long userId) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<NodeEvent> events = nodeEventMapper.selectList(
                new LambdaQueryWrapper<NodeEvent>().eq(NodeEvent::getUserId, userId));
        if (events.isEmpty()) {
            return;
        }
        // 先按「代码 / 去空白名称」把事件建成索引，再拿候选去撞，免得每行都重解析一遍事件
        Map<String, List<NodeEvent>> byNodeCode = new HashMap<String, List<NodeEvent>>();
        Map<String, List<NodeEvent>> byAnchor = new HashMap<String, List<NodeEvent>>();
        // D0 事件按日子分桶；池子是整日复算一次，不按事件重复查库
        Map<LocalDate, List<NodeEvent>> byD0 = new HashMap<LocalDate, List<NodeEvent>>();
        for (NodeEvent e : events) {
            String code = stockCodeOf(e.getNodeStock());
            if (code != null) {
                index(byNodeCode, code, e);
            }
            String anchor = norm(e.getAnchorStock());
            if (anchor != null) {
                index(byAnchor, anchor, e);
            }
            if (e.getD0Date() != null && !NodeSuggestService.SYSTEM_B.equals(e.getSystemType())) {
                index(byD0, e.getD0Date(), e);
            }
        }
        Map<LocalDate, Set<String>> pools = d0CandidatePools(byD0.keySet());
        Map<LocalDate, LocalDate> prevDates = new HashMap<LocalDate, LocalDate>();
        for (CandidateStock c : rows) {
            List<NodeTagVO> tags = new ArrayList<NodeTagVO>();
            collect(tags, byNodeCode.get(c.getCode()), NodeTagVO.KIND_NODE_STOCK, c);
            collect(tags, byAnchor.get(norm(c.getName())), NodeTagVO.KIND_ANCHOR, c);
            LocalDate d0 = prevDetailDate(c.getTradeDate(), prevDates);
            List<NodeEvent> eventsOfD0 = d0 == null ? null : byD0.get(d0);
            Set<String> pool = d0 == null ? null : pools.get(d0);
            if (eventsOfD0 != null && pool != null && pool.contains(c.getCode())) {
                collect(tags, eventsOfD0, NodeTagVO.KIND_D0_CAND, c);
            }
            c.setNodeTags(tags);
        }
    }

    /**
     * 按明细复算这些 D0 各自的候选池：当天涨停池里恰好二板的票。
     * 板数取 {@link NodeSuggestService#A_CANDIDATE_BOARD} 只是同一个数字，<b>不代表与接位同池</b>：
     * 复算的候选池现在跟着老龙的放量日走（可能是别的日子的首板），这一枚标只回答"D0 当天有哪些二板"。
     *
     * @return D0 → 当天二板的代码集合；那天没有二板就没有这个键
     */
    private Map<LocalDate, Set<String>> d0CandidatePools(Set<LocalDate> dates) {
        Map<LocalDate, Set<String>> out = new HashMap<LocalDate, Set<String>>();
        if (dates.isEmpty()) {
            return out;
        }
        List<MarketStock> pool = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .in(MarketStock::getTradeDate, dates)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP)
                .eq(MarketStock::getConsecutive, NodeSuggestService.A_CANDIDATE_BOARD));
        for (MarketStock row : pool) {
            if (row.getTradeDate() == null || row.getCode() == null) {
                continue;
            }
            Set<String> codes = out.get(row.getTradeDate());
            if (codes == null) {
                codes = new HashSet<String>();
                out.put(row.getTradeDate(), codes);
            }
            codes.add(row.getCode());
        }
        return out;
    }

    /** 候选行的上一个有明细的交易日；同一天只查一次，缺失也记账免得反复打库。 */
    private LocalDate prevDetailDate(LocalDate tradeDate, Map<LocalDate, LocalDate> cache) {
        if (tradeDate == null) {
            return null;
        }
        if (cache.containsKey(tradeDate)) {
            return cache.get(tradeDate);
        }
        LocalDate prev = marketStockMapper.prevDetailDate(tradeDate);
        cache.put(tradeDate, prev);
        return prev;
    }

    /**
     * 节点加分票：给策略引擎的 {@code score_weights.node} 那一项用的「这只候选是不是当下这个
     * 周期的节点票」。
     *
     * <p>认的是 {@link #tagCandidates} 那个「节点票」标<b>同一列</b>（{@code node_stock} 都经
     * {@link #stockCodeOf}），但加分比打标<b>严</b>：打标是身份、不限日期，这一项是钱、要卡周期。
     * 所以下面两道闸会让「界面上标着节点票、分数里没加成」成为合法结果——判据就写在策略选股页
     * 那个「得分怎么算」气泡里，别把它当成匹配写错。
     *
     * <p>两道闸：
     * <ol>
     *   <li><b>失效节点不算</b>。他 Skill §三 里失效＝那一轮不做，八月一条已失效的节点
     *       今天这只票再涨停也不该拿加成。</li>
     *   <li><b>D0 要落在最近 {@code window} 个有明细交易日内</b>（含候选日当天）。节点是周期里的
     *       一个位置，不是永久头衔。窗口取配置项 {@code node_scan_window}，「交易日」同样走
     *       {@code prevDetailDate} 而不是日历——与 D0 候选标一个口径，缺明细的那天本就不存在。</li>
     * </ol>
     *
     * <p>同一只票撞进多条节点时留 D0 最近的那条：加分看的是它现在挂在哪个节点下，
     * 多条叠乘会把 0.2 撑成 0.4。
     *
     * @return 节点票代码 → 那条节点事件；没有合格节点时是空表
     */
    public Map<String, NodeEvent> scoredNodeStocks(Long userId, LocalDate tradeDate, int window) {
        Map<String, NodeEvent> out = new HashMap<String, NodeEvent>();
        Set<LocalDate> dates = recentDetailDates(tradeDate, window);
        if (dates.isEmpty()) {
            return out;
        }
        List<NodeEvent> events = nodeEventMapper.selectList(
                new LambdaQueryWrapper<NodeEvent>().eq(NodeEvent::getUserId, userId));
        for (NodeEvent e : events) {
            if ("失效".equals(e.getStatus()) || e.getD0Date() == null || !dates.contains(e.getD0Date())) {
                continue;
            }
            String code = stockCodeOf(e.getNodeStock());
            if (code == null) {
                continue;
            }
            NodeEvent held = out.get(code);
            if (held == null || e.getD0Date().isAfter(held.getD0Date())) {
                out.put(code, e);
            }
        }
        return out;
    }

    /** 最近 n 个有明细的交易日（含 {@code from} 当天）；明细断在哪天就只数到那天。 */
    private Set<LocalDate> recentDetailDates(LocalDate from, int n) {
        Set<LocalDate> dates = new LinkedHashSet<LocalDate>();
        LocalDate cur = from;
        while (cur != null && dates.size() < Math.max(n, 0)) {
            dates.add(cur);
            cur = marketStockMapper.prevDetailDate(cur);
        }
        return dates;
    }

    private static <K> void index(Map<K, List<NodeEvent>> m, K key, NodeEvent e) {
        List<NodeEvent> list = m.get(key);
        if (list == null) {
            list = new ArrayList<NodeEvent>();
            m.put(key, list);
        }
        list.add(e);
    }

    /** 同一种角色命中多条事件时全部保留——界面按角色分组，来源列在悬浮里。 */
    private static void collect(List<NodeTagVO> out, List<NodeEvent> events, String kind,
                                CandidateStock c) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (NodeEvent e : events) {
            NodeTagVO t = new NodeTagVO();
            t.setKind(kind);
            t.setEventId(e.getId());
            t.setD0Date(e.getD0Date());
            t.setStatus(e.getStatus());
            t.setAnchorStock(e.getAnchorStock());
            t.setTheme(e.getTheme());
            t.setNodeStock(e.getNodeStock());
            t.setNodeStockMaxBoard(e.getNodeStockMaxBoard());
            out.add(t);
        }
    }

    /** 名称比对用：去掉全部空白，防「罗 牛 山」与「罗牛山」对不上。 */
    private static String norm(String s) {
        if (s == null) {
            return null;
        }
        String v = s.replaceAll("\\s+", "");
        return v.isEmpty() ? null : v;
    }

    // ---------- 杂项 ----------

    /** 节点票格式是「名称(代码)」→ 抽 6 位代码；解析不出就当没确认。 */
    /** 「名称(000012)」转 6 位代码；打分侧与破壁节点写库共用这一个格式，两边必须同一把解析。 */
    static String stockCodeOf(String nodeStock) {
        if (nodeStock == null) {
            return null;
        }
        int idx = nodeStock.lastIndexOf('(');
        if (idx < 0 || idx + 6 >= nodeStock.length()) {
            return null;
        }
        String code = nodeStock.substring(idx + 1, idx + 7);
        return code.matches("\\d{6}") ? code : null;
    }

    private static String nowText() {
        return LocalDateTime.now().toString().replace('T', ' ').substring(0, 16);
    }

    /**
     * 「从今日天梯新增节点」的轻量预填，只读本地表：D0 日期回落最近有明细的交易日，
     * 涨停/跌停家数与最高板取自 t_market_daily，龙头候选取当日涨停池 ≥2 板。
     */
    public NodePrefillVO ladderIntel(LocalDate requested) {
        LocalDate date = resolveDate(requested);
        NodePrefillVO vo = new NodePrefillVO();
        vo.setDate(date);
        if (date != null) {
            com.emotion.entity.MarketDaily day = marketDailyMapper.selectOne(new LambdaQueryWrapper<com.emotion.entity.MarketDaily>()
                    .eq(com.emotion.entity.MarketDaily::getTradeDate, date).last("LIMIT 1"));
            if (day != null) {
                vo.setMaxBoard(day.getMaxConsecutiveLimit());
                vo.setLimitUpCount(day.getLimitUpCount());
                vo.setLimitDownCount(day.getLimitDownCount());
            }
            collectLeaders(date, vo.getLeaders());
        }
        LocalDate prevDate = date == null ? null : resolveDate(date.minusDays(1));
        vo.setPrevDate(prevDate);
        if (prevDate != null) {
            collectLeaders(prevDate, vo.getPrevLeaders());
            if (!vo.getPrevLeaders().isEmpty()) {
                vo.setPrevMaxBoard(vo.getPrevLeaders().get(0).getBoard());
            }
        }
        return vo;
    }

    /** 取某交易日涨停池 ≥2 板龙头，按板高降序、同板按封单额降序，最多 20 只。 */
    private void collectLeaders(LocalDate date, List<NodePrefillVO.Leader> out) {
        if (date == null) {
            return;
        }
        List<MarketStock> zt = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP));
        List<MarketStock> ladders = new ArrayList<>();
        for (MarketStock row : zt) {
            int n = row.getConsecutive() == null ? 1 : row.getConsecutive();
            if (n >= 2) {
                ladders.add(row);
            }
        }
        ladders.sort((a, b) -> {
            int byBoard = Integer.compare(
                    b.getConsecutive() == null ? 1 : b.getConsecutive(),
                    a.getConsecutive() == null ? 1 : a.getConsecutive());
            if (byBoard != 0) {
                return byBoard;
            }
            int bySeal = (a.getSealAmount() == null ? 0 : a.getSealAmount().compareTo(
                    b.getSealAmount() == null ? BigDecimal.ZERO : b.getSealAmount()));
            return -bySeal;
        });
        for (MarketStock row : ladders) {
            if (out.size() >= 20) {
                break;
            }
            NodePrefillVO.Leader ld = new NodePrefillVO.Leader();
            ld.setCode(row.getCode());
            ld.setName(row.getName());
            ld.setIndustry(row.getIndustry());
            ld.setBoard(row.getConsecutive() == null ? 1 : row.getConsecutive());
            out.add(ld);
        }
    }

    /** 有明细的最近交易日：优先给定日，无则往前走逐个找；thead 是空就给 null（返回空预填）。 */
    private LocalDate resolveDate(LocalDate requested) {
        LocalDate start = requested != null ? requested : LocalDate.now();
        LocalDate date = start;
        for (int i = 0; i < 10; i++) {
            Long c = marketStockMapper.selectCount(new LambdaQueryWrapper<MarketStock>()
                    .eq(MarketStock::getTradeDate, date));
            if (c != null && c > 0) {
                return date;
            }
            date = date.minusDays(1);
        }
        return null;
    }

    private static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }
}