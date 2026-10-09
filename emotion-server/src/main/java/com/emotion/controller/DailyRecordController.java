package com.emotion.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.emotion.dto.DailyRecordRequest;
import com.emotion.dto.ImportRequest;
import com.emotion.dto.PredictionRequest;
import com.emotion.dto.PositionRequest;
import com.emotion.entity.DailyRecord;
import com.emotion.service.DailyRecordService;
import com.emotion.service.CycleService;
import com.emotion.service.ReviewExportService;
import com.emotion.service.ReviewImportService;
import com.emotion.service.ReviewLedgerService;
import com.emotion.util.CycleStageMachine;
import com.emotion.util.TemperatureCalculator;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.ScoreDetailVO;
import com.emotion.vo.ImportPreviewVO;
import com.emotion.vo.ReviewDetailVO;
import com.emotion.vo.ReviewExportVO;
import com.emotion.vo.StageAdviceVO;
import com.emotion.vo.TemperatureCurveVO;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 复盘记录与台账。
 *
 * <p>这里两种归属并存：{@code /positions*} 那几条是<b>持仓台账</b>，每个账号各记一套，
 * 登录态必须带上；其余（复盘行、重算、导入导出、预判、曲线）读写的都是全平台共享的一份，
 * 只有持仓那几段仍按账号取，所以导出/导入/明细这几个入口传的叫做 ledgerUserId。
 */
@RestController
@RequestMapping("/api/records")
public class DailyRecordController {

    private final DailyRecordService dailyRecordService;
    private final CycleService cycleService;
    private final ReviewImportService reviewImportService;
    private final ReviewExportService reviewExportService;
    private final ReviewLedgerService reviewLedgerService;
    private final ObjectMapper json;

    public DailyRecordController(DailyRecordService dailyRecordService,
                                 CycleService cycleService,
                                 ReviewImportService reviewImportService,
                                 ReviewExportService reviewExportService,
                                 ReviewLedgerService reviewLedgerService,
                                 ObjectMapper json) {
        this.dailyRecordService = dailyRecordService;
        this.cycleService = cycleService;
        this.reviewImportService = reviewImportService;
        this.reviewExportService = reviewExportService;
        this.reviewLedgerService = reviewLedgerService;
        this.json = json;
    }

    @PostMapping
    public ApiResponse<DailyRecord> create(@RequestBody Map<String, Object> raw) {
        return ApiResponse.ok(dailyRecordService.createOrUpdate(toRequest(raw), raw.keySet()));
    }

    @PutMapping("/{id}")
    public ApiResponse<DailyRecord> update(@PathVariable Long id,
                                           @RequestBody Map<String, Object> raw) {
        return ApiResponse.ok(dailyRecordService.createOrUpdate(toRequest(raw), raw.keySet()));
    }

    /**
     * 同一份 body 用两遍：DTO 管类型，{@code keySet} 管「这一格你到底发没发」。
     * 后者没法从 DTO 反推——键缺席和键发 null 解出来都是 null，而 ALWAYS 列上
     * 「发 null」是清回未填、「不发」是一个字都不动，差的是整整一列数据。
     */
    private DailyRecordRequest toRequest(Map<String, Object> raw) {
        return json.convertValue(raw, DailyRecordRequest.class);
    }

    @GetMapping("/today")
    public ApiResponse<DailyRecord> getToday() {
        DailyRecord record = dailyRecordService.getToday();
        return ApiResponse.ok(record);
    }

    /**
     * 取某日记录：主观复盘行为空、但全局客观日表有值时，回一份 id=null 的合成 carrier
     * （客观九数照填）。前端据此渲染行情读数，保存时走 create。
     */
    @GetMapping("/date/{date}")
    public ApiResponse<DailyRecord> getByDate(@PathVariable String date) {
        LocalDate d = LocalDate.parse(date);
        return ApiResponse.ok(dailyRecordService.viewByDate(d));
    }

    @GetMapping("/range")
    public ApiResponse<List<DailyRecord>> getRange(@RequestParam String start,
                                                   @RequestParam String end) {
        return ApiResponse.ok(dailyRecordService.getRange(
                LocalDate.parse(start), LocalDate.parse(end)));
    }

    @GetMapping("/latest")
    public ApiResponse<List<DailyRecord>> getLatest(@RequestParam(defaultValue = "20") int days) {
        return ApiResponse.ok(dailyRecordService.getLatest(days));
    }

    @GetMapping("/advice")
    public ApiResponse<StageAdviceVO> getAdvice() {
        DailyRecord today = dailyRecordService.getToday();
        if (today == null || isBlank(today.getStage())) {
            // 当日没复盘时回落到最近一条，和仪表盘头部卡片保持同一口径
            List<DailyRecord> latest = dailyRecordService.getLatest(1);
            today = latest.isEmpty() ? null : latest.get(0);
        }
        if (today != null && !isBlank(today.getStage())) {
            return ApiResponse.ok(cycleService.getAdvice(today.getStage()));
        }
        if (today != null) {
            // 有记录但阶段为空 = 缺维守卫拦下了。这里给建议等于替使用者把残值读成市场判断。
            int dims = today.getScoredDims() == null ? 0 : today.getScoredDims();
            return ApiResponse.ok(new StageAdviceVO("数据不足", "不给出建议", "-",
                    "当日只有 " + dims + " 维参与打分，少于 " + TemperatureCalculator.MIN_DIMS_FOR_STAGE + " 维不定位阶段",
                    "补齐缺失的盘面数据或主线明确度后再看这一格。缺维的日子温度只是残值的自我确认，不代表市场。"));
        }
        return ApiResponse.ok(new StageAdviceVO("未录入", "不给出建议", "-",
                "请先在复盘页录入当日数据",
                "没有当日数据时本页不代表任何市场判断，不要据此决策。"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    @PostMapping("/recalc")
    public ApiResponse<DailyRecord> recalc(@RequestParam String date) {
        LocalDate day = parse(date);
        DailyRecord record = dailyRecordService.recalc(day);
        if (record == null) {
            throw new IllegalArgumentException(day + " 那天没有复盘记录：重算只改派生列，不会替你新建一条");
        }
        return ApiResponse.ok(record);
    }

    /** 打分口径一改就要整体重跑。人工填的那十三项原样不动，所以不需要重新提交复盘。 */
    @PostMapping("/recalc-all")
    public ApiResponse<Integer> recalcAll() {
        return ApiResponse.ok(dailyRecordService.recalcAll());
    }

    /**
     * 复盘 md → 平台。{@code confirm=false} 只看差异，{@code true} 才落库并只重算那一天。
     *
     * <p>坏行<b>照样返回 200</b>：解析失败如果被 {@code GlobalExceptionHandler} 压成一条红色 toast，
     * 五处坏行会糊成一坨，而你要改的是那份 md、需要的是行号。错误在 data.errors 里，页面渲染成表。
     */
    @PostMapping("/import")
    public ApiResponse<ImportPreviewVO> importMd(Authentication auth,
                                                 @RequestBody ImportRequest req) {
        return ApiResponse.ok(reviewImportService.importDoc(userId(auth), req.getContent(), req.isConfirm()));
    }

    /**
     * 那天存过的原文，逐字取回。没存过就退化成按库里数据重建——导出接口没有理由因为"没导过"而红脸。
     *
     * <p>返回 VO 不返回裸字符串：{@code warnings} 和 {@code omittedKeys} 是这份文件能不能放心用
     * 的一部分，藏在日志里等于没有。
     */
    @GetMapping("/import/export")
    public ApiResponse<ReviewExportVO> exportReviewMd(Authentication auth, @RequestParam String date) {
        return ApiResponse.ok(reviewExportService.export(userId(auth), parse(date)));
    }

    /** 按库里当前的值重建一份可导入的 md。写今天的复盘用这个：系统那些数不用手抄。 */
    @GetMapping("/import/template")
    public ApiResponse<ReviewExportVO> templateReviewMd(Authentication auth, @RequestParam String date) {
        return ApiResponse.ok(reviewExportService.template(userId(auth), parse(date)));
    }

    /**
     * 每日复盘页「导出复盘文档」：把那天库里的系统取数按用户手写版式排成只读 md（【一】…【九】），
     * 供他读和补判断。跟上面那个可导入模板不是一回事——这份不带 meta、不承诺能再导入。
     */
    @GetMapping("/review-doc")
    public ApiResponse<ReviewExportVO> reviewDoc(Authentication auth, @RequestParam String date) {
        return ApiResponse.ok(reviewExportService.reviewDoc(userId(auth), parse(date)));
    }

    /**
     * Stage 9 只读端点：某天 5 维完整 eval 树 + 结构信号 + 原始读数快照。走引擎现算、不落库；
     * 改一 sub 权重或一 ladder 阈值 → 刷新即反映。Dashboard 卡片 tooltip 唯一数据源。
     */
    @GetMapping("/score-detail")
    public ApiResponse<ScoreDetailVO> scoreDetail(@RequestParam String date) {
        return ApiResponse.ok(dailyRecordService.scoreDetail(parse(date)));
    }

    /**
     * 复盘页下方那块明细：持仓 / 预判与兑现 / 指数 / 涨跌家数 / 我的仓位 / 对照 / 当日题材快照。
     * 这一页现在只有<b>对照</b>跟表单一起存（{@code PUT /{id}}）、<b>持仓</b>走下面的端点，其余都是只读展示；
     * 涨跌家数、我的仓位、预判与兑现的唯一作者是那天导入的复盘 md。
     */
    @GetMapping("/import/detail")
    public ApiResponse<ReviewDetailVO> importDetail(Authentication auth, @RequestParam String date) {
        return ApiResponse.ok(reviewImportService.detail(userId(auth), parse(date)));
    }

    /**
     * 整日替换当天持仓：body 是那天<b>全部</b>行，空数组就是"这天清仓了"。
     * md 那条路表达不了清空（解析器拒收空的 {@code 持仓:} 键），所以这一格只有这个入口做得到。
     */
    @PutMapping("/positions")
    public ApiResponse<Integer> savePositions(Authentication auth,
                                              @RequestParam String date,
                                              @RequestBody List<PositionRequest> rows) {
        return ApiResponse.ok(reviewLedgerService.savePositions(userId(auth), parse(date), rows));
    }

    /** 读取某日持仓台账（三条段式扩展列随行返回），日期切换时前端回填编辑表。 */
    @GetMapping("/positions")
    public ApiResponse<List<com.emotion.entity.Position>> getPositions(Authentication auth,
                                                                       @RequestParam String date) {
        return ApiResponse.ok(reviewLedgerService.readPositions(userId(auth), parse(date)));
    }

    /**
     * 外溢点①：仪表盘「待裁决」卡——最近一条未执行决策的持仓。
     * before 缺省 = 今天（历史日期仍待裁决的也能被带到，避免跨日漏办）。
     */
    @GetMapping("/positions/pending/latest")
    public ApiResponse<com.emotion.entity.Position> latestPending(Authentication auth,
                                                                  @RequestParam(required = false) String before) {
        LocalDate b = before == null ? LocalDate.now() : parse(before);
        return ApiResponse.ok(reviewLedgerService.latestPendingPosition(userId(auth), b));
    }

    /** 外溢点②：次日（T+1）复盘页顶部——最近一批未执行决策（昨日遗留），limit 缺省 5。 */
    @GetMapping("/positions/pending")
    public ApiResponse<List<com.emotion.entity.Position>> pendingPositions(Authentication auth,
                                                                           @RequestParam(required = false) String before,
                                                                           @RequestParam(defaultValue = "5") int limit) {
        LocalDate b = before == null ? LocalDate.now() : parse(before);
        return ApiResponse.ok(reviewLedgerService.pendingPositions(userId(auth), b, limit));
    }

    /** 外溢点①的痕迹行：待裁决列表空了以后，仪表盘用它显示「最近已裁决」，limit 缺省 3。 */
    @GetMapping("/positions/executed")
    public ApiResponse<List<com.emotion.entity.Position>> executedPositions(Authentication auth,
                                                                            @RequestParam(defaultValue = "3") int limit) {
        return ApiResponse.ok(reviewLedgerService.executedPositions(userId(auth), limit));
    }

    /** 标记某持仓已执行：回填真实动作，executed 置 1，闭环完成。 */
    @PostMapping("/positions/{id}/execute")
    public ApiResponse<Boolean> markExecuted(Authentication auth,
                                             @PathVariable Long id,
                                             @RequestParam(required = false) String action) {
        return ApiResponse.ok(reviewLedgerService.markPositionExecuted(userId(auth), id, action));
    }

    /**
     * 跨日全量台账（持仓与台账页）：days 缺省 365，0=全量。
     * 各日快照原样返回，生命周期与纪律统计由前端按标的聚合。
     */
    @GetMapping("/positions/all")
    public ApiResponse<List<com.emotion.entity.Position>> allPositions(Authentication auth,
                                                                       @RequestParam(required = false) Integer days) {
        return ApiResponse.ok(reviewLedgerService.allPositions(userId(auth), days == null ? 365 : days));
    }

    /**
     * 整日替换当天预判与对答案，PLAN + ANSWER 两批一起发、一起换（少发一种就是把它清掉）。
     * <b>复盘页已经没有这块编辑口</b>：这两批行现在只由那天导入的 md 写，这个入口留作同一套语义的手工口。
     */
    @PutMapping("/predictions")
    public ApiResponse<Integer> savePredictions(@RequestParam String date,
                                                @RequestBody List<PredictionRequest> rows) {
        return ApiResponse.ok(reviewLedgerService.savePredictions(parse(date), rows));
    }

    private static LocalDate parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("date 必填，格式 2026-09-04");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("date 无法解析：" + raw + "，请用 2026-09-04 这种格式");
        }
    }

    /** 持仓台账按账号各记一套，这个 id 只喂给台账与那份文档里的持仓段。 */
    private static Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    @GetMapping("/curve")
    public ApiResponse<TemperatureCurveVO> getCurve(@RequestParam(defaultValue = "20") int days) {
        List<DailyRecord> records = dailyRecordService.getLatest(days);

        TemperatureCurveVO vo = new TemperatureCurveVO();
        List<String> dates = new ArrayList<>();
        List<String> isoDates = new ArrayList<>();
        List<Double> temperatures = new ArrayList<>();
        List<String> stages = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        List<Integer> dims = new ArrayList<>();

        for (int i = records.size() - 1; i >= 0; i--) {
            DailyRecord r = records.get(i);
            dates.add(r.getTradeDate().format(DateTimeFormatter.ofPattern("MM/dd")));
            isoDates.add(r.getTradeDate().toString());
            // 没评出来就是没评出来。填 0 会在曲线上画出一个"冰点"，而冰点是要据此空仓的读数。
            temperatures.add(r.getTemperature() != null ? r.getTemperature().doubleValue() : null);
            stages.add(r.getStage() != null ? r.getStage() : "");
            labels.add(CycleStageMachine.label(r.getStage(), r.getStagePhase(), r.getStageSeq()));
            summaries.add(summary(r));
            dims.add(r.getScoredDims() != null ? r.getScoredDims() : 0);
        }

        vo.setDates(dates);
        vo.setIsoDates(isoDates);
        vo.setTemperatures(temperatures);
        vo.setStages(stages);
        vo.setLabels(labels);
        vo.setSummaries(summaries);
        vo.setDims(dims);
        return ApiResponse.ok(vo);
    }

    /** tooltip 里一行看完当日盘面，字段顺序与复盘页一致。 */
    private static String summary(DailyRecord r) {
        return "连板 " + dash(r.getMaxConsecutiveLimit())
                + " · 涨停 " + dash(r.getLimitUpCount()) + "/跌停 " + dash(r.getLimitDownCount())
                + " · 溢价 " + dash(r.getYesterdayLimitPremium(), "%")
                + " · 炸板 " + dash(r.getBrokenBoardRate(), "%")
                + " · 大面 " + dash(r.getBigLossCount())
                + " · 成交 " + dash(r.getTotalVolume(), "亿");
    }

    private static String dash(Object v) {
        return v == null ? "—" : String.valueOf(v);
    }

    private static String dash(Object v, String unit) {
        return v == null ? "—" : plain(v) + unit;
    }

    /** DECIMAL 列定标返回，42.80% / 24000.00亿 都得去掉补出来的 0。 */
    private static String plain(Object v) {
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }
}
