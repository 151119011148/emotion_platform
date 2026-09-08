package com.emotion.service;

import com.emotion.entity.IndexClose;
import com.emotion.entity.Position;
import com.emotion.entity.Prediction;
import com.emotion.entity.DailyRecord;
import com.emotion.util.ReviewDoc;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * md 那一侧到库里列的<b>唯一</b>一份映射，外加那次事务。
 *
 * <p>映射做成静态方法是有原因的：{@code ReviewImportService} 拼预览差异时要把同一套映射
 * 跑在一份不落库的副本上。两边各写一遍的话，「预览说会改成 X、确认之后库里是 Y」这种事故
 * 迟早会出来，而且没人会发现。
 *
 * <p>字段语义只有一条：<b>键没写 = 一个字都不动；键写了空值 = 清掉</b>。
 * 所以这里没有 if (v != null) 之外的那套守卫，也没有把 "" 写成 NULL——
 * {@code main_theme} 那几列没挂 ALWAYS 策略，写 NULL 会被 MyBatis-Plus 的 NOT_NULL 更新
 * 策略整列跳过，清空就得落成空串。数字列（score_theme / up_count / my_position_pct）反过来，
 * 它们挂了 ALWAYS，清空要写 NULL，写空串会被 JDBC 拒掉。
 */
@Service
public class ReviewImportWriter {

    private final DailyRecordService dailyRecordService;
    private final PositionStore positionStore;
    private final PredictionStore predictionStore;
    private final IndexCloseStore indexCloseStore;
    private final ThemeDayWriter themeDayWriter;

    public ReviewImportWriter(DailyRecordService dailyRecordService,
                              PositionStore positionStore,
                              PredictionStore predictionStore,
                              IndexCloseStore indexCloseStore,
                              ThemeDayWriter themeDayWriter) {
        this.dailyRecordService = dailyRecordService;
        this.positionStore = positionStore;
        this.predictionStore = predictionStore;
        this.indexCloseStore = indexCloseStore;
        this.themeDayWriter = themeDayWriter;
    }

    /**
     * 一次确认 = 一个事务。标量列走 {@code importManual}（那天没记录时它会新建，
     * 这是导入器和 {@code recalc} 最大的区别：recalc 拒绝凭空造一行），
     * 三张按日表删除重建，题材走 upsert。
     *
     * @param names 代码 → t_stock 里的正名。名字一律以库为准，md 里写错的字只留一条警告。
     */
    @Transactional(rollbackFor = Exception.class)
    public DailyRecord write(Long userId, LocalDate date, String content, ReviewDoc doc,
                             Map<String, String> names) {
        DailyRecord record = dailyRecordService.importManual(userId, date, target -> {
            applySingles(target, doc, names);
            // 整篇原文，连 ```meta 围栏块一起。md 是唯一真相，这份要能原样导回编辑器改完再导入。
            target.setReviewMd(content);
        });
        positionStore.replaceForDate(userId, date, positionRows(userId, date, doc, names));
        predictionStore.replaceForDate(userId, date, predictionRows(userId, date, doc));
        indexCloseStore.replaceForDate(date, indexRows(date, doc));
        themeDayWriter.apply(userId, date, doc.getThemes());
        return record;
    }

    // ---- 标量列 ----

    /** 只动 md 点名的那些列。七个行情字段、温度、阶段都不在这里，导入器不接收它们。 */
    static void applySingles(DailyRecord target, ReviewDoc doc, Map<String, String> names) {
        ReviewDoc.Value v;
        if ((v = doc.single("主线")) != null) {
            target.setMainTheme(v.getRaw());
        }
        if ((v = doc.single("主线明确度")) != null) {
            target.setScoreTheme(v.isBlank() ? null : Integer.valueOf(v.getRaw()));
        }
        if ((v = doc.single("总龙头")) != null) {
            target.setLeadingStock(nameOf(v, names));
        }
        if ((v = doc.single("龙头状态")) != null) {
            target.setLeadingStockStatus(v.getRaw());
        }
        if ((v = doc.single("中军")) != null) {
            target.setMidCapStock(nameOf(v, names));
        }
        if ((v = doc.single("轮动观察")) != null) {
            target.setRotationNote(v.getRaw());
        }
        if ((v = doc.single("明日计划")) != null) {
            target.setTomorrowPlan(v.getRaw());
        }
        if ((v = doc.single("我的仓位")) != null) {
            target.setMyPositionPct(v.isBlank() ? null : new BigDecimal(v.getRaw()));
        }
        if ((v = doc.single("涨跌家数")) != null) {
            String[] parts = v.isBlank() ? new String[0] : v.getRaw().split("/");
            target.setUpCount(parts.length > 0 ? Integer.valueOf(parts[0].trim()) : null);
            target.setDownCount(parts.length > 1 ? Integer.valueOf(parts[1].trim()) : null);
        }
        if ((v = doc.single("对照")) != null) {
            target.setCompareNote(nullIfBlank(v.getRaw()));
        }
    }

    /**
     * 总龙头/中军 的值是「代码 名称」，而这两列存的是名字，所以取空格后那段。
     * 名字以 t_stock 为准（{@code names} 里就是它）：改名的票（摘 ST 那种）库里已经换了，
     * md 里的旧写法只配得上一条警告，不配反过来改库。
     */
    private static String nameOf(ReviewDoc.Value v, Map<String, String> names) {
        if (v.isBlank()) {
            return "";
        }
        String[] parts = v.getRaw().split("\\s+");
        if (parts.length != 2) {
            return v.getRaw();
        }
        String canonical = names.get(parts[0]);
        return canonical != null ? canonical : parts[1];
    }

    /** 可选标签没写时解析器给 null，写了但没内容给空串。库里这两种都是"没有"，不必分。 */
    private static String nullIfBlank(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    // ---- 按日行 ----

    /** 没有 持仓 键时返回 null = 这次没说，Store 会整块跳过。 */
    static List<Position> positionRows(Long userId, LocalDate date, ReviewDoc doc,
                                       Map<String, String> names) {
        if (doc.getPositions().isEmpty()) {
            return null;
        }
        List<Position> rows = new ArrayList<>();
        for (ReviewDoc.PositionRow r : doc.getPositions()) {
            Position p = new Position();
            p.setUserId(userId);
            p.setTradeDate(date);
            p.setStockCode(r.getCode());
            p.setStockName(names.getOrDefault(r.getCode(), r.getName()));
            p.setCostPrice(r.getCost());
            p.setCurrentPrice(r.getCurrent());
            p.setFloatPct(r.getFloatPct());
            p.setAction(r.getAction());
            p.setPlannedAction(r.getPlannedAction());
            p.setDiscipline(r.getDiscipline().isEmpty() ? null : r.getDiscipline());
            rows.add(p);
        }
        return rows;
    }

    /**
     * PLAN 与 ANSWER 一起交出去：Store 只删这次出现过的 kind。
     * 一份只写了 {@code 预判:} 的文件不该把那天已有的 {@code 对答案:} 行清掉。
     */
    static List<Prediction> predictionRows(Long userId, LocalDate date, ReviewDoc doc) {
        List<Prediction> rows = new ArrayList<>();
        for (ReviewDoc.PlanRow r : doc.getPlans()) {
            Prediction p = new Prediction();
            p.setUserId(userId);
            p.setTradeDate(date);
            p.setKind(Prediction.KIND_PLAN);
            p.setName(r.getName());
            p.setProb(r.getProb());
            p.setConditionText(nullIfBlank(r.getCondition()));
            rows.add(p);
        }
        for (ReviewDoc.AnswerRow r : doc.getAnswers()) {
            Prediction p = new Prediction();
            p.setUserId(userId);
            p.setTradeDate(date);
            p.setKind(Prediction.KIND_ANSWER);
            p.setName(r.getName());
            p.setResult(r.getResult());
            p.setResultNote(nullIfBlank(r.getNote()));
            rows.add(p);
        }
        return rows;
    }

    static List<IndexClose> indexRows(LocalDate date, ReviewDoc doc) {
        if (doc.getIndexes().isEmpty()) {
            return null;
        }
        List<IndexClose> rows = new ArrayList<>();
        for (ReviewDoc.IndexRow r : doc.getIndexes()) {
            IndexClose ic = new IndexClose();
            ic.setTradeDate(date);
            ic.setIndexCode(r.getCode());
            ic.setIndexName(r.getName());
            ic.setClosePrice(r.getClose());
            ic.setChangePct(r.getChangePct());
            rows.add(ic);
        }
        return rows;
    }
}
