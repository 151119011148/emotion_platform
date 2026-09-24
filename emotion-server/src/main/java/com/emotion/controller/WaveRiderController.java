package com.emotion.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.dto.WaveRiderCreateRequest;
import com.emotion.dto.WaveRiderNodeRequest;
import com.emotion.dto.WaveRiderRunRequest;
import com.emotion.dto.WaveRiderSaveVersionRequest;
import com.emotion.entity.CandidateStock;
import com.emotion.entity.NodeDetect;
import com.emotion.entity.Strategy;
import com.emotion.entity.StrategyRun;
import com.emotion.entity.StrategyTemplate;
import com.emotion.entity.StrategyVersion;
import com.emotion.mapper.CandidateStockMapper;
import com.emotion.mapper.CandidateT1Mapper;
import com.emotion.mapper.NodeDetectMapper;
import com.emotion.mapper.StrategyRunMapper;
import com.emotion.service.NodeService;
import com.emotion.service.TiantiService;
import com.emotion.service.TopicHeatService;
import com.emotion.service.WaveRiderConfigService;
import com.emotion.service.WaveRiderReviewService;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.NodeTagVO;
import com.emotion.vo.ThemeTagVO;
import com.emotion.waverider.WaveRiderConfig;
import com.emotion.waverider.WaveRiderEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WaveRider 的对外接口（PRD §11.1）。
 *
 * <p>读侧与写侧分开：{@code /strategies/**}、{@code /templates} 只碰库；
 * {@code /run} 会真的去读行情表并落候选。所有策略读写都做归属校验——
 * 别人的策略即使知道 id 也读不到、改不了。
 *
 * <p>{@code /candidates} 在候选为空时会带上 {@code emptyReason}：
 * 空表格和不命中是两件事，界面必须能把「今天确实没票」和「数据没拉回来」区分开。
 */
@RestController
@RequestMapping("/api/waverider")
public class WaveRiderController {

    private final WaveRiderConfigService configService;
    private final WaveRiderEngine engine;
    private final WaveRiderReviewService reviewService;
    private final NodeDetectMapper nodeMapper;
    private final StrategyRunMapper runMapper;
    private final CandidateStockMapper candidateMapper;
    private final CandidateT1Mapper t1Mapper;
    private final NodeService nodeService;
    private final TopicHeatService topicHeatService;
    private final TiantiService tiantiService;
    private final ObjectMapper objectMapper;

    public WaveRiderController(WaveRiderConfigService configService,
                               WaveRiderEngine engine,
                               WaveRiderReviewService reviewService,
                               NodeDetectMapper nodeMapper,
                               StrategyRunMapper runMapper,
                               CandidateStockMapper candidateMapper,
                               CandidateT1Mapper t1Mapper,
                               NodeService nodeService,
                               TopicHeatService topicHeatService,
                               TiantiService tiantiService,
                               ObjectMapper objectMapper) {
        this.configService = configService;
        this.engine = engine;
        this.reviewService = reviewService;
        this.nodeMapper = nodeMapper;
        this.runMapper = runMapper;
        this.candidateMapper = candidateMapper;
        this.t1Mapper = t1Mapper;
        this.nodeService = nodeService;
        this.topicHeatService = topicHeatService;
        this.tiantiService = tiantiService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 策略

    @GetMapping("/strategies")
    public ApiResponse<List<Map<String, Object>>> strategies(Authentication auth) {
        return ApiResponse.ok(configService.listStrategies(userId(auth)));
    }

    @PostMapping("/strategies")
    public ApiResponse<Strategy> create(Authentication auth, @RequestBody WaveRiderCreateRequest req) {
        return ApiResponse.ok(configService.create(userId(auth), req.getName(),
                req.getDescription(), req.getTemplateCode()));
    }

    @GetMapping("/strategies/{id}")
    public ApiResponse<Map<String, Object>> detail(Authentication auth, @PathVariable Long id) {
        Strategy s = owned(auth, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", s);
        out.put("currentConfig", configService.currentConfig(id));
        out.put("versions", configService.listVersions(id));
        return ApiResponse.ok(out);
    }

    /** 停用只影响定时任务，界面的「立即运行」照常可用。 */
    @PutMapping("/strategies/{id}/enabled")
    public ApiResponse<Strategy> setEnabled(Authentication auth, @PathVariable Long id,
                                           @RequestParam boolean enabled) {
        owned(auth, id);
        return ApiResponse.ok(configService.setEnabled(id, enabled));
    }

    @PostMapping("/strategies/{id}/versions")
    public ApiResponse<StrategyVersion> saveVersion(Authentication auth, @PathVariable Long id,
                                                    @RequestBody WaveRiderSaveVersionRequest req) {
        owned(auth, id);
        String json = toJson(req.getConfig());
        return ApiResponse.ok(configService.saveVersion(id, json, req.getChangeNote(), userId(auth)));
    }

    @GetMapping("/strategies/{id}/versions")
    public ApiResponse<List<StrategyVersion>> versions(Authentication auth, @PathVariable Long id) {
        owned(auth, id);
        return ApiResponse.ok(configService.listVersions(id));
    }

    /** 回滚 = 用旧版本内容建新版本，版本号只增不减。 */
    @PostMapping("/strategies/{id}/rollback/{versionId}")
    public ApiResponse<StrategyVersion> rollback(Authentication auth, @PathVariable Long id,
                                                 @PathVariable Long versionId) {
        owned(auth, id);
        return ApiResponse.ok(configService.rollback(id, versionId, userId(auth)));
    }

    /**
     * 只校验不落库。
     *
     * <p>返回体里带 {@code warnings} 与 {@code fatal} 两类：前者是「能用但不建议」
     * （例如 min_board_count 调得很高，历史上一次都不会命中），后者是直接存不进去。
     * 分成两类是为了让界面能把「提醒」和「拦住」画得不一样。
     */
    @PostMapping("/strategies/{id}/validate")
    public ApiResponse<Map<String, Object>> validate(Authentication auth, @PathVariable Long id,
                                                     @RequestBody WaveRiderSaveVersionRequest req) {
        owned(auth, id);
        WaveRiderConfig cfg = configService.parse(toJson(req.getConfig()));
        List<String> fatal = cfg.validate();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fatal", fatal);
        out.put("valid", fatal.isEmpty());
        List<String> warnings = new ArrayList<>();
        if (cfg.getMinBoardCount() >= 5) {
            warnings.add("min_board_count = " + cfg.getMinBoardCount()
                    + " 偏高，多数交易日可能一只都不命中；请对照「近 20 日触发次数」确认这不是死分支");
        }
        if (cfg.getEntryGapMax() > 0.05) {
            warnings.add("entry_gap_max 超过 5%：实测高开组在可执行口径下是负收益，放这么宽等于主动接高开");
        }
        if (cfg.getFilterConflictPolicy().equals(WaveRiderConfig.CONFLICT_DROP)
                && cfg.getFilterMinAmount() > 0.5) {
            warnings.add("DROP 策略 + 较高的成交额下限会把最高身位股一起剔掉（实测华瓷股份成交额仅 0.49 亿）");
        }
        out.put("warnings", warnings);
        return ApiResponse.ok(out);
    }

    // ------------------------------------------------------------------ 模板

    @GetMapping("/templates")
    public ApiResponse<List<StrategyTemplate>> templates() {
        return ApiResponse.ok(configService.listTemplates());
    }

    @PostMapping("/templates/{code}/apply")
    public ApiResponse<StrategyVersion> applyTemplate(Authentication auth, @PathVariable String code,
                                                      @RequestParam Long strategyId) {
        owned(auth, strategyId);
        return ApiResponse.ok(configService.applyTemplate(strategyId, code, userId(auth)));
    }

    @GetMapping("/versions/{v1}/diff/{v2}")
    public ApiResponse<Map<String, Object>> diff(Authentication auth,
                                                 @PathVariable Long v1, @PathVariable Long v2) {
        return ApiResponse.ok(configService.diff(v1, v2));
    }

    // ------------------------------------------------------------------ 运行

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(Authentication auth, @RequestBody WaveRiderRunRequest req) {
        if (req.getStrategyId() == null) {
            throw new IllegalArgumentException("strategyId 不能为空");
        }
        owned(auth, req.getStrategyId());
        LocalDate date = parse(req.getTradeDate(), LocalDate.now());
        WaveRiderEngine.Outcome o = engine.run(req.getStrategyId(), date, StrategyRun.TRIGGER_MANUAL,
                Boolean.TRUE.equals(req.getDryRun()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", o.getRunId());
        out.put("status", o.getStatus());
        out.put("tradeDate", date);
        out.put("candidateCount", o.getCandidates().size());
        out.put("funnel", o.getFunnel());
        out.put("warnings", o.getWarnings());
        nodeService.tagCandidates(o.getCandidates(), userId(auth));
        topicHeatService.tagTdxThemes(o.getCandidates(), date);
        tagAlerts(o.getCandidates(), date);
        out.put("candidates", o.getCandidates());
        return ApiResponse.ok(out);
    }

    /**
     * 手工补写某日候选的 D+1 表现。
     *
     * <p>两个用得上它的场合：定时任务漏跑了（补账是次日运行的前置步骤，但漏跑当天没人来补）；
     * 以及候选是手工试跑出来的（试跑不经定时任务，没有前置步骤替它补）。
     */
    @PostMapping("/backfill")
    public ApiResponse<Map<String, Object>> backfill(Authentication auth, @RequestParam Long strategyId,
                                                     @RequestParam String date) {
        owned(auth, strategyId);
        LocalDate d = parse(date, LocalDate.now());
        int n = engine.backfillT1(strategyId, d);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategyId", strategyId);
        out.put("tradeDate", d);
        out.put("patched", n);
        return ApiResponse.ok(out);
    }

    @GetMapping("/runs")
    public ApiResponse<List<StrategyRun>> runs(Authentication auth, @RequestParam Long strategyId,
                                               @RequestParam(required = false) String date) {
        owned(auth, strategyId);
        LambdaQueryWrapper<StrategyRun> q = new LambdaQueryWrapper<StrategyRun>()
                .eq(StrategyRun::getStrategyId, strategyId)
                .orderByDesc(StrategyRun::getStartedAt)
                .last("LIMIT 100");
        if (date != null && !date.trim().isEmpty()) {
            q = q.eq(StrategyRun::getTradeDate, parse(date, LocalDate.now()));
        }
        return ApiResponse.ok(runMapper.selectList(q));
    }

    /**
     * 某日候选池。空的时候带空态原因。
     *
     * <p>date 不给取最近一个有候选的交易日——盘后没跑或当天还没到 15:30 时，
     * 直接回一个空表格会让人以为策略坏了。
     */
    @GetMapping("/candidates")
    public ApiResponse<Map<String, Object>> candidates(Authentication auth, @RequestParam Long strategyId,
                                                      @RequestParam(required = false) String date) {
        owned(auth, strategyId);
        LocalDate target = (date == null || date.trim().isEmpty())
                ? latestCandidateDate(strategyId) : parse(date, LocalDate.now());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategyId", strategyId);
        out.put("tradeDate", target);
        if (target == null) {
            out.put("candidates", new ArrayList<CandidateStock>());
            out.put("emptyReason", "该策略还没有跑出过任何候选。可以先在配置页确认参数，再手工运行一次");
            return ApiResponse.ok(out);
        }

        List<CandidateStock> rows = candidateMapper.listOfDay(strategyId, target);
        // 读侧打「来自节点追踪」的标（瞬态）：节点事件会被复算改写，落库等于把当时的判断冻在候选行上
        nodeService.tagCandidates(rows, userId(auth));
        // 题材同上：读侧现算。题材成分与当日热度都会变，落库等于把「今天谁最热」冻住
        topicHeatService.tagTdxThemes(rows, target);
        tagAlerts(rows, target);
        out.put("candidates", rows);
        out.put("count", rows.size());

        // T+1 表现（可能还没到验证日，那就没有）
        out.put("t1", t1Mapper.selectList(new LambdaQueryWrapper<com.emotion.entity.CandidateT1>()
                .eq(com.emotion.entity.CandidateT1::getStrategyId, strategyId)
                .eq(com.emotion.entity.CandidateT1::getTradeDate, target)));

        StrategyRun last = runMapper.selectOne(new LambdaQueryWrapper<StrategyRun>()
                .eq(StrategyRun::getStrategyId, strategyId)
                .eq(StrategyRun::getTradeDate, target)
                .orderByDesc(StrategyRun::getStartedAt)
                .last("LIMIT 1"));
        if (last != null) {
            out.put("runStatus", last.getStatus());
            out.put("runWarning", last.getWarning());
            out.put("funnel", parseJson(last.getDetailJson()));
        }
        if (rows.isEmpty() && last != null) {
            out.put("emptyReason", emptyReason(last));
        }
        out.put("nodeCount", last == null ? 0 : last.getNodeCount());
        return ApiResponse.ok(out);
    }

    // ------------------------------------------------------------------ 节点

    @GetMapping("/nodes")
    public ApiResponse<List<NodeDetect>> nodes(Authentication auth,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to,
                                               @RequestParam(required = false) String type) {
        LambdaQueryWrapper<NodeDetect> q = new LambdaQueryWrapper<NodeDetect>()
                .eq(NodeDetect::getUserId, userId(auth))
                .orderByDesc(NodeDetect::getTradeDate);
        if (from != null && !from.trim().isEmpty()) {
            q = q.ge(NodeDetect::getTradeDate, parse(from, LocalDate.now()));
        }
        if (to != null && !to.trim().isEmpty()) {
            q = q.le(NodeDetect::getTradeDate, parse(to, LocalDate.now()));
        }
        if (type != null && !type.trim().isEmpty()) {
            q = q.eq(NodeDetect::getNodeType, type.trim().toUpperCase());
        }
        return ApiResponse.ok(nodeMapper.selectList(q));
    }

    /** 人工标记节点。优先级高于自动识别，会被 next 次权重标定采样。 */
    @PostMapping("/nodes")
    public ApiResponse<NodeDetect> markNode(Authentication auth, @RequestBody WaveRiderNodeRequest req) {
        if (req.getNodeType() == null || req.getNodeType().trim().isEmpty()) {
            throw new IllegalArgumentException("nodeType 不能为空");
        }
        LocalDate date = parse(req.getTradeDate(), LocalDate.now());
        String type = req.getNodeType().trim().toUpperCase();

        NodeDetect exist = nodeMapper.selectOne(new LambdaQueryWrapper<NodeDetect>()
                .eq(NodeDetect::getUserId, userId(auth))
                .eq(NodeDetect::getTradeDate, date)
                .eq(NodeDetect::getNodeType, type)
                .last("LIMIT 1"));
        if (exist != null) {
            // 同一天同类节点只留一条：人工表态覆盖已有记录，而不是插出重复行
            exist.setSource(NodeDetect.SOURCE_MANUAL);
            exist.setConfirmed(1);
            exist.setConfirmedBy(userId(auth));
            exist.setHitExpr(req.getNote());
            nodeMapper.updateById(exist);
            return ApiResponse.ok(exist);
        }
        NodeDetect n = new NodeDetect();
        n.setUserId(userId(auth));
        n.setStrategyId(req.getStrategyId());
        n.setTradeDate(date);
        n.setNodeType(type);
        n.setSource(NodeDetect.SOURCE_MANUAL);
        n.setConfirmed(1);
        n.setConfirmedBy(userId(auth));
        n.setHitExpr(req.getNote());
        nodeMapper.insert(n);
        return ApiResponse.ok(n);
    }

    /**
     * 取消节点标记。
     *
     * <p>做成 {@code confirmed=-1} 而不是物理删除：节点判断本身是可争论的，
     * 留着「当时标过又被否掉」这条记录，比事后一片空白有用。
     */
    @DeleteMapping("/nodes/{id}")
    public ApiResponse<NodeDetect> cancelNode(Authentication auth, @PathVariable Long id) {
        NodeDetect n = nodeMapper.selectById(id);
        if (n == null || !userId(auth).equals(n.getUserId())) {
            throw new IllegalArgumentException("没有这个节点标记：" + id);
        }
        n.setConfirmed(-1);
        n.setConfirmedBy(userId(auth));
        nodeMapper.updateById(n);
        return ApiResponse.ok(n);
    }

    // ------------------------------------------------------------------ 复盘与导出

    @GetMapping("/review")
    public ApiResponse<Map<String, Object>> review(Authentication auth, @RequestParam Long strategyId,
                                                   @RequestParam String from, @RequestParam String to) {
        owned(auth, strategyId);
        return ApiResponse.ok(reviewService.review(strategyId, parse(from, LocalDate.now()),
                parse(to, LocalDate.now())));
    }

    /** 导出当日候选。format=md（默认）或 csv，内容以文本返回，前端自己存文件。 */
    @GetMapping("/export")
    public ApiResponse<Map<String, Object>> export(Authentication auth, @RequestParam Long strategyId,
                                                   @RequestParam(required = false) String date,
                                                   @RequestParam(required = false, defaultValue = "md") String format) {
        owned(auth, strategyId);
        LocalDate target = (date == null || date.trim().isEmpty())
                ? latestCandidateDate(strategyId) : parse(date, LocalDate.now());
        List<CandidateStock> rows = target == null ? new ArrayList<CandidateStock>()
                : candidateMapper.listOfDay(strategyId, target);
        nodeService.tagCandidates(rows, userId(auth));
        topicHeatService.tagTdxThemes(rows, target);
        tagAlerts(rows, target);
        boolean csv = "csv".equalsIgnoreCase(format);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tradeDate", target);
        out.put("format", csv ? "csv" : "md");
        out.put("filename", "waverider-" + target + (csv ? ".csv" : ".md"));
        out.put("content", csv ? toCsv(target, rows) : toMd(target, rows));
        return ApiResponse.ok(out);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 打「执行预警」（瞬态，不落库）：候选里哪些是一字断魂刀。
     *
     * <p>判据不在这里写第二份——天梯页与候选池各留一套的话，改了一处、另一处会静默不一致，
     * 而「静默不一致」正是这类规则最难查的坏法。这里只负责把结果贴到行上。
     */
    private void tagAlerts(List<CandidateStock> rows, LocalDate date) {
        if (rows == null || rows.isEmpty() || date == null) {
            return;
        }
        List<String> codes = new ArrayList<>(rows.size());
        for (CandidateStock c : rows) {
            codes.add(c.getCode());
        }
        Set<String> duanDao = tiantiService.duanDaoCodes(date, codes);
        for (CandidateStock c : rows) {
            if (duanDao.contains(c.getCode())) {
                c.setAlertFlag(CandidateStock.ALERT_DUANDAO);
            }
        }
    }

    /** 预警代码 → 中文。前端另有一份同文案（它只拿到代码），改这里记得一起改。 */
    private String alertText(CandidateStock c) {
        return CandidateStock.ALERT_DUANDAO.equals(c.getAlertFlag()) ? "一字断魂刀" : "";
    }

    private String toMd(LocalDate date, List<CandidateStock> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# WaveRider 候选池 · ").append(date).append("\n\n");
        sb.append("> 本清单为交易辅助工具输出，不构成任何投资建议。\n");
        sb.append("> 收益基准为 T+1 开盘价（候选当日已涨停，T 日收盘价实盘买不到）。\n\n");
        if (rows.isEmpty()) {
            sb.append("当日无命中候选。\n");
            return sb.toString();
        }
        sb.append("| # | 代码 | 名称 | 连板 | 节点 | 题材（通达信） | 身位 | 得分 | 建议仓位 | 警示 | 风险 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (CandidateStock c : rows) {
            sb.append("| ").append(c.getRankNo())
                    .append(" | ").append(c.getCode())
                    .append(" | ").append(c.getName())
                    .append(" | ").append(c.getBoard() == null ? "-" : c.getBoard())
                    .append(" | ").append(nodeTagText(c))
                    .append(" | ").append(themeText(c, 3))
                    .append(" | ").append(nz(c.getPositionType()))
                    .append(" | ").append(c.getScore() == null ? "-" : c.getScore())
                    .append(" | ").append(positionText(c))
                    .append(" | ").append(nz(alertText(c)))
                    .append(" | ").append(nz(c.getRiskFlag()))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private String toCsv(LocalDate date, List<CandidateStock> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("trade_date,rank,code,name,board,node_tag,tdx_themes,position_type,"
                + "score,suggest_position,alert_flag,risk_flag\n");
        for (CandidateStock c : rows) {
            sb.append(date).append(',').append(c.getRankNo())
                    .append(',').append(c.getCode())
                    .append(',').append(quote(c.getName()))
                    .append(',').append(c.getBoard() == null ? "" : c.getBoard())
                    .append(',').append(quote(nodeTagText(c)))
                    .append(',').append(quote(themeText(c, 0)))
                    .append(',').append(quote(c.getPositionType()))
                    .append(',').append(c.getScore() == null ? "" : c.getScore())
                    .append(',').append(c.getSuggestPosition() == null ? "" : c.getSuggestPosition())
                    // csv 给机器码、md 给中文：与同一行的 risk_flag 保持一致（那一列也是码）
                    .append(',').append(nz(c.getAlertFlag()))
                    .append(',').append(nz(c.getRiskFlag()))
                    .append('\n');
        }
        return sb.toString();
    }

    /** 三种角色在导出里的显示名。 */
    private static final Map<String, String> NODE_KIND_LABEL = new LinkedHashMap<String, String>();
    static {
        NODE_KIND_LABEL.put(NodeTagVO.KIND_NODE_STOCK, "节点票");
        NODE_KIND_LABEL.put(NodeTagVO.KIND_ANCHOR, "锚定龙头");
        NODE_KIND_LABEL.put(NodeTagVO.KIND_D0_CAND, "D0候选");
    }

    /**
     * 导出列用的紧凑文本：同类角色合并计数，如 {@code 节点票×2/D0候选}。空则空串。
     *
     * <p>导出是拿去对着行情软件下单的，一列里塞不下每条事件的 D0 与状态，
     * 所以这里只留「是什么角色」，来源细节在页面上看。
     */
    private String nodeTagText(CandidateStock c) {
        if (c.getNodeTags() == null || c.getNodeTags().isEmpty()) {
            return "";
        }
        Map<String, Integer> byKind = new LinkedHashMap<String, Integer>();
        for (NodeTagVO t : c.getNodeTags()) {
            String label = NODE_KIND_LABEL.containsKey(t.getKind())
                    ? NODE_KIND_LABEL.get(t.getKind()) : t.getKind();
            byKind.merge(label, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : byKind.entrySet()) {
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append(e.getKey());
            if (e.getValue() > 1) {
                sb.append("×").append(e.getValue());
            }
        }
        return sb.toString();
    }

    /**
     * 导出列用的题材文本：通达信概念板块，按当日该题材涨停家数降序。
     *
     * <p>{@code max <= 0} 表示不截断：md 是给人看的表格，截 3 个（列宽有限）；
     * csv 是拿去透视的，截断等于丢数据。
     *
     * <p>没有题材记录时退回行业。宁可显示一个口径不同的标签，
     * 也不要空着让人以为「这只票没题材」。
     */
    private String themeText(CandidateStock c, int max) {
        List<ThemeTagVO> ts = c.getTdxThemes();
        if (ts == null || ts.isEmpty()) {
            return nz(c.getTopic());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ts.size(); i++) {
            if (max > 0 && i >= max) {
                sb.append(" 等").append(ts.size()).append("个");
                break;
            }
            if (i > 0) {
                sb.append("、");
            }
            sb.append(ts.get(i).getName());
        }
        return sb.toString();
    }

    private String positionText(CandidateStock c) {
        return c.getSuggestPosition() == null ? "-"
                : c.getSuggestPosition().multiply(java.math.BigDecimal.valueOf(100))
                .setScale(2, java.math.RoundingMode.HALF_UP) + "%";
    }

    private String quote(String s) {
        if (s == null) {
            return "";
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** 把「跑到哪一步归零」翻译成一句人话。空白表格最容易被误读成「策略坏了」。 */
    private String emptyReason(StrategyRun run) {
        if (StrategyRun.STATUS_FAILED.equals(run.getStatus())) {
            return "这次运行失败了：" + nz(run.getErrorMsg());
        }
        Map<String, Object> funnel = parseJson(run.getDetailJson());
        if (funnel.isEmpty()) {
            return "当日无命中候选";
        }
        Object poolSize = funnel.get("涨停池");
        if (poolSize == null || Integer.parseInt(String.valueOf(poolSize)) == 0) {
            return "当日行情还没入库（涨停池为空）。行情拉取任务跑完之后再试，或手工触发一次行情拉取";
        }
        for (Map.Entry<String, Object> e : funnel.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("连板") || k.startsWith("剔除") || k.startsWith("取身位") || k.startsWith("清单上限")) {
                if (Integer.parseInt(String.valueOf(e.getValue())) == 0) {
                    return "候选在「" + k + "」这一步归零——当日没有满足条件的个股";
                }
            }
        }
        return "当日无命中候选";
    }

    private LocalDate latestCandidateDate(Long strategyId) {
        CandidateStock top = candidateMapper.selectOne(new LambdaQueryWrapper<CandidateStock>()
                .eq(CandidateStock::getStrategyId, strategyId)
                .orderByDesc(CandidateStock::getTradeDate)
                .last("LIMIT 1"));
        return top == null ? null : top.getTradeDate();
    }

    /** 归属校验：别人的策略一律当不存在。 */
    private Strategy owned(Authentication auth, Long strategyId) {
        Strategy s = configService.requireStrategy(strategyId);
        if (!userId(auth).equals(s.getUserId())) {
            throw new IllegalArgumentException("没有这个策略：" + strategyId);
        }
        return s;
    }

    private String toJson(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String) {
            return (String) o;
        }
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体不是合法 JSON：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private static Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    private static LocalDate parse(String raw, LocalDate fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd，实得：" + raw);
        }
    }
}
