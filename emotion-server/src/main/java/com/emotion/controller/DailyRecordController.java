package com.emotion.controller;

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

@RestController
@RequestMapping("/api/records")
public class DailyRecordController {

    private final DailyRecordService dailyRecordService;
    private final CycleService cycleService;
    private final ReviewImportService reviewImportService;
    private final ReviewExportService reviewExportService;
    private final ReviewLedgerService reviewLedgerService;

    public DailyRecordController(DailyRecordService dailyRecordService,
                                 CycleService cycleService,
                                 ReviewImportService reviewImportService,
                                 ReviewExportService reviewExportService,
                                 ReviewLedgerService reviewLedgerService) {
        this.dailyRecordService = dailyRecordService;
        this.cycleService = cycleService;
        this.reviewImportService = reviewImportService;
        this.reviewExportService = reviewExportService;
        this.reviewLedgerService = reviewLedgerService;
    }

    @PostMapping
    public ApiResponse<DailyRecord> create(Authentication auth,
                                           @RequestBody DailyRecordRequest req) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(dailyRecordService.createOrUpdate(userId, req));
    }

    @PutMapping("/{id}")
    public ApiResponse<DailyRecord> update(Authentication auth,
                                           @PathVariable Long id,
                                           @RequestBody DailyRecordRequest req) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(dailyRecordService.createOrUpdate(userId, req));
    }

    @GetMapping("/today")
    public ApiResponse<DailyRecord> getToday(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        DailyRecord record = dailyRecordService.getToday(userId);
        return ApiResponse.ok(record);
    }

    @GetMapping("/date/{date}")
    public ApiResponse<DailyRecord> getByDate(Authentication auth,
                                              @PathVariable String date) {
        Long userId = (Long) auth.getPrincipal();
        LocalDate d = LocalDate.parse(date);
        return ApiResponse.ok(dailyRecordService.getByDate(userId, d));
    }

    @GetMapping("/range")
    public ApiResponse<List<DailyRecord>> getRange(Authentication auth,
                                                   @RequestParam String start,
                                                   @RequestParam String end) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(dailyRecordService.getRange(userId,
                LocalDate.parse(start), LocalDate.parse(end)));
    }

    @GetMapping("/latest")
    public ApiResponse<List<DailyRecord>> getLatest(Authentication auth,
                                                    @RequestParam(defaultValue = "20") int days) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(dailyRecordService.getLatest(userId, days));
    }

    @GetMapping("/advice")
    public ApiResponse<StageAdviceVO> getAdvice(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        DailyRecord today = dailyRecordService.getToday(userId);
        if (today == null || isBlank(today.getStage())) {
            // 当日没复盘时回落到最近一条，和仪表盘头部卡片保持同一口径
            List<DailyRecord> latest = dailyRecordService.getLatest(userId, 1);
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
    public ApiResponse<DailyRecord> recalc(Authentication auth, @RequestParam String date) {
        Long userId = (Long) auth.getPrincipal();
        LocalDate day = parse(date);
        DailyRecord record = dailyRecordService.recalc(userId, day);
        if (record == null) {
            throw new IllegalArgumentException(day + " 那天没有复盘记录：重算只改派生列，不会替你新建一条");
        }
        return ApiResponse.ok(record);
    }

    /** 打分口径一改就要整体重跑。人工填的那十三项原样不动，所以不需要重新提交复盘。 */
    @PostMapping("/recalc-all")
    public ApiResponse<Integer> recalcAll(Authentication auth) {
        return ApiResponse.ok(dailyRecordService.recalcAll((Long) auth.getPrincipal()));
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
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(reviewImportService.importDoc(userId, req.getContent(), req.isConfirm()));
    }

    /**
     * 那天存过的原文，逐字取回。没存过就退化成按库里数据重建——导出接口没有理由因为"没导过"而红脸。
     *
     * <p>返回 VO 不返回裸字符串：{@code warnings} 和 {@code omittedKeys} 是这份文件能不能放心用
     * 的一部分，藏在日志里等于没有。
     */
    @GetMapping("/import/export")
    public ApiResponse<ReviewExportVO> exportReviewMd(Authentication auth, @RequestParam String date) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(reviewExportService.export(userId, parse(date)));
    }

    /** 按库里当前的值重建一份可导入的 md。写今天的复盘用这个：系统那些数不用手抄。 */
    @GetMapping("/import/template")
    public ApiResponse<ReviewExportVO> templateReviewMd(Authentication auth, @RequestParam String date) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(reviewExportService.template(userId, parse(date)));
    }

    /**
     * 每日复盘页「导出复盘文档」：把那天库里的系统取数按用户手写版式排成只读 md（【一】…【九】），
     * 供他读和补判断。跟上面那个可导入模板不是一回事——这份不带 meta、不承诺能再导入。
     */
    @GetMapping("/review-doc")
    public ApiResponse<ReviewExportVO> reviewDoc(Authentication auth, @RequestParam String date) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(reviewExportService.reviewDoc(userId, parse(date)));
    }

    /**
     * 复盘页下方那块明细：持仓 / 预判与兑现 / 指数 / 涨跌家数 / 我的仓位 / 各节判断文字。
     * 涨跌家数、我的仓位、判断文字跟着表单存（{@code PUT /{id}}），两张台账存走下面两个端点。
     */
    @GetMapping("/import/detail")
    public ApiResponse<ReviewDetailVO> importDetail(Authentication auth, @RequestParam String date) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(reviewImportService.detail(userId, parse(date)));
    }

    /**
     * 整日替换当天持仓：body 是那天<b>全部</b>行，空数组就是"这天清仓了"。
     * md 那条路表达不了清空（解析器拒收空的 {@code 持仓:} 键），所以这一格只有这个入口做得到。
     */
    @PutMapping("/positions")
    public ApiResponse<Integer> savePositions(Authentication auth,
                                              @RequestParam String date,
                                              @RequestBody List<PositionRequest> rows) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(reviewLedgerService.savePositions(userId, parse(date), rows));
    }

    /** 整日替换当天预判与对答案，PLAN + ANSWER 两批一起发、一起换（少发一种就是把它清掉）。 */
    @PutMapping("/predictions")
    public ApiResponse<Integer> savePredictions(Authentication auth,
                                                @RequestParam String date,
                                                @RequestBody List<PredictionRequest> rows) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(reviewLedgerService.savePredictions(userId, parse(date), rows));
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

    @GetMapping("/curve")
    public ApiResponse<TemperatureCurveVO> getCurve(Authentication auth,
                                                    @RequestParam(defaultValue = "20") int days) {
        Long userId = (Long) auth.getPrincipal();
        List<DailyRecord> records = dailyRecordService.getLatest(userId, days);

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
