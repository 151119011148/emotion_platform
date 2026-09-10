package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.DailyRecord;
import com.emotion.mapper.DailyRecordMapper;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.market.PoolCounts;
import com.emotion.util.ManualOverride;
import com.emotion.util.ScoreInputs;
import com.emotion.util.TemperatureCalculator;
import com.emotion.vo.AnchorVO;
import com.emotion.vo.ScoreContextVO;
import com.emotion.vo.SurveillanceVO;

/**
 * 组装打分需要的公开输入：档位溢价（{@code t_premium_tier}）、盘面三池家数
 * （{@code t_market_stock} 一条聚合）、阵眼（{@code t_anchor} + 日 K）、
 * 监管名单（{@code t_surveillance} + 日 K）。
 *
 * <p>四样都是"每日公开事实"，跟谁复盘无关，所以谁存那天都该拿到同一个分。其中只有阵眼和监管名单
 * 两样要打网络，而 {@link com.emotion.util.TemperatureCalculator} 一行网络代码都没有——
 * 取数集中在这里，打分才能继续用数组单测。
 *
 * <p>公开输入取齐之后，最后一步把调用方那一行的<b>人工覆盖列</b>叠上去（{@link #applyManual}）：
 * 公开事实负责"谁拿到的是同一个分"，人工列负责"但那天我看到的不是这个数"。
 *
 * <p>取不到一律留 null 并把原因写进依据串，绝不兜成 0：那等于把"上游抖了一下"记成"今天确认极差"。
 * 一次复盘因此最多打 1 次档位表读 + 1 次家数聚合 + 每只阵眼 1 次日 K + 1 次日历日 K + 每只在列监管股 1 次日 K。
 */
@Service
public class ScoreContextService {

    private static final Logger log = LoggerFactory.getLogger(ScoreContextService.class);

    /** 不传日期时的"今天"按北京时间切：JVM 默认时区在别的机器上会算错交易日。 */
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    /**
     * 两列各自的宽度，留 5 字缓冲给依据串后面可能追加的标记。
     * 以前是一个 NOTE_MAX=200 通吃，surv_note 那 300 字就这么被静默砍掉了。
     */
    private static final int ANCHOR_NOTE_MAX = 295;
    private static final int SURV_NOTE_MAX = 495;

    private final PremiumTierStore premiumTierStore;
    private final MarketStockMapper marketStockMapper;
    private final AnchorMetricsService anchors;
    private final SurveillanceService surveillance;
    private final ScoringModelStore scoringModelStore;
    private final LadderMetricsService ladderMetrics;
    private final DailyRecordMapper dailyRecordMapper;

    public ScoreContextService(PremiumTierStore premiumTierStore,
                              MarketStockMapper marketStockMapper,
                              AnchorMetricsService anchors,
                              SurveillanceService surveillance,
                              ScoringModelStore scoringModelStore,
                              LadderMetricsService ladderMetrics,
                              DailyRecordMapper dailyRecordMapper) {
        this.premiumTierStore = premiumTierStore;
        this.marketStockMapper = marketStockMapper;
        this.anchors = anchors;
        this.surveillance = surveillance;
        this.scoringModelStore = scoringModelStore;
        this.ladderMetrics = ladderMetrics;
        this.dailyRecordMapper = dailyRecordMapper;
    }

    public ScoreInputs forDate(Long userId, LocalDate date, DailyRecord record) {
        ScoreInputs in = ScoreInputs.empty();
        // 注册表驱动的维集合与权重：取当前生效模型快照（读不到=null，引擎退回内置默认）。
        in.setScoringModel(scoringModelStore.activeOrNull());
        in.setPremiumTiers(premiumTierStore.read(date));
        fillPoolCounts(in, date);
        fillAnchor(in, userId, date);
        fillSurvival(in, date);
        applyManual(in, record);

        // 五维双层模型：配置树 + 自动取数读数 + 人工列叠加。
        // 树读不到时 BoardScoreCalculator.builtinTree() 兜底；metrics 只填有值的键，缺失键=该子未评（引擎按已评权重归一化）。
        in.setScoringTree(scoringModelStore.activeTreeOrNull());
        Map<String, BigDecimal> metrics = in.getMetrics();
        try {
            List<DailyRecord> recent = loadRecentForTurnover(userId, date);
            Map<String, BigDecimal> auto = ladderMetrics.build(date, record, recent);
            for (Map.Entry<String, BigDecimal> e : auto.entrySet()) {
                metrics.put(e.getKey(), e.getValue());
            }
        } catch (RuntimeException e) {
            log.warn("五维自动取数失败 date={} 原因={}（对应子指标未评，不兜 0）", date, e.toString());
        }
        applyManualMetrics(in, record);
        return in;
    }

    /** 量能基准窗口用：日之前最多 20 条同账号记录（含周末/停牌占位）。异常一律吞成空表，量能子未评。 */
    private List<DailyRecord> loadRecentForTurnover(Long userId, LocalDate date) {
        if (userId == null || date == null) {
            return Collections.emptyList();
        }
        try {
            return dailyRecordMapper.selectList(new LambdaQueryWrapper<DailyRecord>()
                    .eq(DailyRecord::getUserId, userId)
                    .lt(DailyRecord::getTradeDate, date)
                    .orderByDesc(DailyRecord::getTradeDate)
                    .last("LIMIT 20"));
        } catch (RuntimeException e) {
            log.warn("取近 20 日记录失败 user={} date={} 原因={}（量能子未评）", userId, date, e.toString());
            return Collections.emptyList();
        }
    }

    /**
     * 复盘页那块「子项读数」：把合并维拆开，每个子项各带自动值与算式。
     *
     * <p>传进去的 record 是 {@code null}，因此这里只有公开读数——他手改的那几格从表单来，
     * 不经过这条响应。少这一层，前端就没有"拉一次行情把手改覆盖成自动值"的路径可走。
     */
    public ScoreContextVO scoreContextVO(Long userId, LocalDate requestedDate) {
        LocalDate date = requestedDate != null ? requestedDate : LocalDate.now(CN);
        long began = System.currentTimeMillis();
        ScoreInputs in = forDate(userId, date, null);

        ScoreContextVO vo = new ScoreContextVO();
        vo.setTradeDate(date);
        PoolCounts pools = in.getPoolCounts();
        vo.setZtCount(pools == null ? null : pools.getZtCount());
        vo.setZbCount(pools == null ? null : pools.getZbCount());
        vo.setResealCount(pools == null ? null : pools.getResealCount());
        // record 给 null，炸板率那一支整支缺席，只剩两个家数口径——正是这一屏要摊开的两条
        TemperatureCalculator.BrokenDim broken = TemperatureCalculator.calcBrokenDim(null, in);
        vo.setSealedHomeRate(broken.getSealedHomeRate());
        vo.setResealRate(broken.getResealRate());
        vo.setSealedNote(broken.getSealedNote());
        vo.setResealNote(broken.getResealNote());
        vo.setAnchorScore(in.getAnchorScore());
        vo.setAnchorNote(in.getAnchorNote());
        vo.setSurvCount(in.getSurvCount());
        vo.setSurvPremium(in.getSurvPremium());
        vo.setSurvNote(in.getSurvNote());
        vo.setElapsedMs(System.currentTimeMillis() - began);
        return vo;
    }

    /**
     * 把这一行的人工覆盖叠到刚取出来的公开读数上。这里只管第 8/9 维：
     * 它们的人工值替换的就是"进分的那个数"本身（一个分、一组家数与均值），一换全换，
     * 叠在输入层最干净。
     *
     * <p>第 2/4 维不在这里叠，原因在 {@code TemperatureCalculator#calcBrokenDim} 那段注释上：
     * 那两维的人工值是"组均涨幅"和"家数百分数"，跟公开表里的逐档行、逐池家数不是同一个量，
     * 在这里叠就得让 {@link ScoreInputs} 同时装下家数和百分数两份能各自漂移的数。
     *
     * <p>依据串一律"人工在前、自动退成来路"：这条串是界面上唯一解释这个分从哪来的话，
     * 而自动那串的开头是"1 分｜…"，人工把分改了以后它还挂在前面，读的人只会认为系统在算错账。
     */
    static void applyManual(ScoreInputs in, DailyRecord record) {
        if (record == null) {
            return;
        }
        Integer manualAnchor = record.getManualAnchorScore();
        if (manualAnchor != null) {
            Integer score = ManualOverride.clampScore(manualAnchor);
            String head = score + " 分｜人工覆盖";
            if (!score.equals(manualAnchor)) {
                head = head + "（" + manualAnchor + " 越界，已夹到 " + score + "）";
            }
            in.setAnchorScore(score);
            in.setAnchorNote(cut(head + "｜自动值来路：" + in.getAnchorNote(), ANCHOR_NOTE_MAX));
        }

        Integer manualCount = record.getManualSurvCount();
        BigDecimal manualPremium = record.getManualSurvPremium();
        if (manualCount == null && manualPremium == null) {
            return;
        }
        Integer count = ManualOverride.pick(manualCount, in.getSurvCount());
        BigDecimal pct = ManualOverride.pick(manualPremium, in.getSurvPremium());
        in.setSurvCount(count);
        in.setSurvPremium(pct);

        StringBuilder head = new StringBuilder("第 9 维人工覆盖：进分 ");
        head.append(count == null ? "未填" : count + " 家").append("，今日溢价 ");
        head.append(pct == null ? "未填" : pct + "%");
        if (!TemperatureCalculator.survivalScored(count, pct)) {
            // 只填一格时最容易踩：溢价手改成一个负数，家数却还是 0，这一维整维没进分。
            head.append("｜家数与溢价缺一（或家数为 0）→ 这一维不进分");
        }
        in.setSurvNote(cut(head + "｜自动值来路：" + in.getSurvNote(), SURV_NOTE_MAX));
    }

    /**
     * 五维模型下的人工列叠加：把 9 个纯人工子指标的 manual_* 值直接写进 metrics 对应键。
     *
     * <p>null 时<b>不写</b>（保持"未评"），与旧 8/9 维 manualAnchorScore 一样绝不把 null 兜成 0。
     * 只覆盖列出的 9 项——板块涨停/板块溢价/梯队完整性/持续性/极高位换手/首板溢价/
     * 首板封板率/极高位断板/阵眼监管折扣，其他自动读数走 LadderMetricsService 默认值。
     *
     * <p>这九条在 {@code main/java} 里都没有别的生产者，所以这里少叠一条，界面上那条就是恒未评：
     * 首板封板率缺 → D4 少一个 0.15 的子；{@code top_high_break} 缺 → 强制退潮条件 4 永远差一道闸门；
     * 监管折扣缺 → D5 的状态分永远不打折。
     */
    static void applyManualMetrics(ScoreInputs in, DailyRecord record) {
        if (record == null) {
            return;
        }
        Map<String, BigDecimal> metrics = in.getMetrics();
        Integer lu = record.getManualSectorLimitUpCount();
        if (lu != null) {
            metrics.put("sector_limit_up_count", BigDecimal.valueOf(lu));
            in.getMetricNotes().put("sector_limit_up_count", "人工覆盖：主线板块涨停 " + lu + " 家");
        }
        overlayDecimal(metrics, in.getMetricNotes(), "sector_premium_pct", record.getManualSectorPremiumPct(),
                "人工覆盖：主线板块今日均溢价 ", "%");
        overlayDecimal(metrics, in.getMetricNotes(), "ladder_complete_score", record.getManualLadderCompleteScore(),
                "人工覆盖：梯队完整性 ", " 分");
        Integer pd = record.getManualThemePersistenceDays();
        if (pd != null) {
            metrics.put("persistence_days", BigDecimal.valueOf(pd));
            in.getMetricNotes().put("persistence_days", "人工覆盖：主线连续活跃 " + pd + " 日");
        }
        overlayDecimal(metrics, in.getMetricNotes(), "top_high_turnover_pct", record.getManualTopHighTurnoverPct(),
                "人工覆盖：极高位龙头当日换手 ", "%");
        overlayDecimal(metrics, in.getMetricNotes(), "first_premium_pct", record.getManualFirstPremiumPct(),
                "人工覆盖：首板次日均溢价 ", "%");
        overlayDecimal(metrics, in.getMetricNotes(), "first_sealed_rate", record.getManualFirstSealedRate(),
                "人工覆盖：首板封住/(封住+炸) ", "%");
        Integer brk = record.getManualTopHighBreak();
        if (brk != null) {
            metrics.put("top_high_break", BigDecimal.valueOf(brk));
            in.getMetricNotes().put("top_high_break",
                    "人工覆盖：极高位爆量断板未回封 " + (brk.intValue() == 1 ? "是" : "否"));
        }
        overlayDecimal(metrics, in.getMetricNotes(), "anchor_supervision_discount",
                record.getManualAnchorSupervisionDiscount(), "人工覆盖：阵眼监管折扣 ×", "");
    }

    /** {@code unit} 由调用方给：梯队完整性是"分"、监管折扣是纯乘数，一律拼 % 会说谎。 */
    private static void overlayDecimal(Map<String, BigDecimal> metrics, Map<String, String> notes,
                                       String key, BigDecimal v, String noteHead, String unit) {
        if (v == null) {
            return;
        }
        metrics.put(key, v);
        notes.put(key, noteHead + v.toPlainString() + unit);
    }

    /**
     * 三池家数聚合。直接打 mapper 而不是绕 {@link StockPoolWriter}：那个类只承诺写，
     * 在这里读一次本地表既不打上游也不进缓存，重算多少天都只是每天一条 GROUP BY。
     */
    private void fillPoolCounts(ScoreInputs in, LocalDate date) {
        try {
            PoolCounts counts = marketStockMapper.countPools(date);
            in.setPoolCounts(counts == null || counts.isEmpty() ? null : counts);
        } catch (RuntimeException e) {
            // 留 null：第 4 维退回只看炸板率。把"读库抖了一下"写成 0% 封板率，等于凭空判一次崩盘。
            in.setPoolCounts(null);
            log.warn("盘面家数聚合失败 date={} 原因={}（第 4 维退回炸板率单子项）", date, e.toString());
        }
    }

    private void fillAnchor(ScoreInputs in, Long userId, LocalDate date) {
        try {
            AnchorVO vo = anchors.vo(userId, date);
            in.setAnchorScore(vo.getScore());
            in.setAnchorNote(cut(anchorNote(vo), ANCHOR_NOTE_MAX));
            writeAnchorMetrics(in, vo);
        } catch (RuntimeException e) {
            in.setAnchorScore(null);
            in.setAnchorNote(cut("阵眼取数异常：" + reason(e) + "（第 8 维未评，不是 0 分）", ANCHOR_NOTE_MAX));
            log.warn("第 8 维取数失败 user={} date={} 原因={}", userId, date, e.toString());
        }
    }

    /**
     * 五维·阵眼策略指标：从最差那只 item 的布尔旗标折算成 0/1，供 strategyBoardAnchor 用。
     *
     * <p>多只在位时与 9 维同：取最差。score 为 null 表示没在位阵眼或全都没行情，三旗标都不写；
     * 引擎读不到 anchor_sealed/broke/limit_down 任一键=阵眼子整支未评（不兜 0）。
     */
    static void writeAnchorMetrics(ScoreInputs in, AnchorVO vo) {
        if (vo == null || vo.getItems() == null || vo.getItems().isEmpty()) {
            return;
        }
        Integer worst = vo.getScore();
        AnchorVO.Item pick = null;
        for (AnchorVO.Item it : vo.getItems()) {
            if (!it.isAvailable()) {
                continue;
            }
            if (worst == null || (it.getScore() != null && it.getScore().equals(worst))) {
                pick = it;
                break;
            }
        }
        if (pick == null) {
            return;
        }
        Map<String, BigDecimal> metrics = in.getMetrics();
        String note = "阵眼读数：" + pick.getName() + " \u300c" + (pick.getReason() == null ? "" : pick.getReason()) + "\u300d";
        if (Boolean.TRUE.equals(pick.getCloseLimitDown()) || Boolean.TRUE.equals(pick.getTouchedLimitDown())) {
            metrics.put("anchor_limit_down", BigDecimal.ONE);
        } else if (Boolean.TRUE.equals(pick.getBrokeToday())) {
            metrics.put("anchor_broke", BigDecimal.ONE);
        } else if (pick.getPct() != null && pick.getLimitPct() != null
                && pick.getPct().compareTo(pick.getLimitPct().subtract(new BigDecimal("0.5"))) >= 0) {
            metrics.put("anchor_sealed", BigDecimal.ONE);
        }
        in.getMetricNotes().put("anchor_state", note);
    }

    /**
     * 依据串取"进分那一只"的话：多只在位时打分用的是最差的那只，卡片上必须能看到
     * 这个 0 分是谁撞出来的，否则这一维就成了一个无法追问的数。
     */
    static String anchorNote(AnchorVO vo) {
        Integer worst = vo.getScore();
        List<String> reasons = new ArrayList<>();
        if (vo.getItems() != null) {
            for (AnchorVO.Item item : vo.getItems()) {
                if (worst == null || (item.getScore() != null && item.getScore().equals(worst))) {
                    reasons.add(item.getReason());
                }
            }
        }
        String detail = reasons.isEmpty() ? vo.getNote() : join(reasons, " · ");
        return worst == null ? detail : worst + " 分｜" + detail;
    }

    private void fillSurvival(ScoreInputs in, LocalDate date) {
        // 先问库里有没有事件再决定要不要打网络：窗口内一条事件都没有，名单必然空，
        // 但那到底是"当天没票被监管"还是"这天的公告从没回补过"，只有事件表本身能回答。
        int events;
        try {
            events = surveillance.eventsInWindow(date);
        } catch (RuntimeException e) {
            in.setSurvCount(null);
            in.setSurvNote(cut("监管事件表读取异常：" + reason(e) + "（第 9 维未评）", SURV_NOTE_MAX));
            return;
        }
        if (events == 0) {
            in.setSurvCount(null);
            in.setSurvNote(cut(date + " 往前 45 天内监管事件表为空：未拉取，第 9 维不计入分母"
                    + "（不是「当天没有进分的监管股」）", SURV_NOTE_MAX));
            return;
        }
        try {
            SurveillanceVO vo = surveillance.vo(date);
            in.setSurvCount(vo.getCount());
            in.setSurvPremium(vo.getAvgPct());
            in.setSurvNote(cut(survNote(vo), SURV_NOTE_MAX));
        } catch (RuntimeException e) {
            in.setSurvCount(null);
            in.setSurvPremium(null);
            in.setSurvNote(cut("监管名单取数异常：" + reason(e) + "（第 9 维未评，不是 0 分）", SURV_NOTE_MAX));
            log.warn("第 9 维取数失败 date={} 原因={}", date, e.toString());
        }
    }

    /** 均值算式后面带上进分且剩余窗口最长的那一只，剩下的在面板里看，依据串装不下一整份名单。 */
    static String survNote(SurveillanceVO vo) {
        String text = vo.getNote();
        if (vo.getItems() != null) {
            for (SurveillanceVO.Item item : vo.getItems()) {
                if (item.isScored()) {
                    return text + " · 剩余最久 " + item.getName() + " " + item.getDescribe();
                }
            }
        }
        return text;
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

    /** 上游异常常常没有 message，退化到类名，别在依据串里留一个 "null"。 */
    private static String reason(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
