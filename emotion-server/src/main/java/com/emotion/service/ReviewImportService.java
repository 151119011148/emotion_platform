package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.DailyRecord;
import com.emotion.entity.Position;
import com.emotion.entity.Prediction;
import com.emotion.entity.Stock;
import com.emotion.entity.Theme;
import com.emotion.mapper.StockMapper;
import com.emotion.mapper.ThemeMapper;
import com.emotion.util.ReviewDoc;
import com.emotion.util.ReviewImportParser;
import com.emotion.util.SectionNotes;
import com.emotion.vo.ImportPreviewVO;
import com.emotion.vo.ReviewDetailVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 复盘 md → 平台。一次调用做完<b>解析 → 查代码 → 拼差异 → （可选）落库</b>。
 *
 * <p>坏行<b>不抛异常</b>：{@code GlobalExceptionHandler} 会把异常压成一条红色 toast，
 * 五处坏行糊成一坨，而你要改的是那份 md，需要的是带行号的表。所以预览接口一律 200，
 * 错误在 data 里，前端渲染成表格。
 *
 * <p>差异用的是 {@link ReviewImportWriter#applySingles} 那一份映射，跑在一份不落库的副本上。
 * 这是"预览说会把主线改成 X、确认之后库里也是 X"唯一的保证。
 *
 * <p><b>已知边界</b>（都不是 bug，是这轮刻意不做的）：
 * <ul>
 *   <li>多行键只能覆盖不能清空——{@code 持仓:} 单独一行空值会被解析器判成坏行，
 *       所以"这天清仓了、一行持仓都不剩"要靠删掉那天的行没法用 md 表达。同一天重导时旧行会留着。</li>
 *   <li>题材强度没有日粒度，最后一次导入赢（见 {@link ThemeDayWriter}）。</li>
 *   <li>七个行情字段两边都不改，只并排显示。真实分歧已经存在过：9/3 你记 46/17，系统取到 44/16。</li>
 * </ul>
 */
@Service
public class ReviewImportService {

    private static final Logger log = LoggerFactory.getLogger(ReviewImportService.class);

    /** 会进分母的标量键。目前只有主线明确度（第 7 维）。 */
    private static final String KEY_SCORE_THEME = "主线明确度";
    private static final String NOTE_SCORE_THEME =
            "第 7 维。这一格改了会同时动分子和分母，温度和阶段跟着变。";
    private static final String NOTE_BREADTH =
            "只入库、只并排看广度，不进分母：03 篇把涨跌家数比列在「辅助指标（选看）」，打分表第 3 维写的是涨停 vs 跌停家数。";
    private static final String NOTE_POSITION_PCT = "你自己的仓位，平台不猜也不进分。";

    private final DailyRecordService dailyRecordService;
    private final ReviewImportWriter writer;
    private final PositionStore positionStore;
    private final PredictionStore predictionStore;
    private final IndexCloseStore indexCloseStore;
    private final StockMapper stockMapper;
    private final ThemeMapper themeMapper;

    public ReviewImportService(DailyRecordService dailyRecordService,
                               ReviewImportWriter writer,
                               PositionStore positionStore,
                               PredictionStore predictionStore,
                               IndexCloseStore indexCloseStore,
                               StockMapper stockMapper,
                               ThemeMapper themeMapper) {
        this.dailyRecordService = dailyRecordService;
        this.writer = writer;
        this.positionStore = positionStore;
        this.predictionStore = predictionStore;
        this.indexCloseStore = indexCloseStore;
        this.stockMapper = stockMapper;
        this.themeMapper = themeMapper;
    }

    /**
     * @param confirm false = 只看差异，库里一个字都不动；true = 落库并只重算那一天。
     */
    public ImportPreviewVO importDoc(Long userId, String content, boolean confirm) {
        ReviewDoc doc = ReviewImportParser.parse(content);
        Prepared prepared = prepare(userId, doc);
        ImportPreviewVO vo = prepared.preview;
        if (!confirm) {
            return vo;
        }
        if (!vo.isOkToConfirm()) {
            // 前端在这种情况下会把按钮禁掉，走到这里只能是你绕过了页面。不写，把错误原样回给你。
            log.warn("{} 导入被拒：{} 行坏数据", userId, doc.getErrors().size());
            return vo;
        }
        LocalDate date = doc.getDate();
        try {
            writer.write(userId, date, content, doc, prepared.names);
        } catch (DuplicateKeyException e) {
            // createOrUpdate/importManual 都是"先查后插"，两个窗口撞在一起就落在这条唯一键上。
            throw new IllegalArgumentException(date + " 这一天正在被别处写入，请刷新后重试", e);
        }
        return vo;
    }

    /** 预览结果 + 那份一次性算清的 代码→正名。落库必须复用同一个 map，见 {@link #prepare}。 */
    private static final class Prepared {
        private final ImportPreviewVO preview;
        private final Map<String, String> names;

        private Prepared(ImportPreviewVO preview, Map<String, String> names) {
            this.preview = preview;
            this.names = names;
        }
    }

    public ReviewDetailVO detail(Long userId, LocalDate date) {
        ReviewDetailVO vo = new ReviewDetailVO();
        vo.setDate(date);
        DailyRecord record = dailyRecordService.getByDate(userId, date);
        if (record != null) {
            vo.setUpCount(record.getUpCount());
            vo.setDownCount(record.getDownCount());
            vo.setMyPositionPct(record.getMyPositionPct());
            vo.setDocNotes(SectionNotes.fromJson(record.getDocNotes()));
            vo.setCompareNote(blankToNull(record.getCompareNote()));
            if (record.getReviewMd() != null && !record.getReviewMd().isEmpty()) {
                vo.setHasMd(true);
                // 对照：列优先，列没填过就拿原文兜底（补列之前那两天的值只在 md 里）。
                // 当日题材仍然只能从原文现读回来。
                ReviewDoc doc = ReviewImportParser.parse(record.getReviewMd());
                if (vo.getCompareNote() == null) {
                    vo.setCompareNote(blankToNull(doc.textOr("对照", null)));
                }
                for (ReviewDoc.ThemeRow t : doc.getThemes()) {
                    ReviewDetailVO.ThemeItem item = new ReviewDetailVO.ThemeItem();
                    item.setTheme(t.getTheme());
                    item.setStrength(t.getStrength());
                    item.setStatus(t.getStatus());
                    item.setLeaderCode(t.getLeaderCode());
                    item.setLeaderName(t.getLeaderName());
                    vo.getThemes().add(item);
                }
            }
        }
        for (Position p : positionStore.read(userId, date)) {
            ReviewDetailVO.PositionItem item = new ReviewDetailVO.PositionItem();
            item.setCode(p.getStockCode());
            item.setName(p.getStockName());
            item.setCostPrice(p.getCostPrice());
            item.setCurrentPrice(p.getCurrentPrice());
            item.setFloatPct(p.getFloatPct());
            item.setAction(p.getAction());
            item.setPlannedAction(p.getPlannedAction());
            item.setDiscipline(p.getDiscipline());
            vo.getPositions().add(item);
        }
        for (Prediction p : predictionStore.read(userId, date)) {
            ReviewDetailVO.PredictionItem item = new ReviewDetailVO.PredictionItem();
            item.setName(p.getName());
            item.setProb(p.getProb());
            item.setCondition(p.getConditionText());
            item.setResult(p.getResult());
            item.setNote(p.getResultNote());
            if (Prediction.KIND_ANSWER.equals(p.getKind())) {
                vo.getAnswers().add(item);
            } else {
                vo.getPlans().add(item);
            }
        }
        indexCloseStore.read(date).forEach(ic -> {
            ReviewDetailVO.IndexItem item = new ReviewDetailVO.IndexItem();
            item.setCode(ic.getIndexCode());
            item.setName(ic.getIndexName());
            item.setClosePrice(ic.getClosePrice());
            item.setChangePct(ic.getChangePct());
            vo.getIndexes().add(item);
        });
        return vo;
    }

    // ---- 预览 ----

    private Prepared prepare(Long userId, ReviewDoc doc) {
        ImportPreviewVO vo = new ImportPreviewVO();
        vo.setDate(doc.getDate());
        vo.setProseIncluded(doc.isProse());
        // 代码只查这一次。落库复用同一份 map，重跑一遍会把"名字以库为准"的警告刷成双份。
        Map<String, String> names = resolveCodes(doc);
        // 解析错误按行号排过序，代码校验是后追加的，不重排就会把"查无此码"堆到表尾、看不出在文件哪一段。
        doc.getErrors().sort(Comparator.comparingInt(ReviewDoc.ParseError::getLine));
        vo.getErrors().addAll(doc.getErrors());
        vo.getWarnings().addAll(doc.getWarnings());
        if (doc.getDate() == null || doc.hasErrors()) {
            vo.setOkToConfirm(false);
            return new Prepared(vo, names);
        }
        vo.setOkToConfirm(true);

        LocalDate date = doc.getDate();
        DailyRecord current = dailyRecordService.getByDate(userId, date);
        DailyRecord base = current != null ? current : blankRecord(userId, date);
        DailyRecord draft = new DailyRecord();
        BeanUtils.copyProperties(base, draft);
        ReviewImportWriter.applySingles(draft, doc, names);

        vo.setCompareNote(blankToNull(doc.textOr("对照", null)));
        addScalarChanges(vo, doc, base, draft);
        addRowCounts(vo, userId, date, doc);
        addThemeChanges(vo, userId, doc);
        addMarketCompare(vo, current);
        addScoreImpact(vo, userId, date, current, draft);
        if (current == null) {
            vo.getWarnings().add(date + " 这天还没有复盘记录，确认后会新建一条。"
                    + "表单页的「重算」不会替你建（它拒绝凭空造行），所以行情七数得另外拉一次快照。");
        }
        return new Prepared(vo, names);
    }

    private void addScalarChanges(ImportPreviewVO vo, ReviewDoc doc, DailyRecord b, DailyRecord a) {
        List<ImportPreviewVO.Change> out = vo.getChanges();
        addText(out, doc, "主线", "主线", b.getMainTheme(), a.getMainTheme(), null);
        addNum(out, doc, KEY_SCORE_THEME, "主线明确度", b.getScoreTheme(), a.getScoreTheme(),
                true, NOTE_SCORE_THEME);
        addText(out, doc, "总龙头", "总龙头", b.getLeadingStock(), a.getLeadingStock(),
                "md 里写「代码 名称」，库里这列只存名称。");
        addText(out, doc, "龙头状态", "龙头状态", b.getLeadingStockStatus(), a.getLeadingStockStatus(), null);
        addText(out, doc, "中军", "中军", b.getMidCapStock(), a.getMidCapStock(),
                "md 里写「代码 名称」，库里这列只存名称。");
        addText(out, doc, "轮动观察", "轮动观察", b.getRotationNote(), a.getRotationNote(), null);
        addText(out, doc, "明日计划", "明日计划", b.getTomorrowPlan(), a.getTomorrowPlan(), null);
        addNum(out, doc, "我的仓位", "我的仓位%", b.getMyPositionPct(), a.getMyPositionPct(),
                false, NOTE_POSITION_PCT);
        addText(out, doc, "涨跌家数", "涨/跌家数", breadth(b.getUpCount(), b.getDownCount()),
                breadth(a.getUpCount(), a.getDownCount()), NOTE_BREADTH);
    }

    private void addText(List<ImportPreviewVO.Change> out, ReviewDoc doc, String key, String label,
                         String before, String after, String note) {
        ReviewDoc.Value v = doc.single(key);
        if (v == null) {
            return;
        }
        out.add(buildChange(key, label, nz(before), nz(after), false, v.isBlank(), note));
    }

    private void addNum(List<ImportPreviewVO.Change> out, ReviewDoc doc, String key, String label,
                        Object before, Object after, boolean scored, String note) {
        ReviewDoc.Value v = doc.single(key);
        if (v == null) {
            return;
        }
        out.add(buildChange(key, label, plain(before), plain(after), scored, v.isBlank(), note));
    }

    private static ImportPreviewVO.Change buildChange(String key, String label, String before,
                                                      String after, boolean scored,
                                                      boolean clearing, String note) {
        ImportPreviewVO.Change c = new ImportPreviewVO.Change();
        c.setKey(key);
        c.setLabel(label);
        c.setOldValue(before);
        c.setNewValue(after);
        c.setScored(scored);
        c.setClearing(clearing);
        c.setNote(note);
        c.setChanged(!before.equals(after));
        return c;
    }

    private void addRowCounts(ImportPreviewVO vo, Long userId, LocalDate date, ReviewDoc doc) {
        if (!doc.getPositions().isEmpty()) {
            vo.getRowCounts().add(new ImportPreviewVO.RowCount("持仓台账 t_position",
                    positionStore.read(userId, date).size(), doc.getPositions().size(),
                    "按日删除重建：这天原有的持仓行会被这批换掉"));
        }
        List<Prediction> existing = predictionStore.read(userId, date);
        if (!doc.getPlans().isEmpty()) {
            vo.getRowCounts().add(new ImportPreviewVO.RowCount("预判 t_prediction·PLAN",
                    countKind(existing, Prediction.KIND_PLAN), doc.getPlans().size(),
                    "兑现结果不冗余存一份：次日 ANSWER 行按路径名 join 回来"));
        }
        if (!doc.getAnswers().isEmpty()) {
            vo.getRowCounts().add(new ImportPreviewVO.RowCount("对答案 t_prediction·ANSWER",
                    countKind(existing, Prediction.KIND_ANSWER), doc.getAnswers().size(),
                    "答的是前一日那些路径名，名字对不上就 join 不回来"));
        }
        if (!doc.getIndexes().isEmpty()) {
            vo.getRowCounts().add(new ImportPreviewVO.RowCount("指数 t_index_close",
                    indexCloseStore.read(date).size(), doc.getIndexes().size(),
                    "公开数据，不绑用户"));
        }
    }

    private void addThemeChanges(ImportPreviewVO vo, Long userId, ReviewDoc doc) {
        for (ReviewDoc.ThemeRow row : doc.getThemes()) {
            ImportPreviewVO.ThemeChange t = new ImportPreviewVO.ThemeChange();
            t.setTheme(row.getTheme());
            t.setStrength(row.getStrength());
            t.setStatus(row.getStatus());
            t.setLeader(row.getLeaderName());
            Theme existing = themeMapper.selectOne(new LambdaQueryWrapper<Theme>()
                    .eq(Theme::getUserId, userId)
                    .eq(Theme::getName, row.getTheme())
                    .orderByAsc(Theme::getId)
                    .last("LIMIT 1"));
            if (existing == null) {
                t.setNote("新建题材");
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append(row.getStrength() != null && !row.getStrength().equals(existing.getStrength())
                        ? "强度 " + dash(existing.getStrength()) + "→" + row.getStrength() : "强度不变");
                if (!Objects.equals(row.getStatus(), existing.getStatus())) {
                    sb.append(" · 状态 ").append(dash(existing.getStatus())).append("→").append(row.getStatus());
                }
                sb.append("（t_theme 无日粒度，这是覆盖当前值不是补当天历史）");
                t.setNote(sb.toString());
            }
            vo.getThemeChanges().add(t);
        }
    }

    /** 系统七数原样摆出来给你核，导入既不改它们也不接收它们。 */
    private void addMarketCompare(ImportPreviewVO vo, DailyRecord current) {
        vo.getMarketCompare().add(new ImportPreviewVO.MarketRow("连板高度",
                current == null ? null : plain(current.getMaxConsecutiveLimit()), "东财涨停池"));
        vo.getMarketCompare().add(new ImportPreviewVO.MarketRow("涨停家数",
                current == null ? null : plain(current.getLimitUpCount()), "东财涨停池"));
        vo.getMarketCompare().add(new ImportPreviewVO.MarketRow("跌停家数",
                current == null ? null : plain(current.getLimitDownCount()), "东财跌停池"));
        vo.getMarketCompare().add(new ImportPreviewVO.MarketRow("昨日涨停溢价",
                current == null ? null : plain(current.getYesterdayLimitPremium()), "昨日涨停池 × 腾讯报价"));
        vo.getMarketCompare().add(new ImportPreviewVO.MarketRow("炸板率",
                current == null ? null : plain(current.getBrokenBoardRate()), "东财炸板池"));
        vo.getMarketCompare().add(new ImportPreviewVO.MarketRow("大面数",
                current == null ? null : plain(current.getBigLossCount()), "盘面明细"));
        vo.getMarketCompare().add(new ImportPreviewVO.MarketRow("两市成交",
                current == null ? null : plain(current.getTotalVolume()), "腾讯"));
    }

    /**
     * 在一份 detached 副本上跑同一套打分。阵眼与监管两维要联网取日 K，
     * 取不到就把整块降级成一句"未能预览分数"——预览失败不该拦下导入，落库后照常重算。
     */
    private void addScoreImpact(ImportPreviewVO vo, Long userId, LocalDate date,
                                DailyRecord current, DailyRecord draft) {
        ImportPreviewVO.ScoreImpact s = new ImportPreviewVO.ScoreImpact();
        vo.setScoreImpact(s);
        try {
            DailyRecord after = dailyRecordService.scoredCopy(userId, date, draft);
            s.setDimsAfter(after.getScoredDims());
            s.setTemperatureAfter(plain(after.getTemperature()));
            s.setStageAfter(nz(after.getStage()));
            if (current == null) {
                s.setDimsBefore(0);
                s.setTemperatureBefore("");
                s.setStageBefore("");
            } else {
                s.setDimsBefore(current.getScoredDims());
                s.setTemperatureBefore(plain(current.getTemperature()));
                s.setStageBefore(nz(current.getStage()));
            }
        } catch (Exception e) {
            log.warn("导入预览算分失败，退化成不显示分数影响: {}", e.toString());
            s.setUnavailable("未能预览分数：" + e.getClass().getSimpleName()
                    + "。阵眼与监管两维要联网取日 K，这一步取不到不影响导入，落库后按九维口径正常重算。");
        }
    }

    // ---- 代码校验 ----

    /**
     * 六位代码反查 t_stock，返回代码 → 库里的正名。
     *
     * <p>名字<b>不采信 md 里写的那个</b>：一个错代码会把整只票对到别的票身上，而台账上看不出异样。
     * 查无此码时给名字相近的候选，但<b>不自动换码也不自动改名</b>——猜错一次的代价是台账从此对不上。
     *
     * <p>指数代码<b>不进这套校验</b>：000001 既是上证指数也是平安银行，两张表撞码。
     * 拿 t_stock 去判指数会把合法持仓判成错码，反过来会让指数行进个股表。指数只按 KNOWN_INDEXES 提个警告。
     */
    private Map<String, String> resolveCodes(ReviewDoc doc) {
        Set<String> codes = new LinkedHashSet<>();
        doc.getPositions().forEach(r -> codes.add(r.getCode()));
        doc.getThemes().forEach(r -> {
            if (r.getLeaderCode() != null) {
                codes.add(r.getLeaderCode());
            }
        });
        addCode(codes, doc.single("总龙头"));
        addCode(codes, doc.single("中军"));

        Map<String, String> names = new HashMap<>();
        if (codes.isEmpty()) {
            return names;
        }
        for (Stock s : stockMapper.selectList(new LambdaQueryWrapper<Stock>()
                .in(Stock::getCode, codes))) {
            names.put(s.getCode(), s.getName());
        }
        for (String code : codes) {
            if (!names.containsKey(code)) {
                doc.addError(lineOf(doc, code), keyOf(doc, code),
                        "代码 " + code + " 不在 A股代码表里", suggest(nameForCode(doc, code)));
            }
        }
        warnOnNameDrift(doc, names);
        return names;
    }

    private static void addCode(Set<String> codes, ReviewDoc.Value v) {
        if (v == null || v.isBlank()) {
            return;
        }
        String[] parts = v.getRaw().split("\\s+");
        if (parts.length == 2) {
            codes.add(parts[0]);
        }
    }

    /** 一个代码可能同时出现在持仓和题材龙头里，报错取它第一次出现的那行、那个键，只报一条。 */
    private static int lineOf(ReviewDoc doc, String code) {
        int line = 0;
        for (ReviewDoc.PositionRow r : doc.getPositions()) {
            if (r.getCode().equals(code)) {
                line = Math.min(line == 0 ? Integer.MAX_VALUE : line, r.getLine());
            }
        }
        for (ReviewDoc.ThemeRow r : doc.getThemes()) {
            if (code.equals(r.getLeaderCode())) {
                line = Math.min(line == 0 ? Integer.MAX_VALUE : line, r.getLine());
            }
        }
        for (String key : new String[]{"总龙头", "中军"}) {
            ReviewDoc.Value v = doc.single(key);
            if (v != null && v.getRaw().startsWith(code + " ")) {
                line = Math.min(line == 0 ? Integer.MAX_VALUE : line, v.getLine());
            }
        }
        return line == Integer.MAX_VALUE ? 0 : line;
    }

    private static String keyOf(ReviewDoc doc, String code) {
        for (ReviewDoc.PositionRow r : doc.getPositions()) {
            if (r.getCode().equals(code)) {
                return "持仓";
            }
        }
        for (ReviewDoc.ThemeRow r : doc.getThemes()) {
            if (code.equals(r.getLeaderCode())) {
                return "题材";
            }
        }
        for (String key : new String[]{"总龙头", "中军"}) {
            ReviewDoc.Value v = doc.single(key);
            if (v != null && v.getRaw().startsWith(code + " ")) {
                return key;
            }
        }
        return null;
    }

    private static String nameForCode(ReviewDoc doc, String code) {
        for (ReviewDoc.PositionRow r : doc.getPositions()) {
            if (r.getCode().equals(code)) {
                return r.getName();
            }
        }
        for (ReviewDoc.ThemeRow r : doc.getThemes()) {
            if (code.equals(r.getLeaderCode())) {
                return r.getLeaderName();
            }
        }
        for (String key : new String[]{"总龙头", "中军"}) {
            ReviewDoc.Value v = doc.single(key);
            if (v != null && v.getRaw().startsWith(code + " ")) {
                return v.getRaw().substring(code.length()).trim();
            }
        }
        return null;
    }

    /** 候选只是给个方向：按名字模糊查三条，剩下你自己认。 */
    private String suggest(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        List<Stock> near = stockMapper.selectList(new LambdaQueryWrapper<Stock>()
                .like(Stock::getName, name)
                .orderByAsc(Stock::getCode)
                .last("LIMIT 3"));
        if (near.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("是不是想写 ");
        for (int i = 0; i < near.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(near.get(i).getCode()).append(' ').append(near.get(i).getName());
        }
        return sb.toString();
    }

    private void warnOnNameDrift(ReviewDoc doc, Map<String, String> names) {
        for (ReviewDoc.PositionRow r : doc.getPositions()) {
            warnIfDrift(doc, r.getLine(), "持仓", r.getCode(), r.getName(), names);
        }
        for (ReviewDoc.ThemeRow r : doc.getThemes()) {
            if (r.getLeaderCode() != null) {
                warnIfDrift(doc, r.getLine(), "题材", r.getLeaderCode(), r.getLeaderName(), names);
            }
        }
        warnSingleIfDrift(doc, names, "总龙头");
        warnSingleIfDrift(doc, names, "中军");
    }

    private static void warnSingleIfDrift(ReviewDoc doc, Map<String, String> names, String key) {
        ReviewDoc.Value v = doc.single(key);
        if (v == null || v.isBlank()) {
            return;
        }
        String[] parts = v.getRaw().split("\\s+");
        if (parts.length == 2) {
            warnIfDrift(doc, v.getLine(), key, parts[0], parts[1], names);
        }
    }

    private static void warnIfDrift(ReviewDoc doc, int line, String key, String code,
                                    String mdName, Map<String, String> names) {
        String canonical = names.get(code);
        if (canonical != null && !canonical.equals(mdName)) {
            doc.addWarning("第 " + line + " 行 " + key + " " + code + "：库里叫「" + canonical
                    + "」，md 写的是「" + mdName + "」，导入以库为准（改名票常见，不替你改代码表）");
        }
    }

    // ---- 小工具 ----

    private static DailyRecord blankRecord(Long userId, LocalDate date) {
        DailyRecord record = new DailyRecord();
        record.setUserId(userId);
        record.setTradeDate(date);
        return record;
    }

    private static int countKind(List<Prediction> rows, String kind) {
        int n = 0;
        for (Prediction p : rows) {
            if (kind.equals(p.getKind())) {
                n++;
            }
        }
        return n;
    }

    private static String breadth(Integer up, Integer down) {
        if (up == null && down == null) {
            return "";
        }
        return dash(up) + "/" + dash(down);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }

    private static String dash(Object v) {
        return v == null ? "—" : String.valueOf(v);
    }

    /** DECIMAL 列定标返回：5.00% → 5，3942.09 保持。空值给空串而不是 0，0 是一个读数。 */
    private static String plain(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }
}
