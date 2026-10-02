package com.emotion.waverider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.CandidateStock;
import com.emotion.entity.CandidateT1;
import com.emotion.entity.DailyRecord;
import com.emotion.entity.MarketStock;
import com.emotion.entity.NodeDetect;
import com.emotion.entity.NodeEvent;
import com.emotion.entity.Stock;
import com.emotion.entity.Strategy;
import com.emotion.entity.StrategyRun;
import com.emotion.market.HighControlStock;
import com.emotion.market.TencentClient;
import com.emotion.mapper.CandidateStockMapper;
import com.emotion.mapper.CandidateT1Mapper;
import com.emotion.mapper.DailyRecordMapper;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.NodeDetectMapper;
import com.emotion.mapper.StockMapper;
import com.emotion.mapper.StrategyRunMapper;
import com.emotion.service.DailyBarService;
import com.emotion.service.NodeService;
import com.emotion.service.WaveRiderConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WaveRider 的候选生成引擎：从当日涨停池里挑出身位板，过一遍过滤器，算分与仓位，落库。
 *
 * <p><strong>这一版的口径</strong>：候选池 = 当日涨停且连板数 ≥ {@code min_board_count}，
 * 按题材取身位（{@code position_mode=highest_board}），按<strong>可执行性</strong>排序。
 *
 * <p>为什么排序不看分数，只看可执行性——这是本项目最贵的一条经验：
 * 候选池的定义决定了「T 日收盘价买不到」（那天它涨停了），所以任何以 close(T) 为起算价的
 * 指标都在描述一件实盘做不到的事。实测同一批 226 个样本：
 * 以 close(T) 起算 +3.36%、胜率 65.0%；换成 T+1 开盘价起算 −0.20%、胜率 40.3%。
 * 更麻烦的是方向会反过来——A 口径下最赚的那组（跳空 &gt;3%）恰是实盘最亏的一组（−2.27%），
 * 因为该组近半是一字板，根本买不进。所以这里的排序锚在「能不能买到」上，
 * 而不是「历史上哪类票涨得多」。
 *
 * <p>可执行性的代理指标是<strong>封单额÷成交额</strong>：全库唯一能在 T 日事前预警
 * 「T+1 买不进」的变量，且强单调（&lt;15%→8.4% 一字 ｜ 150~300%→52.9% ｜ ≥300%→87.0%）。
 * 越过 {@code seal_lock_ratio} 的直接出池。
 *
 * <p><strong>尚未实现</strong>：自动节点识别（PRD §5 的 {@code node_rules} AST）。当日节点仍会从
 * {@code t_node_detect} 读出来并计入 run.node_count，但不会因此产出候选。
 *
 * <p>评分里的<strong>节点分是活的</strong>：它读的是他手工录的 {@code t_node_event}，而不是
 * PRD §5.5 点名的 {@code t_node_detect}——后者要等自动节点识别才有数据，照它实现这一项仍是恒 0。
 * 匹配口径与策略选股页那个「节点票」标同源，见 {@link #build}；{@code node_type} 为 NULL 时
 * 按 1.0 计，理由写在 {@link #nodePart} 上。
 */
@Service
public class WaveRiderEngine {

    private static final Logger log = LoggerFactory.getLogger(WaveRiderEngine.class);

    /** 封板时间早于此刻算「早封板」，给一个评分加成。1000 = 10:00。 */
    private static final int EARLY_SEAL_HHMM = 1000;

    private final WaveRiderConfigService configService;
    private final MarketStockMapper marketStockMapper;
    private final StockMapper stockMapper;
    private final NodeDetectMapper nodeDetectMapper;
    private final CandidateStockMapper candidateMapper;
    private final CandidateT1Mapper t1Mapper;
    private final StrategyRunMapper runMapper;
    /** 情绪温度的出处。t_daily_record 按 user_id 分行，同一天多个复盘人各有一份、值可以差好几度。 */
    private final DailyRecordMapper dailyRecordMapper;
    private final DailyBarService dailyBarService;
    /** 节点票身份的唯一出处：与策略选股页那个「节点票」标共用一套匹配，见 {@link #build}。 */
    private final NodeService nodeService;
    private final ObjectMapper objectMapper;

    public WaveRiderEngine(WaveRiderConfigService configService,
                           MarketStockMapper marketStockMapper,
                           StockMapper stockMapper,
                           NodeDetectMapper nodeDetectMapper,
                           CandidateStockMapper candidateMapper,
                           CandidateT1Mapper t1Mapper,
                           StrategyRunMapper runMapper,
                           DailyRecordMapper dailyRecordMapper,
                           DailyBarService dailyBarService,
                           NodeService nodeService,
                           ObjectMapper objectMapper) {
        this.configService = configService;
        this.marketStockMapper = marketStockMapper;
        this.stockMapper = stockMapper;
        this.nodeDetectMapper = nodeDetectMapper;
        this.candidateMapper = candidateMapper;
        this.t1Mapper = t1Mapper;
        this.runMapper = runMapper;
        this.dailyRecordMapper = dailyRecordMapper;
        this.dailyBarService = dailyBarService;
        this.nodeService = nodeService;
        this.objectMapper = objectMapper;
    }

    /** 一次运行的产出，供 controller / task 直接组装响应。 */
    public static class Outcome {
        private final Long runId;
        private final String status;
        private final List<CandidateStock> candidates;
        private final Map<String, Integer> funnel;
        private final List<String> warnings;

        public Outcome(Long runId, String status, List<CandidateStock> candidates,
                       Map<String, Integer> funnel, List<String> warnings) {
            this.runId = runId;
            this.status = status;
            this.candidates = candidates;
            this.funnel = funnel;
            this.warnings = warnings;
        }

        public Long getRunId() {
            return runId;
        }

        public String getStatus() {
            return status;
        }

        public List<CandidateStock> getCandidates() {
            return candidates;
        }

        public Map<String, Integer> getFunnel() {
            return funnel;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }

    /** 当日三池是否已入库。定时任务用它决定「跑」还是「skip」，绝不写空候选。 */
    public boolean hasPoolData(LocalDate date) {
        Long n = marketStockMapper.selectCount(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date));
        return n != null && n > 0;
    }

    // ------------------------------------------------------------------ 选股

    /**
     * 跑一次选股。
     *
     * @param dryRun true 时只算不落库（回测/试跑），候选仍会返回给调用方看
     */
    @Transactional
    public Outcome run(Long strategyId, LocalDate tradeDate, String triggerType, boolean dryRun) {
        long t0 = System.currentTimeMillis();
        Strategy strategy = configService.requireStrategy(strategyId);
        WaveRiderConfig cfg = configService.currentConfig(strategyId);

        StrategyRun run = new StrategyRun();
        run.setStrategyId(strategyId);
        run.setVersionId(strategy.getCurrentVersionId());
        run.setTradeDate(tradeDate);
        run.setTriggerType(triggerType);
        run.setDryRun(dryRun ? 1 : 0);
        run.setStatus(StrategyRun.STATUS_RUNNING);
        run.setCandidateCount(0);
        run.setNodeCount(0);
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);

        Map<String, Integer> funnel = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<CandidateStock> chosen = new ArrayList<>();
        String status = StrategyRun.STATUS_SUCCESS;
        String error = null;

        try {
            int nodeCount = countNodes(tradeDate);
            run.setNodeCount(nodeCount);
            if (nodeCount == 0) {
                // 不是失败：没有节点日就只是没有节点票。身位板照跑。
                warnings.add("NO_NODE");
            }
            chosen = select(strategyId, strategy.getUserId(), tradeDate, cfg, run.getId(), funnel, warnings);

            if (!dryRun) {
                // 整日替换：AC-9 要求同一天重跑多次候选行数不变（run 表会多行留痕）。
                candidateMapper.delete(new LambdaQueryWrapper<CandidateStock>()
                        .eq(CandidateStock::getStrategyId, strategyId)
                        .eq(CandidateStock::getTradeDate, tradeDate));
                for (CandidateStock c : chosen) {
                    candidateMapper.insert(c);
                }
            }
            if (chosen.isEmpty()) {
                status = StrategyRun.STATUS_EMPTY;
            }
        } catch (RuntimeException e) {
            status = StrategyRun.STATUS_FAILED;
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("WaveRider 运行失败 strategy={} date={}", strategyId, tradeDate, e);
        }

        run.setStatus(status);
        run.setCandidateCount(chosen.size());
        run.setErrorMsg(error);
        run.setWarning(warnings.isEmpty() ? null : truncate(String.join(",", warnings), 120));
        run.setDetailJson(toJson(funnel));
        run.setFinishedAt(LocalDateTime.now());
        run.setCostMs((int) (System.currentTimeMillis() - t0));
        runMapper.updateById(run);

        return new Outcome(run.getId(), status, chosen, funnel, warnings);
    }

    /**
     * 选股主体。漏斗每一步都记数——这是界面回答「为什么只剩这几只」的唯一依据，
     * 也是防止某个阈值悄悄变成死分支的手段（PRD AC-11 要求近 20 日触发次数可见）。
     */
    private List<CandidateStock> select(Long strategyId, Long userId, LocalDate tradeDate, WaveRiderConfig cfg,
                                        Long runId, Map<String, Integer> funnel, List<String> warnings) {
        List<MarketStock> pool = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, tradeDate)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP));
        funnel.put("涨停池", pool.size());
        if (pool.isEmpty()) {
            return new ArrayList<>();
        }

        // ---- 连板门槛
        List<MarketStock> step = new ArrayList<>();
        for (MarketStock m : pool) {
            if (m.getConsecutive() != null && m.getConsecutive() >= cfg.getMinBoardCount()) {
                step.add(m);
            }
        }
        funnel.put("连板≥" + cfg.getMinBoardCount(), step.size());

        // ---- ST
        if (cfg.isFilterSt()) {
            step = keep(step, m -> m.getName() != null && !isSt(m.getName()));
            funnel.put("剔除ST", step.size());
        }

        // ---- 次新（t_stock.listed_at 为 NULL 时不剔，拿不到数据不算不达标）
        Map<String, Stock> dict = loadDict(step);
        if (cfg.getFilterNewStockDays() > 0) {
            final LocalDate ref = tradeDate;
            step = keep(step, m -> {
                Stock s = dict.get(m.getCode());
                if (s == null || s.getListedAt() == null) {
                    return true;
                }
                return s.getListedAt().plusDays(cfg.getFilterNewStockDays()).isBefore(ref)
                        || s.getListedAt().plusDays(cfg.getFilterNewStockDays()).isEqual(ref);
            });
            funnel.put("剔除次新", step.size());
        }

        // ---- 封单锁死：只标记，不剔除。
        // 226 个样本按 封单额÷成交额 分五档，以 T 日涨停价为买点（本策略的真实买点）时，
        // 收益与胜率沿封单强度严格单调递增：A 口径 +1.71/+3.55/+4.37/+5.40/+7.56%，
        // 胜率 53%→87%；换成 D+1 开盘价为买点则单调递减（+0.38 → -1.71%）。
        // 所以封单锁死是正向信号：它意味着这票大概率已经是/即将是最强的板，
        // 只不过通常 T 日就一字（≥150% 档实测 97.5% 开盘即封），要买得靠集合竞价排队。
        // 「排不到队」是执行成本，不该用剔除来消化——那剔掉的恰是清单里最好的一组。
        int locked = 0;
        for (MarketStock m : step) {
            Double r = sealRatio(m);
            if (r != null && r >= cfg.getSealLockRatio()) {
                locked++;
            }
        }
        if (locked > 0) {
            warnings.add("SEAL_LOCKED_" + locked);
        }

        // ---- 取身位：同题材内按板数取前 max_same_position 只
        List<MarketStock> picked = pickPositions(step, cfg);
        funnel.put("取身位", picked.size());

        // ---- 情绪温度 → 仓位系数（PRD §6/§17）。市场级变量，同日全部候选共享同一取值，
        // 所以在这儿算一次就够；它不进个股评分——§12.4.3：对「选哪只」没有区分度。
        Double temperature = temperatureOf(userId, tradeDate);
        Double tempScale = null;
        if (temperature == null) {
            warnings.add("NO_TEMPERATURE");
        } else {
            tempScale = cfg.temperatureScale(temperature);
        }

        List<CandidateStock> rows = build(strategyId, userId, tradeDate, runId, cfg, picked,
                temperature, tempScale);

        // ---- 清单长度上限（相对当日涨停池）。
        // 必须放在【排序之后】截断。放在排序之前，砍掉的是「题材遍历顺序靠后」的票——
        // 那不是任何意义上的取舍，只是把字典序当成了筛选条件；用户看到的将是
        // 「为什么同一天、同样两个板，A 进了 B 没进」这种解释不通的结果。
        if (!rows.isEmpty()) {
            int cap = (int) Math.floor(pool.size() * cfg.getMaxSelectRate());
            if (cap > 0 && rows.size() > cap) {
                rows = new ArrayList<>(rows.subList(0, cap));
                warnings.add("CANDIDATE_TRUNCATED");
            }
            funnel.put("清单上限", rows.size());
        }
        return rows;
    }

    /**
     * 当日情绪温度；<strong>拿不到时返回 {@code null}</strong>。
     *
     * <p>{@code null} 不是 0。PRD §13 的异常分支写得很明确：「T 日无情绪温度记录」时候选池
     * <strong>照常产出</strong>，{@code suggest_position} <strong>置空</strong>并标
     * {@code NO_TEMPERATURE}，不按 0 仓位处理。
     *
     * <p>为什么不能把温度做成候选池的前置条件（§12.4.8「落地障碍」）：温度不是纯自动产物，
     * {@code manual_first_sealed_rate}、{@code manual_top_high_break} 等子指标必须人工填写，
     * 实测 19 个交易日里只有 14 天有温度。一旦当前置条件，用户某天没复盘当天就整片不可用。
     *
     * <p>取的是<strong>策略属主自己那行</strong>：{@code uk_user_date} 保证 (user_id, trade_date)
     * 唯一，而同一天不同复盘人的温度可以差出好几度（2026-09-30：user 1 = 57.4、user 2 = 52.8），
     * 差到跨档就会给出不同系数——所以这里必须跟着引擎其余部分一样按 userId 作用域取。
     */
    private Double temperatureOf(Long userId, LocalDate tradeDate) {
        DailyRecord rec = dailyRecordMapper.selectOne(new LambdaQueryWrapper<DailyRecord>()
                .eq(DailyRecord::getUserId, userId)
                .eq(DailyRecord::getTradeDate, tradeDate));
        BigDecimal t = rec == null ? null : rec.getTemperature();
        return t == null ? null : t.doubleValue();
    }

    /**
     * 身位选取：每个题材内按连板数降序取前 N 只（{@code max_same_position}）。
     *
     * <p>题材料取 {@code t_market_stock.industry}：库内现成、当日就有值。
     * PRD 附录把 {@code topic_source} 默认写成 tdx_concept，但那份概念库要靠同步任务维护，
     * 拿不到时的退路就是行业——这里选一个当天一定有值的来源。
     */
    private List<MarketStock> pickPositions(List<MarketStock> rows, WaveRiderConfig cfg) {
        Map<String, List<MarketStock>> byTopic = new LinkedHashMap<>();
        for (MarketStock m : rows) {
            String topic = m.getIndustry() == null || m.getIndustry().trim().isEmpty()
                    ? "未分类" : m.getIndustry().trim();
            byTopic.computeIfAbsent(topic, k -> new ArrayList<>()).add(m);
        }
        List<MarketStock> out = new ArrayList<>();
        for (Map.Entry<String, List<MarketStock>> e : byTopic.entrySet()) {
            List<MarketStock> group = e.getValue();
            // 组内排序：板数降序；同板数比成交额（流动性好的优先，成交额缺失当 0 排后面）。
            // 写成显式 Comparator 而不是链式 comparing：成交额是 BigDecimal，
            // 链式写法在 thenComparing 处会因为键类型推断不起作用而编译不过。
            group.sort(new Comparator<MarketStock>() {
                @Override
                public int compare(MarketStock a, MarketStock b) {
                    int c = Integer.compare(nz(b.getConsecutive()), nz(a.getConsecutive()));
                    if (c != 0) {
                        return c;
                    }
                    BigDecimal x = a.getAmount() == null ? BigDecimal.ZERO : a.getAmount();
                    BigDecimal y = b.getAmount() == null ? BigDecimal.ZERO : b.getAmount();
                    return y.compareTo(x);
                }
            });
            int take = Math.min(cfg.getMaxSamePosition(), group.size());
            out.addAll(group.subList(0, take));
        }
        return out;
    }

    /**
     * 组装候选行：算分、算仓位、打风险标、排展示顺序。
     *
     * <p>{@code temperature} / {@code tempScale} 是当日的情绪温度原值与换算出的仓位系数，
     * 两者只在算仓位与写追溯明细时用得上——<strong>不参与算分</strong>（§12.4.3：温度是市场级变量，
     * 同日全部候选共享同一取值，对「选哪只」没有区分度）。拿不到温度时两者都为 {@code null}，
     * 此时仓位一律置空而不是按 0 处理。
     */
    private List<CandidateStock> build(Long strategyId, Long userId, LocalDate tradeDate, Long runId,
                                       WaveRiderConfig cfg, List<MarketStock> picked,
                                       Double temperature, Double tempScale) {
        if (picked.isEmpty()) {
            return new ArrayList<>();
        }
        // 节点分查的是 t_node_event（他手工录的那份），不是 PRD §5.5 说的 t_node_detect：
        // 后者要靠自动节点识别才有数据，那张表至今 0 行，照它实现这一项仍是恒 0。
        // 认的列和策略选股页那个「节点票」标相同，但这里多卡失效与窗口两道闸，
        // 「有标不加分」是设计而非 bug，判据见 NodeService#scoredNodeStocks。
        Map<String, NodeEvent> nodeStocks =
                nodeService.scoredNodeStocks(userId, tradeDate, cfg.getNodeScanWindow());
        int maxBoard = 0;
        for (MarketStock m : picked) {
            maxBoard = Math.max(maxBoard, nz(m.getConsecutive()));
        }
        // 题材内最高板：身位描述与「是不是身位股」都看它
        Map<String, Integer> topicTop = new HashMap<>();
        for (MarketStock m : picked) {
            String t = topic(m);
            topicTop.merge(t, nz(m.getConsecutive()), Math::max);
        }

        List<CandidateStock> rows = new ArrayList<>();
        double maxScore = Double.NEGATIVE_INFINITY;
        List<Double> scores = new ArrayList<>();
        Set<String> pickedCodes = new HashSet<>();
        for (MarketStock m : picked) {
            pickedCodes.add(m.getCode());
        }
        Map<String, BigDecimal> firstBoardMv = HighControlStock.firstBoardFloatMv(
                marketStockMapper, pickedCodes, tradeDate);
        for (MarketStock m : picked) {
            BigDecimal fbMv = firstBoardMv.get(m.getCode());
            boolean highControl = HighControlStock.isHighControl(m, fbMv);
            double riskPenalty = riskPenalty(m, cfg, highControl);
            String riskFlag = riskFlag(m, cfg, riskPenalty, highControl);
            boolean isTopOfTopic = nz(m.getConsecutive()) >= topicTop.getOrDefault(topic(m), 0);
            NodeEvent node = nodeStocks.get(m.getCode());
            double nodePart = nodePart(node, cfg.getNodeTypeWeights());
            double boardPart = maxBoard > 0 ? (double) nz(m.getConsecutive()) / maxBoard : 0;
            boolean early = isEarlySeal(m.getFirstSealTime());

            double score = weightedScore(cfg, boardPart, isTopOfTopic, nodePart, early, riskPenalty);
            scores.add(score);
            maxScore = Math.max(maxScore, score);

            CandidateStock c = new CandidateStock();
            c.setRunId(runId);
            c.setStrategyId(strategyId);
            c.setTradeDate(tradeDate);
            c.setCode(m.getCode());
            c.setName(m.getName());
            c.setBoard(m.getConsecutive());
            c.setTopic(topic(m));
            c.setConceptsJson("[\"" + topic(m) + "\"]");
            c.setHitPrinciplesJson("[\"POSITION\"]");
            c.setPositionType(topic(m) + (isTopOfTopic ? " 最高板" : " " + nz(m.getConsecutive()) + " 板"));
            c.setScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
            c.setRiskFlag(riskFlag);
            c.setFilterDetailJson(filterDetail(m, cfg, fbMv, highControl, temperature, tempScale,
                    scoreDetail(cfg, m, maxBoard, boardPart, isTopOfTopic, node, nodePart,
                            early, riskPenalty, riskFlag, score)));
            c.setCreatedAt(LocalDateTime.now());
            rows.add(c);
        }

        // 建议仓位：等权基准 × (分数/最高分) × 温度系数，带风险标记的再折半
        for (int i = 0; i < rows.size(); i++) {
            CandidateStock c = rows.get(i);
            if (tempScale == null) {
                // 当日没有情绪温度记录：仓位置空，不按 0 处理（PRD §13 的 NO_TEMPERATURE 分支）。
                // 行本身照常产出——温度只是仓位的调节项，不是候选池的前置条件。
                continue;
            }
            double ratio = maxScore > 0 ? scores.get(i) / maxScore : 1.0;
            if (ratio <= 0) {
                // 分数为负说明风险扣分吃掉了全部加成，给个地板而不是 0——
                // 仓位 0 在界面上和「没选中」长得一样，容易误读。
                ratio = 0.1;
            }
            double w = cfg.getMaxPositionPerStock() * ratio * tempScale;
            if (c.getRiskFlag() != null) {
                w = w * 0.5;
            }
            c.setSuggestPosition(BigDecimal.valueOf(Math.min(w, cfg.getMaxPositionPerStock()))
                    .setScale(4, RoundingMode.HALF_UP));
        }

        // 展示顺序由 sort_by 决定，并列时一律比连板数降序
        rows.sort(rowComparator(cfg, picked));
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setRankNo(i + 1);
        }
        return rows;
    }

    /**
     * 清单排序。默认按封单强度降序——在「T 日涨停价买入」这个真实买点口径下，
     * 它是唯一一个跨档严格单调的正向因子（+1.71/+3.55/+4.37/+5.40/+7.56%）。
     *
     * <p>未实现的取值（weighted_score / principle_count）退化为默认排序。
     */
    private Comparator<CandidateStock> rowComparator(WaveRiderConfig cfg, List<MarketStock> picked) {
        if (WaveRiderConfig.SORT_EXECUTABILITY.equals(cfg.getSortBy())) {
            // 可执行性：封单比升序（越低越可能买得到）。对应「D+1 开盘再接」的备选打法。
            return Comparator
                    .comparingDouble((CandidateStock c) -> executabilityKey(c, picked))
                    .thenComparing(c -> nz(c.getBoard()), Comparator.reverseOrder());
        }
        // 默认 SORT_SEAL_STRENGTH：封单比降序（越强越靠前）
        return Comparator
                .comparingDouble((CandidateStock c) -> -sealStrengthKey(c, picked))
                .thenComparing(c -> nz(c.getBoard()), Comparator.reverseOrder());
    }

    /** 排序键：封单比。缺值给 -1（封单比非负），降序时自然落到最后。 */
    private double sealStrengthKey(CandidateStock c, List<MarketStock> picked) {
        for (MarketStock m : picked) {
            if (m.getCode().equals(c.getCode())) {
                Double r = sealRatio(m);
                return r == null ? -1.0 : r;
            }
        }
        return -1.0;
    }

    /** 排序键：封单比。缺值排最后（拿不到封单数据时不敢说它好买）。 */
    private double executabilityKey(CandidateStock c, List<MarketStock> picked) {
        for (MarketStock m : picked) {
            if (m.getCode().equals(c.getCode())) {
                Double r = sealRatio(m);
                return r == null ? Double.MAX_VALUE : r;
            }
        }
        return Double.MAX_VALUE;
    }

    /** PRD §6.2 的评分公式。算出来的分只作展示与追溯，默认不参与排序（见类注释）。 */
    private double weightedScore(WaveRiderConfig cfg, double boardPart, boolean isTopOfTopic,
                                 double nodePart, boolean early, double riskPenalty) {
        Map<String, Double> w = cfg.getScoreWeights();
        return w("board", w) * boardPart
                + w("position", w) * (isTopOfTopic ? 1 : 0)
                + w("node", w) * nodePart
                + w("timing", w) * (early ? 1 : 0)
                - w("risk", w) * riskPenalty;
    }

    /**
     * 「得分是怎么来的」——界面上「得分」格悬浮看到的就是它。
     *
     * <p>分量一律由 {@link #build} 算好再传进来，这里不再重算：
     * 总分与明细一旦各算一遍，页面上的数就成了第二份真相，漂移了还查不出来。
     *
     * <p><b>只给数与原始值，不给文案</b>：中文名、单位、「为什么这项没拿到分」的解释都在前端拼，
     * 后端写死文案等于把展示层搬进引擎（同 {@code alertFlag} / {@code riskFlag} 的分工）。
     *
     * <p>历史行没有这段（老数据 filterDetailJson 里没有 score_breakdown），
     * 前端按「取不到就只显示总分」降级，重跑当日即可补齐——不回填、不猜。
     */
    private Map<String, Object> scoreDetail(WaveRiderConfig cfg, MarketStock m, int maxBoard,
                                            double boardPart, boolean isTopOfTopic, NodeEvent node,
                                            double nodePart, boolean early, double riskPenalty,
                                            String riskFlag, double score) {
        Map<String, Double> w = cfg.getScoreWeights();
        List<Map<String, Object>> terms = new ArrayList<>();

        Map<String, Object> board = term("board", w("board", w), boardPart);
        board.put("board", nz(m.getConsecutive()));
        board.put("max_board", maxBoard);
        terms.add(board);

        terms.add(term("position", w("position", w), isTopOfTopic ? 1 : 0));

        Map<String, Object> nodeTerm = term("node", w("node", w), nodePart);
        // 类型系数从哪来：null 表示它不是节点票，有类型但权重表查不到则是 0，判据见 nodePart
        nodeTerm.put("node_type", node == null ? null : node.getNodeType());
        terms.add(nodeTerm);

        Map<String, Object> timing = term("timing", w("timing", w), early ? 1 : 0);
        timing.put("first_seal_time", m.getFirstSealTime());
        terms.add(timing);

        // 风险是唯一一项负值：扣分而非加分，value 取负号
        Map<String, Object> risk = term("risk", w("risk", w), riskPenalty);
        risk.put("value", -round4(w("risk", w) * riskPenalty));
        risk.put("risk_flag", riskFlag);
        terms.add(risk);

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("terms", terms);
        d.put("max_board", maxBoard);
        // 满分 = 四项正权重之和（各项 raw 拉满时）；不含风险项，它是扣的
        d.put("ceiling", round2(w("board", w) + w("position", w) + w("node", w) + w("timing", w)));
        d.put("score", round2(score));
        return d;
    }

    /** 单项：权重 × 原始值 = 得分。 */
    private static Map<String, Object> term(String key, double weight, double raw) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("key", key);
        t.put("weight", round4(weight));
        t.put("raw", round4(raw));
        t.put("value", round4(weight * raw));
        return t;
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 节点分＝PRD §6.2 的 {@code node_type_weight}：启动 1.0／切换 0.8／分歧 0.6，不是节点票则 0。
     *
     * <p><b>没录类型的节点按 1.0 计</b>——这一条是明知偏离字面公式后的选择：{@code node_type}
     * 现存 6 条全是 NULL（自动识别 PRD §5 没实现，节点追踪表单也不给选类型），照字面只认类型
     * 就等于这一项永远拿 0，跟没接一样。而「它是这条节点的节点票」这件事本身是真的，
     * 只是还没归到某个类型上，所以缺类型不惩罚。
     *
     * <p>反过来，<b>有类型但权重表里查不到 → 0 分</b>：{@code node_type_weights} 是 PRD 那三个值
     * 的闭集（配置校验还逼着键必须大写），拼错或新加类型时宁可给 0，也不替它猜一个权重。
     */
    static double nodePart(NodeEvent node, Map<String, Double> typeWeights) {
        if (node == null) {
            return 0;
        }
        if (node.getNodeType() == null) {
            return 1.0;
        }
        Double w = typeWeights == null ? null : typeWeights.get(node.getNodeType());
        return w == null ? 0 : w;
    }

    /**
     * 高控庄票的扣分系数：普通风险项的两倍。
     *
     * <p>和 YIZI_THIN 扣一样的分说不通——缩量独走连板＋首板日小市值＋一字锁死是「庄在里头」，
     * 崩起来没有承接；缩量一字只是流动性差。判据更狠，扣分也得跟着更狠。
     *
     * <p>取 2 而不是别的数：整数倍好念（界面上就是「按两倍扣」），且在现行权重下
     * （W_risk=0.20、满分 1.10）扣掉 0.40、约占满分 36%，狠但还不至于把命中项全压成负分。
     */
    static final double HIGH_CONTROL_PENALTY = 2;

    /**
     * 风险扣分系数，实际扣分 = W_risk × 本系数。
     *
     * <p>普通风险项（缩量一字 / 换手&gt;30%）各记 1、封顶 1；
     * 高控庄票单独一档，直接取 {@link #HIGH_CONTROL_PENALTY}。
     *
     * <p>YIZI_THIN 的口径按 PRD：换手 &lt; 2% 且成交额低于下限——这是「缩量一字」，
     * 与「封单锁死」不是一回事：后者是买不进（出池），前者是流动性差（保留但折算仓位）。
     *
     * <p>HIGH_CONTROL 的判据见 {@link HighControlStock}：连板≥2 + 换手&lt;5% +
     * 首板日流通市值≤35亿 + 一字锁死。它要求换手&lt;5%，与「换手&gt;30%」互斥，
     * 所以命中即走高档、不参与下面两条的相加与封顶。
     *
     * <p>系数可以 &gt;1，得分因此可能为负。建议仓位那侧已有地板（{@code ratio<=0} 取 0.1），
     * 不会算出负仓位。
     */
    static double riskPenalty(MarketStock m, WaveRiderConfig cfg, boolean highControl) {
        if (highControl) {
            return HIGH_CONTROL_PENALTY;
        }
        double p = 0;
        if (isYiziThin(m, cfg)) {
            p += 1;
        }
        if (m.getTurnoverRate() != null && m.getTurnoverRate().doubleValue() > 30) {
            p += 1;
        }
        return Math.min(p, 1);
    }

    private String riskFlag(MarketStock m, WaveRiderConfig cfg, double penalty, boolean highControl) {
        if (penalty <= 0) {
            return null;
        }
        if (highControl) {
            return CandidateStock.RISK_HIGH_CONTROL;
        }
        if (isYiziThin(m, cfg)) {
            return CandidateStock.RISK_YIZI_THIN;
        }
        return CandidateStock.RISK_HIGH_TURNOVER;
    }

    private static boolean isYiziThin(MarketStock m, WaveRiderConfig cfg) {
        boolean lowTurnover = m.getTurnoverRate() != null && m.getTurnoverRate().doubleValue() < 2;
        boolean lowAmount = m.getAmount() == null
                || m.getAmount().doubleValue() < cfg.getFilterMinAmount() * 1e8;
        return lowTurnover && lowAmount;
    }

    /** 过滤器逐条判定明细 + 得分构成。界面「点开追溯」看到的就是它。 */
    private String filterDetail(MarketStock m, WaveRiderConfig cfg,
                                BigDecimal firstBoardFloatMv, boolean highControl,
                                Double temperature, Double tempScale,
                                Map<String, Object> scoreDetail) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", m.getCode());
        d.put("board", m.getConsecutive());
        d.put("amount_yi", yi(m.getAmount()));
        d.put("seal_amount_yi", yi(m.getSealAmount()));
        d.put("seal_ratio", sealRatio(m));
        d.put("seal_lock_ratio", cfg.getSealLockRatio());
        d.put("turnover_rate", m.getTurnoverRate());
        d.put("first_seal_time", m.getFirstSealTime());
        d.put("industry", m.getIndustry());
        d.put("filter_st", cfg.isFilterSt());
        d.put("filter_min_amount_yi", cfg.getFilterMinAmount());
        d.put("entry_gap_max", cfg.getEntryGapMax());
        d.put("position_mode", cfg.getPositionMode());
        d.put("first_board_float_mv_yi", firstBoardFloatMv == null ? null : yi(firstBoardFloatMv));
        d.put("high_control", highControl);
        d.put("temperature", temperature);
        d.put("temperature_scale", tempScale);
        d.put("score_breakdown", scoreDetail);
        return toJson(d);
    }

    // ------------------------------------------------------------------ T+1 补写

    /**
     * 围绕 {@code tradeDate} 把能补的 T+1 都补一遍，返回补写的行数。
     *
     * <p>补两笔：{@code tradeDate} 的前一交易日（它的 T+1 就是这一天），
     * 以及 {@code tradeDate} 自己（只有它的 T+1 已经入库才补得动——重跑历史日期时才是事实）。
     * 手动运行不经定时任务，没有那个「先补昨日账」的前置步骤，这个方法是给它补上同款语义。
     *
     * <p>单只票的行情请求在这台机器上会偶发超时，所以这里<strong>故意不开事务</strong>：
     * 一笔补挂了就记一笔日志继续往下，既不把运行本身带下水，
     * 也不让第 14 只票的超时把前 13 只已经写好的行回滚掉——留着的缺口下次运行或 {@code /backfill} 会再补。
     */
    public int backfillAround(Long strategyId, LocalDate tradeDate) {
        int n = 0;
        LocalDate prev = marketStockMapper.prevDetailDate(tradeDate);
        for (LocalDate d : new LocalDate[]{prev, tradeDate}) {
            if (d == null) {
                continue;
            }
            try {
                n += backfillT1(strategyId, d);
            } catch (RuntimeException e) {
                log.warn("策略[{}]补写 {} 的 T+1 失败：{}", strategyId, d, e.toString());
            }
        }
        return n;
    }

    /**
     * 补写候选票在 D+1 的实际表现。
     *
     * <p>做成「跑当日选股前的前置步骤」而不是独立任务：漏跑一天的时候，
     * 下一次运行会自己把前一天的账补上，不需要人去数漏了哪天。
     *
     * <p>取价走 {@link DailyBarService}（落库缓存）：拉 [D, D+1] 两根<strong>同源前复权</strong> K 线，
     * 用 close(D) 与 open(D+1) 算跳空。相邻两日共享同一个复权因子，比值里会互相抵消，
     * 所以除权日是安全的；但绝对价不落进任何一个字段——{@code t1Open} 存的是当日真实开盘价，
     * 不参与跨日比较。
     *
     * @return 补写的行数
     */
    @Transactional
    public int backfillT1(Long strategyId, LocalDate tradeDate) {
        List<CandidateStock> cands = candidateMapper.listOfDay(strategyId, tradeDate);
        if (cands.isEmpty()) {
            return 0;
        }
        LocalDate t1Date = marketStockMapper.nextDetailDate(tradeDate);
        if (t1Date == null) {
            return 0;
        }
        Map<String, MarketStock> nextPool = new HashMap<>();
        List<MarketStock> nextRows = marketStockMapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, t1Date));
        for (MarketStock m : nextRows) {
            nextPool.put(m.getCode(), m);
        }

        int n = 0;
        for (CandidateStock c : cands) {
            try {
                if (upsertT1(strategyId, tradeDate, t1Date, c, nextPool.get(c.getCode()))) {
                    n++;
                }
            } catch (RuntimeException e) {
                // 单只票取数失败不该拖垮整批：留着下次补
                log.warn("补写 T+1 失败 code={} date={} 原因={}", c.getCode(), tradeDate, e.toString());
            }
        }
        return n;
    }

    private boolean upsertT1(Long strategyId, LocalDate tradeDate, LocalDate t1Date,
                             CandidateStock c, MarketStock next) {
        List<TencentClient.DayBar> bars = dailyBarService.bars(symbol(c.getCode()), tradeDate, t1Date);
        BigDecimal closeD = null;
        BigDecimal openT1 = null;
        BigDecimal closeT1 = null;
        BigDecimal highT1 = null;
        for (TencentClient.DayBar b : bars) {
            if (tradeDate.equals(b.getDate())) {
                closeD = b.getClose();
            } else if (t1Date.equals(b.getDate())) {
                openT1 = b.getOpen();
                closeT1 = b.getClose();
                highT1 = b.getHigh();
            }
        }
        if (closeD == null || openT1 == null) {
            return false;
        }

        CandidateT1 row = t1Mapper.selectOne(new LambdaQueryWrapper<CandidateT1>()
                .eq(CandidateT1::getStrategyId, strategyId)
                .eq(CandidateT1::getTradeDate, tradeDate)
                .eq(CandidateT1::getCode, c.getCode())
                .last("LIMIT 1"));
        boolean isNew = row == null;
        if (isNew) {
            row = new CandidateT1();
            row.setStrategyId(strategyId);
            row.setTradeDate(tradeDate);
            row.setCode(c.getCode());
        }
        row.setBoard(c.getBoard());
        row.setT1Date(t1Date);
        row.setT1Open(openT1);
        row.setGapPct(pct(openT1, closeD));
        row.setT1ChangePct(closeT1 == null ? null : pct(closeT1, closeD));
        row.setMaxChg(highT1 == null ? null : pct(highT1, closeD));
        if (next != null) {
            row.setT1Pool(next.getPool());
            int dBoard = c.getBoard() == null ? 0 : c.getBoard();
            int t1Board = next.getConsecutive() == null ? 0 : next.getConsecutive();
            boolean promoted = MarketStock.POOL_LIMIT_UP.equals(next.getPool()) && t1Board == dBoard + 1;
            row.setPromoted(promoted ? 1 : 0);
        } else {
            row.setT1Pool(null);
            row.setPromoted(0);
        }
        row.setUpdatedAt(LocalDateTime.now());
        if (isNew) {
            t1Mapper.insert(row);
        } else {
            t1Mapper.updateById(row);
        }
        return true;
    }

    // ------------------------------------------------------------------ 工具

    private Map<String, Stock> loadDict(List<MarketStock> rows) {
        Set<String> codes = new HashSet<>();
        for (MarketStock m : rows) {
            codes.add(m.getCode());
        }
        Map<String, Stock> out = new HashMap<>();
        if (codes.isEmpty()) {
            return out;
        }
        // t_stock 可能整张是空的（新库没同步过字典）——这里只用来判次新，拿不到就跳过判断，
        // 不因为字典缺失把整批候选拦下。
        List<Stock> found = stockMapper.selectList(new LambdaQueryWrapper<Stock>()
                .in(Stock::getCode, codes));
        for (Stock s : found) {
            out.put(s.getCode(), s);
        }
        return out;
    }

    private int countNodes(LocalDate date) {
        List<NodeDetect> nodes = nodeDetectMapper.listEffectiveOn(date);
        return nodes == null ? 0 : nodes.size();
    }

    private List<MarketStock> keep(List<MarketStock> rows, Predicate<MarketStock> p) {
        List<MarketStock> out = new ArrayList<>();
        for (MarketStock m : rows) {
            if (p.test(m)) {
                out.add(m);
            }
        }
        return out;
    }

    /** 只为了少引一个 java.util.function 的 import。 */
    private interface Predicate<T> {
        boolean test(T t);
    }

    private String topic(MarketStock m) {
        return m.getIndustry() == null || m.getIndustry().trim().isEmpty() ? "未分类" : m.getIndustry().trim();
    }

    private boolean isSt(String name) {
        String up = name.toUpperCase().replace(" ", "");
        return up.contains("ST");
    }

    private boolean isEarlySeal(Integer hhmmss) {
        if (hhmmss == null) {
            return false;
        }
        // first_seal_time 是 5 位/6 位混存（94536 = 9:45:36，104156 = 10:41:56），先补齐再比较
        String s = String.format("%06d", hhmmss);
        try {
            return Integer.parseInt(s.substring(0, 4)) <= EARLY_SEAL_HHMM;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 封单额 ÷ 成交额。缺任一侧返回 null——不猜。 */
    private Double sealRatio(MarketStock m) {
        if (m.getSealAmount() == null || m.getAmount() == null
                || m.getAmount().compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return m.getSealAmount().divide(m.getAmount(), 6, RoundingMode.HALF_UP).doubleValue();
    }

    private BigDecimal pct(BigDecimal now, BigDecimal base) {
        if (now == null || base == null || base.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return now.subtract(base).multiply(BigDecimal.valueOf(100))
                .divide(base, 2, RoundingMode.HALF_UP);
    }

    private Double yi(BigDecimal yuan) {
        return yuan == null ? null : BigDecimal.valueOf(yuan.doubleValue() / 1e8)
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /**
     * 腾讯行情用的带市场前缀代码。
     *
     * <p>委托 {@link TencentClient#symbolOf(String)}——前缀口径只该有一处。这里原本自带一套
     * "6/9 沪、4/8 北、其余深" 的判断，把 <b>920xxx</b>（北交所新号段，首字符同样是 9）
     * 错判成 sh：腾讯查无此票、<b>静默返回空</b>，表现成"这只票取不到价"，而不是报错——
     * 回填 t_zt_perf 时就是这么丢掉了一批北交所票的。
     */
    public static String symbol(String code) {
        String sym = TencentClient.symbolOf(code);
        return sym == null ? code : sym;
    }

    private double w(String key, Map<String, Double> map) {
        Double v = map == null ? null : map.get(key);
        return v == null ? 0 : v;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("JSON 序列化失败 {}", e.toString());
            return null;
        }
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
