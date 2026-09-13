package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.Anchor;
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
        if (event.getLastRecalcAt() != null) existing.setLastRecalcAt(event.getLastRecalcAt());

        nodeEventMapper.updateById(existing);
        return existing;
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
        com.emotion.entity.MarketDaily day = date == null ? null
                : marketDailyMapper.selectOne(new LambdaQueryWrapper<com.emotion.entity.MarketDaily>()
                        .eq(com.emotion.entity.MarketDaily::getTradeDate, date).last("LIMIT 1"));
        if (day != null) {
            vo.setMaxBoard(day.getMaxConsecutiveLimit());
            vo.setLimitUpCount(day.getLimitUpCount());
            vo.setLimitDownCount(day.getLimitDownCount());
        }
        if (date == null) {
            return vo;
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
            if (vo.getLeaders().size() >= 20) {
                break;
            }
            NodePrefillVO.Leader ld = new NodePrefillVO.Leader();
            ld.setCode(row.getCode());
            ld.setName(row.getName());
            ld.setIndustry(row.getIndustry());
            ld.setBoard(row.getConsecutive() == null ? 1 : row.getConsecutive());
            vo.getLeaders().add(ld);
        }
        return vo;
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