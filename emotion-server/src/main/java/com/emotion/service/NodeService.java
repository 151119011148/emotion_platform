package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final ObjectMapper objectMapper;

    public NodeService(NodeEventMapper nodeEventMapper,
                       AnchorMapper anchorMapper,
                       DailyRecordMapper dailyRecordMapper,
                       MarketStockMapper marketStockMapper,
                       MarketDailyMapper marketDailyMapper,
                       SurveillanceMapper surveillanceMapper,
                       NodeSuggestService nodeSuggestService,
                       ObjectMapper objectMapper) {
        this.nodeEventMapper = nodeEventMapper;
        this.anchorMapper = anchorMapper;
        this.dailyRecordMapper = dailyRecordMapper;
        this.marketStockMapper = marketStockMapper;
        this.marketDailyMapper = marketDailyMapper;
        this.surveillanceMapper = surveillanceMapper;
        this.nodeSuggestService = nodeSuggestService;
        this.objectMapper = objectMapper;
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
        if (event.getNodeType() != null) existing.setNodeType(event.getNodeType());
        if (event.getBreakStockCode() != null) existing.setBreakStockCode(event.getBreakStockCode());
        if (event.getBreakBoard() != null) existing.setBreakBoard(event.getBreakBoard());
        if (event.getBreakForm() != null) existing.setBreakForm(event.getBreakForm());
        if (event.getRepairStatus() != null) existing.setRepairStatus(event.getRepairStatus());
        if (event.getLastRecalcAt() != null) existing.setLastRecalcAt(event.getLastRecalcAt());

        nodeEventMapper.updateById(existing);
        return existing;
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
     * 节点类型 → 中文展示名（类型轴只认这 5 个具体值）。
     *
     * <p>查不到一律返回 null，由前端显示「未识别」：<strong>绝不回落成「普通节点」</strong>——
     * 反义定义只说明它不是什么，而且每加一个 node_type 这个词的所指就要变一次（PRD §2 命名约定）。
     */
    static String nodeTypeLabel(String nodeType) {
        if (nodeType == null) {
            return null;
        }
        switch (nodeType) {
            case "SPACE_BREAK":
                return "破局日 · 观察";
            case "SPACE_BREAK_NEXT":
                return "破局次日 · 出手";
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
     * <p>三种角色都算：节点票（{@code node_stock}，带代码、可精确匹配）、锚定龙头
     * （{@code anchor_stock}，只有名称）、D0 候选（{@code d0_candidates}，名称数组）。
     *
     * <p>不限日期。一只票可能既是甲事件的节点票、又是乙事件的 D0 候选，也可能事隔一个月才又涨停；
     * 全都列出来、每条带上 D0 与状态，比只留最近一条有用——这段周期还作不作数由看的人判断。
     *
     * <p>匹配口径与 {@link #stockCodeOf}、名称去空白保持一致：明细里的名字是「罗 牛 山」，
     * 而 d0_candidates 里写的是「罗牛山」，直接 equals 会漏掉。
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
        Map<String, List<NodeEvent>> byD0 = new HashMap<String, List<NodeEvent>>();
        for (NodeEvent e : events) {
            String code = stockCodeOf(e.getNodeStock());
            if (code != null) {
                index(byNodeCode, code, e);
            }
            String anchor = norm(e.getAnchorStock());
            if (anchor != null) {
                index(byAnchor, anchor, e);
            }
            for (String name : parseD0(e.getD0Candidates())) {
                String key = norm(name);
                if (key != null) {
                    index(byD0, key, e);
                }
            }
        }
        for (CandidateStock c : rows) {
            List<NodeTagVO> tags = new ArrayList<NodeTagVO>();
            collect(tags, byNodeCode.get(c.getCode()), NodeTagVO.KIND_NODE_STOCK, c);
            collect(tags, byAnchor.get(norm(c.getName())), NodeTagVO.KIND_ANCHOR, c);
            collect(tags, byD0.get(norm(c.getName())), NodeTagVO.KIND_D0_CAND, c);
            c.setNodeTags(tags);
        }
    }

    private static void index(Map<String, List<NodeEvent>> m, String key, NodeEvent e) {
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

    /** d0_candidates 是名称的 JSON 数组；解析不出来就当空集，不让一条脏数据把整页的标打没了。 */
    @SuppressWarnings("unchecked")
    private List<String> parseD0(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return Collections.emptyList();
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
    private static String stockCodeOf(String nodeStock) {
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