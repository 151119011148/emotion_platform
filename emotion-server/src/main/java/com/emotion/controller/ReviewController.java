package com.emotion.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import com.emotion.dto.DailyRecordRequest;
import com.emotion.entity.DailyRecord;
import com.emotion.entity.IndexClose;
import com.emotion.entity.IndustrySnapshot;
import com.emotion.entity.MarketDaily;
import com.emotion.entity.ReviewFetch;
import com.emotion.mapper.SurveillanceMapper;
import com.emotion.market.SurveillanceKind;
import com.emotion.market.SurveillanceNotice;
import com.emotion.market.TencentClient;
import com.emotion.service.ConceptIndexService;
import com.emotion.service.DailyRecordService;
import com.emotion.service.IndexCloseStore;
import com.emotion.service.IndustrySnapshotService;
import com.emotion.service.MarketDailyStore;
import com.emotion.service.MarketDataService;
import com.emotion.service.ReviewExportService;
import com.emotion.service.ReviewFetchService;
import com.emotion.service.SurveillanceService;
import com.emotion.service.TopicHeatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.MarketStocksVO;
import com.emotion.vo.PremiumTiersVO;
import com.emotion.vo.ReviewDashboardVO;
import com.emotion.vo.ReviewExportVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 每日复盘 v2.0 的一键编排入口（PRD v2.0）：
 * <ul>
 *   <li>{@code POST /api/review/fetch}：点击「拉取行情」触发 T1-T8，SSE 流式回传逐任务进度；</li>
 *   <li>{@code GET /api/review/fetch/status}：查询最近一次编排状态；</li>
 *   <li>{@code GET /api/review/detail}：当日 D1-D5 原始数据 + 计算指标 + 得分 + 就绪度；</li>
 *   <li>{@code POST /api/review/save}：保存复盘记录（操作/持仓/明日计划/预判）；</li>
 *   <li>{@code GET /api/review/export}：导出复盘文档；</li>
 *   <li>{@code POST /api/surveillance/manual}：T7 无自动源时的人工补录入口。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewFetchService reviewFetchService;
    private final IndexCloseStore indexCloseStore;
    private final MarketDailyStore marketDailyStore;
    private final IndustrySnapshotService industrySnapshotService;
    private final MarketDataService marketDataService;
    private final DailyRecordService dailyRecordService;
    private final ReviewExportService reviewExportService;
    private final SurveillanceService surveillanceService;
    private final SurveillanceMapper surveillanceMapper;
    private final ConceptIndexService conceptIndexService;
    private final TopicHeatService topicHeatService;
    private final TencentClient tencent;
    private final ObjectMapper json;
    private final ExecutorService executor;

    public ReviewController(ReviewFetchService reviewFetchService,
                            IndexCloseStore indexCloseStore,
                            MarketDailyStore marketDailyStore,
                            IndustrySnapshotService industrySnapshotService,
                            MarketDataService marketDataService,
                            DailyRecordService dailyRecordService,
                            ReviewExportService reviewExportService,
                            SurveillanceService surveillanceService,
                            SurveillanceMapper surveillanceMapper,
                            ConceptIndexService conceptIndexService,
                            TopicHeatService topicHeatService,
                            TencentClient tencent,
                            ObjectMapper json,
                            @Qualifier("marketExecutor") ExecutorService executor) {
        this.reviewFetchService = reviewFetchService;
        this.indexCloseStore = indexCloseStore;
        this.marketDailyStore = marketDailyStore;
        this.industrySnapshotService = industrySnapshotService;
        this.marketDataService = marketDataService;
        this.dailyRecordService = dailyRecordService;
        this.reviewExportService = reviewExportService;
        this.surveillanceService = surveillanceService;
        this.surveillanceMapper = surveillanceMapper;
        this.conceptIndexService = conceptIndexService;
        this.topicHeatService = topicHeatService;
        this.tencent = tencent;
        this.json = json;
        this.executor = executor;
    }

    /** 一键拉取：T1-T8 编排 + SSE 流式进度。每个任务先发 running 再发终结状态。 */
    @PostMapping("/fetch")
    public SseEmitter fetch(Authentication auth, @RequestParam String date) {
        LocalDate d = parse(date);
        Long userId = userId(auth);
        SseEmitter emitter = new SseEmitter(0L); // 不设超时，编排结束才 complete
        executor.execute(() -> {
            try {
                reviewFetchService.runFetch(d, userId, ev -> {
                    Map<String, Object> payload = new java.util.LinkedHashMap<>();
                    payload.put("task", ev.task);
                    payload.put("status", ev.status);
                    payload.put("rows", ev.rows);
                    payload.put("msg", ev.msg);
                    try {
                        emitter.send(SseEmitter.event().name("task").data(payload));
                    } catch (Exception ignore) {
                        // SSE 端断开后不再投递，但编排仍在后台完成并落档
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** 查询某日最近一次编排状态（逐任务成功/失败/行数）。 */
    @GetMapping("/fetch/status")
    public ApiResponse<ReviewFetch> fetchStatus(@RequestParam String date) {
        return ApiResponse.ok(reviewFetchService.status(parse(date)));
    }

    /** 当日 D1-D5 分块数据 + 得分 + 就绪度。 */
    @GetMapping("/detail")
    public ApiResponse<ReviewDashboardVO> detail(Authentication auth, @RequestParam String date) {
        Long userId = userId(auth);
        LocalDate d = parse(date);
        ReviewDashboardVO vo = new ReviewDashboardVO();
        vo.setTradeDate(d);

        DailyRecord rec = dailyRecordService.viewByDate(userId, d);
        if (rec != null && rec.getId() != null) {
            ReviewDashboardVO.Score s = vo.getScore();
            s.setAvailable(true);
            s.setTotal(rec.getTotalScore());
            s.setTemperature(rec.getTemperature());
            s.setStage(rec.getStage());
            s.setStageDirection(rec.getStageDirection());
            s.setForcedEbb(rec.getForcedEbb());
            s.setForcedEbbReason(rec.getForcedEbbReason());
            s.setSignalFlags(rec.getSignalFlags());
            s.setScoredDims(rec.getScoredDims());
            s.setD1(rec.getScoreMarket());
            s.setD2(rec.getScoreThemeMain());
            s.setD3(rec.getScoreBoard());
            s.setD4(rec.getScoreFirst());
            s.setD5(rec.getScoreHigh());
        }

        // D1 大盘生态
        ReviewDashboardVO.D1 d1 = vo.getD1();
        List<IndexClose> indexes = indexCloseStore.read(d);
        d1.setIndexes(indexes);
        d1.setIndexTotal(tencent.expectedIndexCount());
        for (IndexClose row : indexes) {
            if (row.getClosePrice() != null) {
                d1.setIndexFilled(d1.getIndexFilled() + 1);
            }
        }
        d1.setDaily(marketDailyStore.getByDate(d));

        // D2 日内核心（板块快照 + 题材热度 Top5）
        ReviewDashboardVO.D2 d2 = vo.getD2();
        d2.setIndustries(industrySnapshotService.list(d));
        d2.setTopics(topicHeatService.topByZt(d, 5));

        // D3 连板生态
        vo.getD3().setPremiumTiers(marketDataService.premiumTiers(d));

        // D4 首板生态
        vo.getD4().setStocks(marketDataService.stocks(d));

        // D5 高位生态
        ReviewDashboardVO.D5 d5 = vo.getD5();
        d5.setSurveillanceCount(Math.toIntExact(surveillanceMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.emotion.entity.Surveillance>()
                        .eq(com.emotion.entity.Surveillance::getAnnDate, d))));
        d5.setSevereCount(Math.toIntExact(surveillanceMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.emotion.entity.Surveillance>()
                        .eq(com.emotion.entity.Surveillance::getAnnDate, d)
                        .in(com.emotion.entity.Surveillance::getKind,
                                SurveillanceKind.SEVERE.name(), SurveillanceKind.EXCH.name()))));

        readiness(vo);
        return ApiResponse.ok(vo);
    }

    /**
     * 全量重建「股票-概念」题材索引：遍历东财全部概念板块拉成分股后整盘重写。
     * 耗时长（约 500+ 板块逐个拉取），低频手动触发即可；概念成分变化慢，无需每次拉行情都做。
     * 返回写回的「股票-概念」行数；{@code -1} 表示上游一块概念都没拿到（保留旧索引）。
     */
    @PostMapping("/concepts/build")
    public ApiResponse<Long> buildConcepts() {
        return ApiResponse.ok(conceptIndexService.rebuild());
    }

    /** 当前「股票-概念」题材索引规模（行数），用于判断 D2 题材是否需要先建索引。 */
    @GetMapping("/concepts/status")
    public ApiResponse<Long> conceptsStatus() {
        return ApiResponse.ok(conceptIndexService.count());
    }

    /** 保存复盘记录（操作/持仓/明日计划/预判）。同一份 body 的 keySet 决定"哪一格发了"。 */
    @PostMapping("/save")
    public ApiResponse<DailyRecord> save(Authentication auth, @RequestBody Map<String, Object> raw) {
        Long userId = userId(auth);
        DailyRecordRequest req = json.convertValue(raw, DailyRecordRequest.class);
        return ApiResponse.ok(dailyRecordService.createOrUpdate(userId, req, raw.keySet()));
    }

    /** 导出复盘文档（含当日系统取数 + 复盘记录）。 */
    @GetMapping("/export")
    public ApiResponse<ReviewExportVO> export(Authentication auth, @RequestParam String date) {
        return ApiResponse.ok(reviewExportService.reviewDoc(userId(auth), parse(date)));
    }

    /**
     * T7 无自动源时的人工补录：code + kind + title，公告日取请求里的 date。
     * art_code 用人工合成键保证幂等（同一天同一票同类型只入一条）。
     */
    @PostMapping("/surveillance/manual")
    public ApiResponse<Integer> manualSurveillance(@RequestBody Map<String, Object> raw) {
        String code = str(raw, "code");
        String name = str(raw, "name");
        String kindRaw = str(raw, "kind");
        String title = str(raw, "title");
        LocalDate date = raw.get("date") == null ? LocalDate.now()
                : parse(String.valueOf(raw.get("date")));
        if (code == null || kindRaw == null) {
            return ApiResponse.error("code 与 kind(ZD/SEVERE/EXCH) 必填");
        }
        SurveillanceKind kind;
        try {
            kind = SurveillanceKind.valueOf(kindRaw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, "kind 只支持 ZD/SEVERE/EXCH");
        }
        SurveillanceNotice notice = new SurveillanceNotice();
        notice.setCode(code);
        notice.setName(name == null ? "" : name);
        notice.setAnnDate(date);
        notice.setKind(kind);
        notice.setTitle(title == null ? "" : title);
        notice.setArtCode("MANUAL." + date + "." + code + "." + kind.name());
        return ApiResponse.ok(surveillanceService.upsert(Collections.singletonList(notice)));
    }

    private static String str(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        return v == null ? null : String.valueOf(v);
    }

    // ---------- 就绪度 ----------

    private void readiness(ReviewDashboardVO vo) {
        ReviewDashboardVO.D1 d1 = vo.getD1();
        int need = d1.getIndexFilled();
        if (d1.getDaily() != null && need >= 5) {
            vo.getReadiness().put("D1", "ok");
        } else {
            vo.getReadiness().put("D1", "缺指数收盘(" + need + "/5)或全市场统计");
        }

        vo.getReadiness().put("D2", vo.getD2().getIndustries().isEmpty()
                ? "缺行业板块快照（当日无涨停池或未拉取三池）" : "ok");

        PremiumTiersVO t = vo.getD3().getPremiumTiers();
        vo.getReadiness().put("D3", (t == null || !t.isAvailable())
                ? "缺分档溢价（需 T-1 涨停池与今日表现）" : "ok");

        MarketStocksVO st = vo.getD4().getStocks();
        vo.getReadiness().put("D4", (st == null || !st.isAvailable())
                ? "缺当日三池明细" : "ok");

        int surv = vo.getD5().getSurveillanceCount();
        vo.getReadiness().put("D5", surv == 0
                ? "监管数据缺失，请人工补录（T7 无自动源）" : "ok");
    }

    private static Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    private static LocalDate parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new com.emotion.market.MarketDataException("必须提供 date（yyyy-MM-dd）");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new com.emotion.market.MarketDataException("日期格式应为 yyyy-MM-dd，收到：" + raw);
        }
    }
}