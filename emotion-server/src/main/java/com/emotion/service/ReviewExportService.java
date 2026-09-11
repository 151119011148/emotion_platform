package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.Anchor;
import com.emotion.entity.DailyRecord;
import com.emotion.entity.IndexClose;
import com.emotion.entity.Position;
import com.emotion.entity.Prediction;
import com.emotion.entity.Stock;
import com.emotion.mapper.StockMapper;
import com.emotion.util.ReviewDoc;
import com.emotion.util.ReviewDocFormatter;
import com.emotion.util.ReviewImportParser;
import com.emotion.util.ReviewMdFormatter;
import com.emotion.vo.ReviewExportVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 平台 → 复盘 md。{@link ReviewImportService} 的反向，两边共用 {@link ReviewDoc} 这个中间表示。
 *
 * <p>这一步真正省的不是打字，是<b>抄数</b>。七个行情数、九维各分、温度阶段本来就在库里，
 * 以前每天要人眼从仪表盘上读回来敲进 md——而手抄进来的数导入器又不接收
 * （{@link ReviewImportParser#SYSTEM_OWNED} 一律拒收），于是那份 md 和库里那份永远差着几个数。
 * 现在这些数只进正文的快照段，meta 块里一个都不写，两边不可能再打架。
 *
 * <p><b>meta 块永远按库里当前的值重建</b>，只有正文沿用那天存过的那份。听起来冒险，其实是唯一
 * 说得通的方向：库里那份是<b>上次导入的结果</b>，重建它等于把 md 对齐到自己刚确认过的状态；
 * 反过来沿用旧 md 里的 meta，就会留下一格"页面显示已改、md 还是旧值"的暗差。
 *
 * <p>一个读不到的例外，只能从 {@code review_md} 现解析回来：{@code 题材} 没有日粒度
 * （题材表有，但拿它当"当天的题材"会让上周的强度串到今天）。{@code 对照} 以前也在这一列里，
 * 现在有自己的列了，读取按"列优先、原文兜底"。
 */
@Service
public class ReviewExportService {

    private static final Logger log = LoggerFactory.getLogger(ReviewExportService.class);

    /** 没存过原文时给的新正文骨架，七步对齐 06 篇那份清单。 */
    private static final String SKELETON =
            "# 每日复盘 · %s\n\n"
                    + "## 一、读数与打分\n\n"
                    + "上面那段快照是系统取的数。这里只写<b>你的读数</b>和它不一致的地方、以及不一致的口径依据。\n\n"
                    + "## 二、定位\n\n"
                    + "今天处在七阶段里的哪一段？相对昨天是升 / 降 / 跳级？和系统给的一致吗，不一致的依据是什么。\n\n"
                    + "## 三、认主线认龙头\n\n"
                    + "按强度排序，每条一行；一字独食还是合力换手，标出来。\n\n"
                    + "## 四、看轮动\n\n"
                    + "钱从哪儿流出、往哪儿流入？有没有新题材冒头（下一轮的种子）。\n\n"
                    + "## 五、持仓与纪律\n\n"
                    + "连续 N 日「应做未做」是这块最该沉淀的东西，`纪律` 列务必填。\n\n"
                    + "## 六、对答案\n\n"
                    + "昨天盘前的计划和判断今天兑现了吗？错在<b>定位 / 选股 / 执行</b>哪一层。\n\n"
                    + "## 七、定明天\n\n"
                    + "三路径 + 概率 + 触发条件 + 每路径的仓位上限。\n";

    private final DailyRecordService dailyRecordService;
    private final PositionStore positionStore;
    private final PredictionStore predictionStore;
    private final IndexCloseStore indexCloseStore;
    private final StockMapper stockMapper;
    private final MarketDataService marketDataService;
    private final AnchorService anchorService;

    public ReviewExportService(DailyRecordService dailyRecordService,
                               PositionStore positionStore,
                               PredictionStore predictionStore,
                               IndexCloseStore indexCloseStore,
                               StockMapper stockMapper,
                               MarketDataService marketDataService,
                               AnchorService anchorService) {
        this.dailyRecordService = dailyRecordService;
        this.positionStore = positionStore;
        this.predictionStore = predictionStore;
        this.indexCloseStore = indexCloseStore;
        this.stockMapper = stockMapper;
        this.marketDataService = marketDataService;
        this.anchorService = anchorService;
    }

    /** 不管那天有没有原文，一律按库里当前的值重建。写周五的复盘用这个。 */
    public ReviewExportVO template(Long userId, LocalDate date) {
        return build(userId, date, false);
    }

    /**
     * 那天导入过就原样给出原文（贴回去改完再导入是幂等的），没导入过没什么可"导出"的，退化成重建。
     */
    public ReviewExportVO export(Long userId, LocalDate date) {
        DailyRecord record = dailyRecordService.getByDate(userId, date);
        if (record != null && notBlank(record.getReviewMd())) {
            ReviewExportVO vo = new ReviewExportVO();
            vo.setDate(date);
            vo.setContent(record.getReviewMd());
            vo.setGenerated(false);
            vo.getWarnings().add(date + " 存过原文，上面那份是逐字取回的，meta 块没有按库里当前值重建。"
                    + "要是你在表单页改过某些格，用「生成模板」拿重建的那份。");
            return vo;
        }
        return build(userId, date, true);
    }

    /**
     * 只读<b>复盘文档</b>：把那天库里的系统取数按用户手写的版式（【一】…【九】）排成一份给人读、
     * 给人补判断的 md。与 {@link #template} 不同——那份是喂回导入器的可再导入格式，这份不写 meta、
     * 不承诺可导入。判断文字平台造不出，也不从库里回填（{@code doc_notes} 已停用）：
     * 每节固定留一行 {@code ✍️ 判断} 占位，他写完的那份就是当天的 md。
     */
    public ReviewExportVO reviewDoc(Long userId, LocalDate date) {
        // viewByDate：没有主观复盘行时，客观行情节（七数/盘面）仍由 t_market_daily 合成回填，全账号同一份。
        DailyRecord today = dailyRecordService.viewByDate(userId, date);
        DailyRecord prev = previousRecord(userId, date);

        ReviewDocFormatter.Model m = new ReviewDocFormatter.Model();
        m.date = date;
        m.today = today;
        m.prev = prev;
        m.indexes = indexCloseStore.read(date);
        m.stocks = marketDataService.stocks(date);
        m.positions = positionStore.read(userId, date);
        m.predictions = predictionStore.read(userId, date);
        m.anchors = anchorService.listInPosition(userId, date);
        // 题材无日粒度，只有那天导入过 md 才解析得回来。
        if (today != null && notBlank(today.getReviewMd())) {
            m.themes = ReviewImportParser.parse(today.getReviewMd(), date).getThemes();
        }

        ReviewExportVO vo = new ReviewExportVO();
        vo.setDate(date);
        vo.setGenerated(true);
        if (today == null || today.getId() == null) {
            vo.getWarnings().add(date + " 还没有主观复盘记录：行情数读全局客观日表，主线/龙头等手填节显示 —。");
        }
        if (m.themes.isEmpty()) {
            vo.getWarnings().add("题材没有日粒度：这天没导入过带 `题材:` 的原文，【二】只给了主线与总龙头。");
        }
        if (m.stocks == null || !m.stocks.isAvailable()) {
            vo.getWarnings().add("这天没有盘面明细（连板梯队/跌停/大面），先在复盘页拉一次行情快照再导。");
        }
        vo.setContent(ReviewDocFormatter.render(m));
        return vo;
    }

    /** 上一交易日：近 40 条里 trade_date 严格早于 date 的最近一条。缺历史就返回 null，对照表昨列显 —。 */
    private DailyRecord previousRecord(Long userId, LocalDate date) {
        DailyRecord best = null;
        for (DailyRecord r : dailyRecordService.getLatest(userId, 40)) {
            if (r.getTradeDate() != null && r.getTradeDate().isBefore(date)
                    && (best == null || r.getTradeDate().isAfter(best.getTradeDate()))) {
                best = r;
            }
        }
        return best;
    }

    private ReviewExportVO build(Long userId, LocalDate date, boolean fromExport) {
        // viewByDate：涨跌家数等客观键即使没有主观行也能重建进 meta（值来自 t_market_daily）。
        DailyRecord record = dailyRecordService.viewByDate(userId, date);
        ReviewDoc stored = record != null && record.getId() != null && notBlank(record.getReviewMd())
                ? ReviewImportParser.parse(record.getReviewMd(), date) : null;

        ReviewExportVO vo = new ReviewExportVO();
        vo.setDate(date);
        ReviewDoc doc = new ReviewDoc();
        doc.setDate(date);
        fillSingles(doc, record, stored, vo);
        fillRows(doc, userId, date, stored, vo);

        String prose = stored != null ? ReviewMdFormatter.proseOf(record.getReviewMd())
                : String.format(SKELETON, date);
        String body = ReviewMdFormatter.replaceSnapshot(prose, ReviewMdFormatter.snapshot(record));
        vo.setContent(ReviewMdFormatter.render(doc, metaNotes(date, record, fromExport), body));
        vo.getWarnings().addAll(doc.getWarnings());
        return vo;
    }

    private List<String> metaNotes(LocalDate date, DailyRecord record, boolean fromExport) {
        List<String> notes = new ArrayList<>();
        notes.add("本块由平台按 " + date + " 库里的值生成" + (fromExport ? "（那天没存过原文，这是重建的）" : "")
                + "。改完贴回「复盘文件导入」页，先预览再确认。");
        notes.add("键没写 = 那列不动；键写了空值 = 清空那列。库里没值的键这一版就没写，见页面下方的「未写入的键」。");
        if (record == null) {
            notes.add("这天库里还没有复盘记录：确认导入会新建一条，但行情七数得另外拉一次快照，"
                    + "否则温度、阶段、九维分都出不来。");
        }
        return notes;
    }

    // ---- 标量键 ----

    private void fillSingles(ReviewDoc doc, DailyRecord r, ReviewDoc stored, ReviewExportVO vo) {
        if (r == null) {
            for (String key : ReviewImportParser.SINGLE_KEYS) {
                if (!"date".equals(key)) {
                    vo.getOmittedKeys().add(key);
                }
            }
            return;
        }
        putText(doc, vo, "主线", r.getMainTheme());
        putEnum(doc, vo, "主线明确度", r.getScoreTheme() == null ? null : plain(r.getScoreTheme()),
                ReviewImportParser.SCORE_THEME, "第 7 维只能取 3 / 1 / 0");
        putStock(doc, vo, "总龙头", r.getLeadingStock());
        putEnum(doc, vo, "龙头状态", r.getLeadingStockStatus(), ReviewImportParser.LEADER_STATUS,
                "不在 加速/滞涨/断板/反包/正常 里，写进去会被判坏行");
        putStock(doc, vo, "中军", r.getMidCapStock());
        putText(doc, vo, "轮动观察", r.getRotationNote());
        putText(doc, vo, "明日计划", r.getTomorrowPlan());
        putText(doc, vo, "我的仓位", plain(r.getMyPositionPct()));
        String breadth = ReviewMdFormatter.upDown(r.getUpCount(), r.getDownCount());
        if (breadth == null) {
            vo.getOmittedKeys().add("涨跌家数");
            if (r.getUpCount() != null || r.getDownCount() != null) {
                vo.getWarnings().add("涨跌家数只有一半（涨 " + dash(r.getUpCount()) + " / 跌 "
                        + dash(r.getDownCount()) + "），没法写成 涨/跌 的形状，整条没写入。");
            }
        } else {
            putText(doc, vo, "涨跌家数", breadth);
        }
        // 对照：列优先（表单那一格与 md 的 `对照:` 键都写它），列没填过才回落到那天存过的原文。
        putText(doc, vo, "对照", notBlank(r.getCompareNote()) ? r.getCompareNote()
                : (stored == null ? null : stored.textOr("对照", null)));
    }

    private static void putText(ReviewDoc doc, ReviewExportVO vo, String key, String value) {
        if (!notBlank(value)) {
            vo.getOmittedKeys().add(key);
            return;
        }
        doc.getSingles().put(key, new ReviewDoc.Value(value, 0));
    }

    /** 库里存的是枚举外的值（历史上手填过），宁可不写也别生成一行坏数据。 */
    private static void putEnum(ReviewDoc doc, ReviewExportVO vo, String key, String value,
                                Set<String> allowed, String why) {
        if (!notBlank(value)) {
            vo.getOmittedKeys().add(key);
            return;
        }
        if (!allowed.contains(value)) {
            vo.getOmittedKeys().add(key);
            vo.getWarnings().add(key + " 库里是「" + value + "」，" + why + "，所以没写进 meta。");
            return;
        }
        doc.getSingles().put(key, new ReviewDoc.Value(value, 0));
    }

    /**
     * 总龙头 / 中军 库里只存名字，md 要的是「代码 名称」，所以反查一次 t_stock。
     * 查不到就只写名字——渲染自检会把它降级成注释，代码还在眼前，你补六个数字就行。
     */
    private void putStock(ReviewDoc doc, ReviewExportVO vo, String key, String name) {
        if (!notBlank(name)) {
            vo.getOmittedKeys().add(key);
            return;
        }
        String trimmed = name.trim();
        String code = codeOf(trimmed);
        doc.getSingles().put(key, new ReviewDoc.Value(code == null ? trimmed : code + " " + trimmed, 0));
        if (code == null) {
            vo.getWarnings().add(key + "「" + trimmed + "」在 A股代码表里反查不到代码（改过名？）。"
                    + "生成的时候会被降级成注释，要留这格请自己补六位代码。");
        }
    }

    private String codeOf(String name) {
        try {
            List<Stock> hits = stockMapper.selectList(new LambdaQueryWrapper<Stock>()
                    .eq(Stock::getName, name));
            if (hits.isEmpty()) {
                return null;
            }
            if (hits.size() > 1) {
                log.warn("名字「{}」在 t_stock 里有 {} 条，反查代码时只能放弃", name, hits.size());
                return null;
            }
            return hits.get(0).getCode();
        } catch (RuntimeException e) {
            // 反查失败只影响这一格好不好看，不该让整个导出 500。
            log.warn("反查 {} 失败: {}", name, e.toString());
            return null;
        }
    }

    // ---- 多行键 ----

    private void fillRows(ReviewDoc doc, Long userId, LocalDate date, ReviewDoc stored, ReviewExportVO vo) {
        for (Position p : positionStore.read(userId, date)) {
            doc.getPositions().add(new ReviewDoc.PositionRow(0, p.getStockCode(), p.getStockName(),
                    p.getCostPrice(), p.getCurrentPrice(), p.getFloatPct(),
                    p.getAction(), p.getPlannedAction(), p.getDiscipline()));
        }
        if (doc.getPositions().isEmpty()) {
            vo.getOmittedKeys().add("持仓");
        }
        for (Prediction p : predictionStore.read(userId, date)) {
            if (Prediction.KIND_ANSWER.equals(p.getKind())) {
                doc.getAnswers().add(new ReviewDoc.AnswerRow(0, p.getName(), p.getResult(), p.getResultNote()));
            } else {
                doc.getPlans().add(new ReviewDoc.PlanRow(0, p.getName(), p.getProb(), p.getConditionText()));
            }
        }
        if (doc.getPlans().isEmpty()) {
            vo.getOmittedKeys().add("预判");
        }
        if (doc.getAnswers().isEmpty()) {
            vo.getOmittedKeys().add("对答案");
        }
        for (IndexClose ic : indexCloseStore.read(date)) {
            doc.getIndexes().add(new ReviewDoc.IndexRow(0, ic.getIndexCode(), ic.getIndexName(),
                    ic.getClosePrice(), ic.getChangePct()));
        }
        if (doc.getIndexes().isEmpty()) {
            vo.getOmittedKeys().add("指数");
        }
        // 题材没有日粒度，只能从那天存过的原文里读回来。库里 t_theme 那份是"最后一次导入赢"的当前值，
        // 拿它当"当天的题材"会让上周的强度出现在今天的格子里。
        if (stored != null && !stored.getThemes().isEmpty()) {
            for (ReviewDoc.ThemeRow t : stored.getThemes()) {
                doc.getThemes().add(new ReviewDoc.ThemeRow(0, t.getTheme(), t.getStrength(),
                        t.getStatus(), t.getLeaderCode(), t.getLeaderName()));
            }
        } else {
            vo.getOmittedKeys().add("题材");
            if (stored == null) {
                vo.getWarnings().add("题材没有日粒度，只存在于导入过的原文里。这天没存过原文，所以无从带出；"
                        + "要记题材请直接在 meta 里写 `题材:` 行。");
            }
        }
    }

    // ---- 小工具 ----

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String plain(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }

    private static String dash(Object v) {
        return v == null ? "—" : String.valueOf(v);
    }
}
