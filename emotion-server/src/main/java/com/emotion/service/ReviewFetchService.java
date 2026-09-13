package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.dto.MarketFields;
import com.emotion.entity.IndexClose;
import com.emotion.entity.MarketStock;
import com.emotion.entity.ReviewFetch;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.vo.MarketSnapshotVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 每日复盘「一键拉取」的 T1-T8 编排（PRD v2.0）。
 *
 * <pre>
 *   T1  交易日历校验 + T-1/T-2 三池回补检查（缺失则自动回补）
 *   T2  五大指数收盘 → t_index_close
 *   T3  全市场统计 → t_market_daily
 *   T4  三池（涨停/炸板/跌停）→ t_market_stock
 *   T5  行业板块快照 → t_industry_daily_snapshot
 *   T6  分档溢价 → t_premium_tier
 *   T7  监管异动 → t_surveillance（无自动源时降级为 warn，不强求）
 *   T8  触发五维计算回写（职责分离：只重算已存在的复盘记录，不替用户新建）
 * </pre>
 *
 * <p><b>职责分离是硬约束</b>（与 {@code MarketController} 同一哲学）：拉取只落公开原始数据，
 * 不写评分。T8 因此<b>只对已存在的复盘记录 {@code recalc}</b>——缺维时引擎的自带守卫会给出
 * "数据不足"，而不是用系统残值把一阵评的庄家判决洗白。没有记录就明确提示评分待表单保存后触发。
 *
 * <p><b>T2/T3/T4/T6 复用生产快照引擎</b>{@link MarketDataService#snapshot}：它是唯一经过
 * 363 个单测背书、同时落库三池/溢价/指数/统计的原子路径，逐任务拆到字段级会引入第二份口径。
 * 该阶段视作一个原子批次——任一步失败，四维一起标 fail，但后续 T5/T7/T8 仍然独立执行
 * （这就是 PRD 要的"部分失败不阻断"：监管缺了，板块/评分照常）。
 *
 * <p>每个任务的进度通过 {@code Consumer<Event>} 实时推给调用方（SSE），同一份也收集到
 * {@code all} 里供最后落 {@code t_review_fetch} 做状态回显。
 */
@Service
public class ReviewFetchService {

    private static final Logger log = LoggerFactory.getLogger(ReviewFetchService.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private final MarketDataService marketDataService;
    private final MarketDailyStore marketDailyStore;
    private final IndexCloseStore indexCloseStore;
    private final PremiumTierStore premiumTierStore;
    private final MarketStockMapper marketStockMapper;
    private final IndustrySnapshotService industrySnapshotService;
    private final SurveillanceService surveillanceService;
    private final DailyRecordService dailyRecordService;
    private final ReviewFetchStore fetchStore;
    private final ObjectMapper json;

    public ReviewFetchService(MarketDataService marketDataService,
                              MarketDailyStore marketDailyStore,
                              IndexCloseStore indexCloseStore,
                              PremiumTierStore premiumTierStore,
                              MarketStockMapper marketStockMapper,
                              IndustrySnapshotService industrySnapshotService,
                              SurveillanceService surveillanceService,
                              DailyRecordService dailyRecordService,
                              ReviewFetchStore fetchStore,
                              ObjectMapper json) {
        this.marketDataService = marketDataService;
        this.marketDailyStore = marketDailyStore;
        this.indexCloseStore = indexCloseStore;
        this.premiumTierStore = premiumTierStore;
        this.marketStockMapper = marketStockMapper;
        this.industrySnapshotService = industrySnapshotService;
        this.surveillanceService = surveillanceService;
        this.dailyRecordService = dailyRecordService;
        this.fetchStore = fetchStore;
        this.json = json;
    }

    /** 单个任务的实时进度片。status ∈ running/ok/warn/fail。 */
    public static class Event {
        public String task;
        public String status;
        public Integer rows;
        public String msg;

        Event(String task, String status, Integer rows, String msg) {
            this.task = task;
            this.status = status;
            this.rows = rows;
            this.msg = msg;
        }
    }

    /**
     * 跑完整 T1-T8。{@code sink} 会收到每个任务一次 "running" 起手 + 一次终结状态，
     * 末尾会收到一个汇总 "done" 事件。结束后把当日编排状态落档到 {@code t_review_fetch}。
     */
    public void runFetch(LocalDate date, Long userId, Consumer<Event> sink) {
        List<Event> all = new ArrayList<>();
        Consumer<Event> record = e -> {
            all.add(e);
            if (sink != null) {
                try {
                    sink.accept(e);
                } catch (Exception ignored) {
                    // SSE 写完即断：不能让一次写失败中断整条编排
                }
            }
        };

        boolean failed = false;
        List<String> warnings = new ArrayList<>();

        // ---------- T1 交易日历校验 + T-1/T-2 回补 ----------
        emit(record, "T1", "running", null, "交易日历校验 + T-1/T-2 三池回补检查");
        if (isWeekend(date)) {
            emit(record, "T1", "fail", null, date + " 是周末，非交易日");
            finish(date, ReviewFetch.STATE_FAILED, all, warnings);
            return;
        }
        if (date.isAfter(LocalDate.now(CN))) {
            emit(record, "T1", "fail", null, "不能拉取未来日期：" + date);
            finish(date, ReviewFetch.STATE_FAILED, all, warnings);
            return;
        }
        int backfilled = ensurePrevTwoDays(date, record);
        int before = backfilledScanSeen(date);
        if (backfilled > 0) {
            emit(record, "T1", "ok", null, "T-1/T-2 回补完成，回补 " + backfilled + " 个交易日");
        } else if (before == 0) {
            emit(record, "T1", "warn", null, "向前 7 日内没有一个可用的前一交易日涨停池，晋级/溢价可能无法计算");
            warnings.add("T-1/T-2 涨停池缺失，D3 晋级率/D4 首板溢价可能未评");
        } else {
            emit(record, "T1", "ok", null, "T-1 涨停池已就绪，无需回补");
        }

        // ---------- T2-T6：生产快照引擎一次性拉取并落库（原子批次） ----------
        boolean core = runCore(date, record, warnings);
        if (!core) {
            failed = true;
        }

        // ---------- T5 行业板块快照（独立于 T2-T6，读已落库的 t_market_stock） ----------
        ensure(record, "T5",
                () -> {
                    int n = industrySnapshotService.replaceForDate(date);
                    return n > 0 ? new int[]{n} : null;
                },
                "行业板块快照（涨停池按行业聚合）",
                "行业板块快照为空（涨停池无行业或当日无涨停池）");

        // ---------- T7 监管（无自动源时 warn，不阻断） ----------
        emit(record, "T7", "running", null, "监管异动拉取");
        try {
            List<String> targets = surveillanceService.trackedCodes(date, date);
            if (targets.isEmpty()) {
                emit(record, "T7", "warn", null, "该日无监管关注标的（无法从公告源取到），请人工补录");
                warnings.add("T7 监管无自动源，可在复盘页人工补录");
            } else {
                List<SurveillanceService.RefreshOutcome> out = surveillanceService.refresh(targets, date, date);
                long okCount = out.stream().filter(SurveillanceService.RefreshOutcome::isOk).count();
                emit(record, "T7", okCount > 0 ? "ok" : "warn",
                        (int) okCount, "监管刷新成功 " + okCount + "/" + out.size() + " 只");
                if (okCount == 0) {
                    warnings.add("T7 监管刷新无一成功，可在复盘页人工补录");
                }
            }
        } catch (Exception e) {
            log.warn("{} T7 监管拉取失败: {}", date, e.toString());
            emit(record, "T7", "warn", null, "监管拉取异常，请在复盘页人工补录");
            warnings.add("T7 监管拉取失败，可在复盘页人工补录");
        }

        // ---------- T8 触发五维计算（职责分离：只重算已存在的记录） ----------
        emit(record, "T8", "running", null, "触发五维计算（回写评分）");
        try {
            com.emotion.entity.DailyRecord rec = dailyRecordService.recalc(userId, date);
            if (rec == null) {
                emit(record, "T8", "warn", null,
                        "尚未创建复盘记录：首次评分在「保存复盘」时自动计算（拉取只落原始数据）");
                warnings.add("T8 未触发评分：该日还没有复盘记录");
            } else {
                emit(record, "T8", "ok", null, "五维已重算：D1=" + dim(rec.getScoreMarket())
                        + " D2=" + dim(rec.getScoreThemeMain()) + " D3=" + dim(rec.getScoreBoard())
                        + " D4=" + dim(rec.getScoreFirst()) + " D5=" + dim(rec.getScoreHigh()));
            }
        } catch (Exception e) {
            log.warn("{} T8 评分重算失败: {}", date, e.toString());
            emit(record, "T8", "warn", null, "五维重算失败，原始数据已就绪，可稍后重试");
            warnings.add("T8 评分重算失败");
        }

        boolean hasFail = all.stream().anyMatch(e -> "fail".equals(e.status));
        boolean hasWarn = all.stream().anyMatch(e -> "warn".equals(e.status));
        String overall = hasFail ? ReviewFetch.STATE_PARTIAL
                : hasWarn ? ReviewFetch.STATE_PARTIAL : ReviewFetch.STATE_DONE;
        if (failed && all.stream().noneMatch(e -> "ok".equals(e.status))) {
            overall = ReviewFetch.STATE_FAILED;
        }
        emit(record, "done", overall.equals(ReviewFetch.STATE_DONE) ? "ok"
                        : overall.equals(ReviewFetch.STATE_FAILED) ? "fail" : "warn",
                null, "编排完成：" + all.stream().filter(e -> "ok".equals(e.status)).count()
                        + " 个任务就绪，缺失见各块就绪度");
        finish(date, overall, all, warnings);
    }

    // ---------- T1 回补 ----------

    /** 检查并回补 T-1/T-2 的涨停池。向前最多扫 7 个自然日，命中 2 个有数据的交易日即停。 */
    private int ensurePrevTwoDays(LocalDate date, Consumer<Event> record) {
        int done = 0;
        LocalDate scan = date.minusDays(1);
        int attempts = 0;
        while (scan.isAfter(date.minusDays(8)) && done < 2 && attempts < 7) {
            attempts++;
            if (hasZtPool(scan)) {
                done++;
                scan = scan.minusDays(1);
                continue;
            }
            try {
                MarketSnapshotVO snap = marketDataService.snapshot(scan, true);
                if (snap.getTradeDate() != null && hasZtPool(scan)) {
                    done++;
                }
            } catch (Exception e) {
                log.info("{} 回补跳过（非交易日或无数据）: {}", scan, e.toString());
            }
            scan = scan.minusDays(1);
        }
        return done;
    }

    /** 仅统计（不触发回补），返回向前 7 日内已有涨停池的交易日数，用于给 T1 一个 inform 文案。 */
    private int backfilledScanSeen(LocalDate date) {
        int seen = 0;
        for (LocalDate d = date.minusDays(1); d.isAfter(date.minusDays(8)); d = d.minusDays(1)) {
            if (hasZtPool(d)) {
                seen++;
            }
        }
        return seen;
    }

    private boolean hasZtPool(LocalDate date) {
        Long n = marketStockMapper.selectCount(new LambdaQueryWrapper<MarketStock>()
                .eq(MarketStock::getTradeDate, date)
                .eq(MarketStock::getPool, MarketStock.POOL_LIMIT_UP));
        return n != null && n > 0;
    }

    // ---------- T2-T6 原子批次 ----------

    private boolean runCore(LocalDate date, Consumer<Event> record, List<String> warnings) {
        emit(record, "T2", "running", null, "五大指数收盘");
        emit(record, "T3", "running", null, "全市场统计");
        emit(record, "T4", "running", null, "三池（涨停/炸板/跌停）");
        emit(record, "T6", "running", null, "分档溢价");
        try {
            MarketSnapshotVO snap = marketDataService.snapshot(date, true);
            // /api/market/snapshot 控制器在服务层之后写 t_market_daily；这里绕开控制器直接调服务，
            // 客观九数的落库得自己补上（与控制器同一份口径）。
            writeMarketDaily(snap);

            Integer idx = closedIndexCount(date);
            emit(record, "T2", idx > 0 ? "ok" : "warn", idx, "五大指数收盘已入 " + idx + "/5");

            boolean hasDaily = marketDailyStore.getByDate(date) != null;
            emit(record, "T3", "ok", hasDaily ? 1 : 0, "全市场统计已入全局客观日表");

            Long stockRows = marketStockMapper.selectCount(new LambdaQueryWrapper<MarketStock>()
                    .eq(MarketStock::getTradeDate, date));
            emit(record, "T4", "ok", stockRows == null ? 0 : stockRows.intValue(),
                    "三池明细已入库");

            int tierRows = premiumTierStore.read(date) == null
                    ? 0 : premiumTierStore.read(date).getTiers().size();
            emit(record, "T6", tierRows > 0 ? "ok" : "warn", tierRows,
                    tierRows > 0 ? "分档溢价已写 " + tierRows + " 档" : "该日无可写档位（前一日无非首板涨停池）");
            return true;
        } catch (Exception e) {
            log.warn("{} T2-T6 盘面拉取失败: {}", date, e.toString());
            emit(record, "T2", "fail", null, "盘面拉取失败");
            emit(record, "T3", "fail", null, "盘面拉取失败");
            emit(record, "T4", "fail", null, e.toString());
            emit(record, "T6", "fail", null, "盘面拉取失败");
            warnings.add("T2-T6 盘面拉取失败：" + e.getMessage());
            return false;
        }
    }

    /** 复刻 {@code /api/market/snapshot} 控制器落区：只有快照日=请求日且取到实时家数时才写涨跌家数。 */
    private void writeMarketDaily(MarketSnapshotVO snap) {
        if (snap.getTradeDate() == null || snap.getFilled() == null) {
            return;
        }
        MarketFields f = snap.getFilled();
        boolean liveBreadth = snap.getSnapshotDate() != null
                && snap.getSnapshotDate().equals(snap.getTradeDate())
                && f.getUpCount() != null && f.getDownCount() != null;
        marketDailyStore.upsertSnapshot(snap.getTradeDate(), f, liveBreadth);
    }

    private int closedIndexCount(LocalDate date) {
        int withClose = 0;
        for (IndexClose row : indexCloseStore.read(date)) {
            if (row.getClosePrice() != null) {
                withClose++;
            }
        }
        return withClose;
    }

    // ---------- 汇总 ----------

    /** 独立任务的通用适配：返回 null = 该任务空/失败 → warn。 */
    private void ensure(Consumer<Event> record, String task, Op op, String doneMsg, String emptyMsg) {
        emit(record, task, "running", null, doneMsg);
        try {
            int[] result = op.run();
            if (result == null) {
                emit(record, task, "warn", null, emptyMsg);
            } else {
                emit(record, task, "ok", result[0], doneMsg + "：" + result[0] + " 条");
            }
        } catch (Exception e) {
            log.warn("{} 任务失败: {}", task, e.toString());
            emit(record, task, "warn", null, "执行异常，已跳过本次");
        }
    }

    private interface Op {
        int[] run() throws Exception;
    }

    private static String dim(java.math.BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    /** 落档汇总态（overall + 任务 JSON + warnings）。 */
    private void finish(LocalDate date, String overall, List<Event> all, List<String> warnings) {
        try {
            List<Map<String, Object>> plain = new ArrayList<>();
            for (Event e : all) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("task", e.task);
                m.put("status", e.status);
                m.put("rows", e.rows);
                m.put("msg", e.msg);
                plain.add(m);
            }
            String tasksJson = json.writeValueAsString(plain);
            fetchStore.save(date, overall, tasksJson, String.join("；", warnings));
        } catch (Exception e) {
            log.warn("{} 编排状态落档失败: {}", date, e.toString());
        }
    }

    private static void emit(Consumer<Event> record, String task, String status,
                             Integer rows, String msg) {
        record.accept(new Event(task, status, rows, msg));
    }

    private static boolean isWeekend(LocalDate d) {
        int day = d.getDayOfWeek().getValue();
        return day == 6 || day == 7;
    }

    /** 查询某日最近一次编排状态（前端拉取进度用）。 */
    public ReviewFetch status(LocalDate date) {
        return fetchStore.getByDate(date);
    }
}