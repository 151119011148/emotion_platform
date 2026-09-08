package com.emotion.util;

import com.emotion.entity.Anchor;
import com.emotion.entity.DailyRecord;
import com.emotion.entity.IndexClose;
import com.emotion.entity.Position;
import com.emotion.entity.Prediction;
import com.emotion.util.ReviewDoc.ThemeRow;
import com.emotion.vo.MarketStocksVO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台数据 → <b>给人读的复盘文档</b>。排版对齐用户手写的每日复盘长文（【一】…【九】方括号小节）。
 *
 * <p>和 {@link ReviewMdFormatter} 是两件事：那个产<b>可再导入</b>的 md（```meta + 自检降级），
 * 这个是<b>只读</b>的呈现层——选一天，把库里已经存着的系统取数（七数、温度/阶段、指数、连板梯队、
 * 持仓、预判/兑现、锚点）填进他的版式，判断性文字（核心定性 / 翻译 / 教训 / 评级理由）平台编不出来，
 * 每节只留一行 {@code ✍️ 判断：} 占位。
 *
 * <p>纯函数：入参是一个备好的 {@link Model}，不查库、不联网、不要 Spring，可离线单测。
 * 所有字段可空，空一律渲染成 {@code —} 或「无」的说明行，绝不 NPE、绝不抛给调用方。
 */
public final class ReviewDocFormatter {

    /**
     * 八个小节的判断文字键，<b>顺序必须和 {@link #render} 里的小节顺序一致</b>。
     *
     * <p>这一份是唯一定义处：{@code t_daily_record.doc_notes} 的 JSON 键、复盘页那八个输入框、
     * 以及导出文档里"有原文就放原文、没有就留写作提示"的判断行，全都读它。
     * 新增小节却忘了在这儿登记，结果是他写的字会被静默丢掉——所以 {@code ReviewDocFormatterTest}
     * 里有一条断言把键清单和渲染出的小节标题一一对起来。
     */
    public static final List<String> NOTE_KEYS = Collections.unmodifiableList(Arrays.asList(
            "index", "theme", "emotion", "position", "answer", "plan", "anchor", "strategy"));

    private ReviewDocFormatter() {
    }

    /** 渲染一份文档需要的全部数据。public 字段，服务层装配时直接填。 */
    public static final class Model {
        public LocalDate date;
        /** 当日记录（系统七数 + 温度/阶段/九维 + 主线龙头 + 仓位/笔记）。null = 这天没有记录。 */
        public DailyRecord today;
        /** 上一交易日记录，只为【三】今昨对照表。null = 找不到上一条。 */
        public DailyRecord prev;
        public List<IndexClose> indexes = new ArrayList<>();
        /** 当日盘面明细（连板梯队/跌停/大面）。null 或其 available=false = 没有明细。 */
        public MarketStocksVO stocks;
        public List<Position> positions = new ArrayList<>();
        /** 当日预判（PLAN）+ 当日对答案（ANSWER）混在一起，渲染时按 kind 分流。 */
        public List<Prediction> predictions = new ArrayList<>();
        public List<Anchor> anchors = new ArrayList<>();
        /** 只有那天导入过 md 才带得出题材（无日粒度）。空 = 无从带出。 */
        public List<ThemeRow> themes = new ArrayList<>();
        /** 小节键 → 他写的判断原文（含从元宝/豆包贴来的答案）。平台不解析内容。 */
        public Map<String, String> notes = new LinkedHashMap<>();
    }

    public static String render(Model m) {
        if (m == null || m.date == null) {
            throw new IllegalArgumentException("导出复盘文档需要 date");
        }
        List<String> parts = new ArrayList<>();
        parts.add(title(m));
        parts.add(sectionIndex(m));
        parts.add(sectionTheme(m));
        parts.add(sectionEmotion(m));
        parts.add(sectionPosition(m));
        parts.add(sectionAnswer(m));
        parts.add(sectionPlan(m));
        parts.add(sectionAnchor(m));
        parts.add(sectionStrategy(m));
        String orphans = orphanNotes(m);
        if (orphans != null) {
            parts.add(orphans);
        }
        parts.add(disclaimer());
        return String.join("\n\n", parts).trim() + "\n";
    }

    // ---- 标题 ----

    private static String title(Model m) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(weekday(m.date)).append("（").append(md(m.date)).append("）复盘");
        DailyRecord r = m.today;
        if (r != null && notBlank(r.getStage())) {
            String label = CycleStageMachine.label(r.getStage(), r.getStagePhase(), r.getStageSeq());
            sb.append(" · ").append(label.isEmpty() ? r.getStage() : label);
            if (r.getStageOverridden() != null && r.getStageOverridden() == 1) {
                sb.append("（已人工改判）");
            }
        }
        return sb.toString();
    }

    // ---- 【一、指数与量能】 ----

    private static String sectionIndex(Model m) {
        StringBuilder sb = head("【一、指数与量能】");
        for (Map.Entry<String, String> e : ReviewImportParser.KNOWN_INDEXES.entrySet()) {
            IndexClose ic = findIndex(m.indexes, e.getKey());
            sb.append("- **").append(e.getValue()).append("**：");
            if (ic == null) {
                sb.append("—\n");
                continue;
            }
            sb.append(ic.getClosePrice() == null ? "—" : plain(ic.getClosePrice()));
            if (ic.getChangePct() != null) {
                sb.append("（").append(signed(ic.getChangePct(), "%")).append("）");
            }
            sb.append("\n");
        }
        DailyRecord r = m.today;
        if (r == null) {
            sb.append("\n> 这天还没有系统读数（没拉过行情），成交/涨跌家数/涨停跌停/连板高度都无从取。\n");
        } else {
            sb.append("- **全市场成交**：").append(vol(r.getTotalVolume())).append("\n");
            sb.append("- **上涨/下跌**：").append(breadth(r.getUpCount(), r.getDownCount())).append("\n");
            sb.append("- **涨停/跌停**：").append(plain(r.getLimitUpCount())).append(" / ")
                    .append(plain(r.getLimitDownCount())).append("\n");
            sb.append("- **连板高度**：").append(plain(r.getMaxConsecutiveLimit())).append(" 板\n");
            sb.append("\n").append(tempLine(r)).append("\n");
        }
        return prompt(m, "index", sb, "核心定性：这波是转强、弱修复还是退潮？指数和个股背离吗？量能与家数支持你的定性吗？");
    }

    private static String tempLine(DailyRecord r) {
        StringBuilder sb = new StringBuilder("> 系统读数：温度 ");
        sb.append(r.getTemperature() == null ? "—" : plain(r.getTemperature())).append("°");
        String label = CycleStageMachine.label(r.getStage(), r.getStagePhase(), r.getStageSeq());
        sb.append(" · 阶段 ").append(label.isEmpty() ? dash(r.getStage()) : label);
        sb.append(" · 总分 ").append(dash(r.getTotalScore()));
        sb.append(" · 进分 ").append(r.getScoredDims() == null ? "—" : r.getScoredDims() + "/9 维");
        return sb.toString();
    }

    // ---- 【二、板块主线】 ----

    private static String sectionTheme(Model m) {
        StringBuilder sb = head("【二、板块主线（按强度排序）】");
        DailyRecord r = m.today;
        if (r != null) {
            sb.append("- **主线**：").append(dash(r.getMainTheme())).append("\n");
            String leader = dash(r.getLeadingStock());
            if (notBlank(r.getLeadingStockStatus())) {
                leader = leader + " · " + r.getLeadingStockStatus();
            }
            sb.append("- **总龙头**：").append(leader).append("\n");
            sb.append("- **中军**：").append(dash(r.getMidCapStock())).append("\n");
        } else {
            sb.append("- **主线/龙头**：—\n");
        }
        if (m.themes != null && !m.themes.isEmpty()) {
            sb.append("- **题材（来自那天导入的原文）**：\n");
            for (ThemeRow t : m.themes) {
                sb.append("  - ").append(dash(t.getTheme()));
                if (t.getStrength() != null) {
                    sb.append(" 强度 ").append(t.getStrength());
                }
                if (notBlank(t.getStatus())) {
                    sb.append(" · ").append(t.getStatus());
                }
                if (notBlank(t.getLeaderName())) {
                    sb.append(" · 龙头 ").append(t.getLeaderName());
                }
                sb.append("\n");
            }
        } else {
            sb.append("- **题材**：这天没导入过带 `题材:` 的原文，无从带出（题材无日粒度）。"
                    + "要写主线强度请在复盘里补，或看仪表盘涨停池的行业分布。\n");
        }
        return prompt(m, "theme", sb, "每条主线一句「翻译」：是板块合力还是个股穿越？一字独食还是换手？");
    }

    // ---- 【三、情绪与连板生态】 ----

    private static String sectionEmotion(Model m) {
        StringBuilder sb = head("【三、情绪与连板生态】");
        sb.append("| 指标 | ").append(todayHead(m)).append(" | ").append(prevHead(m)).append(" | 变化 |")
                .append("\n|---|---|---|---|\n");
        compareRow(sb, "温度(°)", decimal(m.today, DailyRecord::getTemperature),
                decimal(m.prev, DailyRecord::getTemperature), 1);
        compareRow(sb, "成交额(亿)", decimal(m.today, DailyRecord::getTotalVolume),
                decimal(m.prev, DailyRecord::getTotalVolume), 2);
        compareRow(sb, "涨停家数", count(m.today, DailyRecord::getLimitUpCount),
                count(m.prev, DailyRecord::getLimitUpCount), 0);
        compareRow(sb, "跌停家数", count(m.today, DailyRecord::getLimitDownCount),
                count(m.prev, DailyRecord::getLimitDownCount), 0);
        compareRow(sb, "上涨家数", count(m.today, DailyRecord::getUpCount),
                count(m.prev, DailyRecord::getUpCount), 0);
        compareRow(sb, "下跌家数", count(m.today, DailyRecord::getDownCount),
                count(m.prev, DailyRecord::getDownCount), 0);
        compareRow(sb, "连板高度", count(m.today, DailyRecord::getMaxConsecutiveLimit),
                count(m.prev, DailyRecord::getMaxConsecutiveLimit), 0);
        sb.append("\n");
        if (m.today != null) {
            sb.append("**九维读数**（分 −1..3，未评的维不进分母）\n\n")
                    .append(ReviewMdFormatter.dimTable(m.today)).append("\n");
        }
        sb.append(ladder(m.stocks));
        return prompt(m, "emotion", sb, "今日最关键的变化是什么？情绪是修复还是退潮？");
    }

    private static String ladder(MarketStocksVO s) {
        StringBuilder sb = new StringBuilder();
        if (s == null || !s.isAvailable()) {
            sb.append("- **连板梯队**：—（这天没有盘面明细，拉一次行情快照才会有）\n");
            return sb.toString();
        }
        if (s.getLadder().isEmpty()) {
            sb.append("- **连板梯队**：无 2 板以上（全是首板）\n");
        } else {
            List<String> tiers = new ArrayList<>();
            for (MarketStocksVO.Tier t : s.getLadder()) {
                List<String> names = new ArrayList<>();
                for (MarketStocksVO.Item it : t.getStocks()) {
                    names.add(it.getName());
                }
                tiers.add(t.getBoard() + "板·" + String.join("、", names));
            }
            sb.append("- **连板梯队**：").append(String.join(" ｜ ", tiers)).append("\n");
        }
        sb.append("- **首板**：").append(s.getFirstBoardCount()).append(" 家");
        if (!s.getGapBoards().isEmpty()) {
            List<String> gaps = new ArrayList<>();
            for (Integer g : s.getGapBoards()) {
                gaps.add(String.valueOf(g));
            }
            sb.append("（断档 ").append(String.join("、", gaps)).append(" 板）");
        }
        sb.append("\n");
        sb.append("- **跌停 ").append(s.getLimitDownCount()).append(" 家**：")
                .append(names(s.getLimitDown())).append("\n");
        if (!s.getBigLoss().isEmpty()) {
            sb.append("- **大面**：").append(names(s.getBigLoss())).append("\n");
        }
        return sb.toString();
    }

    // ---- 【四、持仓处理评价】 ----

    private static String sectionPosition(Model m) {
        StringBuilder sb = head("【四、持仓处理评价】");
        if (m.positions == null || m.positions.isEmpty()) {
            sb.append("（这天没有导入过持仓台账）\n");
            return prompt(m, "position", sb, "有持仓就逐只写「实际动作 vs 应做动作 vs 纪律」；空仓写为什么空。");
        }
        sb.append("| 标的 | 成本 | 现价 | 浮动 | 动作 | 应做 | 纪律 |\n|---|---|---|---|---|---|---|\n");
        for (Position p : m.positions) {
            sb.append("| ").append(cell(p.getStockName())).append(" ").append(cell(p.getStockCode())).append(" | ")
                    .append(price(p.getCostPrice())).append(" | ")
                    .append(price(p.getCurrentPrice())).append(" | ")
                    .append(p.getFloatPct() == null ? "—" : signed(p.getFloatPct(), "%")).append(" | ")
                    .append(cell(p.getAction())).append(" | ")
                    .append(cell(p.getPlannedAction())).append(" | ")
                    .append(cell(p.getDiscipline())).append(" |\n");
        }
        return prompt(m, "position", sb, "连续「应做未做」是这块最该沉淀的东西——写清楚为什么没执行。");
    }

    // ---- 【五、对答案与兑现】 ----

    private static String sectionAnswer(Model m) {
        StringBuilder sb = head("【五、对答案（昨日预判今天兑现了吗）】");
        List<Prediction> answers = filter(m.predictions, true);
        if (answers.isEmpty()) {
            sb.append("（今天没有登记 `对答案`——即没有回填上一交易日的同名预判）\n");
            return prompt(m, "answer", sb, "错在定位 / 选股 / 执行哪一层？");
        }
        sb.append("| 昨日预判 | 兑现 | 说明 |\n|---|---|---|\n");
        for (Prediction a : answers) {
            sb.append("| ").append(cell(a.getName())).append(" | ")
                    .append(cell(a.getResult())).append(" | ")
                    .append(cell(a.getResultNote())).append(" |\n");
        }
        return prompt(m, "answer", sb, "命中/落空背后的原因，一句话。");
    }

    // ---- 【六、次日预期 · 三路径】 ----

    private static String sectionPlan(Model m) {
        LocalDate next = nextSession(m.date);
        StringBuilder sb = head("【六、" + weekday(next) + "（" + md(next) + "）预期 · 三路径】");
        sb.append("> 次日日期按日历近似（跳过周末），非交易日历。\n\n");
        List<Prediction> plans = filter(m.predictions, false);
        if (plans.isEmpty()) {
            sb.append("（没有登记的明日预判）\n");
            return prompt(m, "plan", sb, "写核心定性 + 三路径的概率、触发条件、每路径仓位上限。");
        }
        for (Prediction p : plans) {
            sb.append("- **").append(dash(p.getName())).append("**：");
            if (p.getProb() != null) {
                sb.append("概率 ").append(p.getProb()).append("%");
            }
            if (notBlank(p.getConditionText())) {
                sb.append(p.getProb() != null ? " ｜触发 " : "触发 ").append(oneLine(p.getConditionText()));
            }
            sb.append("\n");
        }
        return prompt(m, "plan", sb, "这三条路径你各押多少仓？触发信号看哪个？");
    }

    // ---- 【七、关键锚点】 ----

    private static String sectionAnchor(Model m) {
        StringBuilder sb = head("【七、关键锚点】");
        if (m.anchors == null || m.anchors.isEmpty()) {
            sb.append("（这天没有在册的周期阵眼/总龙——去「主线龙头」页登记）\n");
            return prompt(m, "anchor", sb, "这一轮的阵眼是谁？它今天给了什么信号？");
        }
        for (Anchor a : m.anchors) {
            sb.append("- **").append(dash(a.getStockName())).append("（").append(dash(a.getStockCode()))
                    .append("）** · ").append(roleCn(a.getRole())).append(" · ").append(span(a));
            if (notBlank(a.getNote())) {
                sb.append("：").append(oneLine(a.getNote()));
            }
            sb.append("\n");
        }
        return prompt(m, "anchor", sb, "这些锚点今天各给了什么信号？断板/跌破的有没有？");
    }

    // ---- 【八、仓位与总策略】 ----

    private static String sectionStrategy(Model m) {
        StringBuilder sb = head("【八、仓位与总策略】");
        DailyRecord r = m.today;
        if (r != null) {
            sb.append("- **我的仓位**：").append(r.getMyPositionPct() == null ? "—"
                    : plain(r.getMyPositionPct()) + " 成").append("\n");
            sb.append("- **明日计划**：").append(cell(r.getTomorrowPlan())).append("\n");
            sb.append("- **轮动观察**：").append(cell(r.getRotationNote())).append("\n");
        } else {
            sb.append("- **我的仓位 / 明日计划**：—\n");
        }
        return prompt(m, "strategy", sb, "综合上面的判断，明天总仓位上限多少？单票上限？绝对回避哪些？");
    }

    private static String disclaimer() {
        return "## 【九、免责声明】\n\n"
                + "> 以上行情数、温度/阶段、九维、连板梯队、持仓、预判、锚点均为平台系统取数自动生成，"
                + "只作记录与复盘用，不构成任何买卖建议；判断与交易决策由你自己写、自己负责。";
    }

    // ---- 版式小工具 ----

    private static StringBuilder head(String title) {
        StringBuilder sb = new StringBuilder("## ").append(title).append("\n\n");
        return sb;
    }

    /**
     * 收一节：他写过判断就放他的原文（含从元宝/豆包贴来的答案），没写过才留一行写作提示。
     *
     * <p>原文一律并进同一个引用块：贴进来的东西段落数不可控，
     * 空行不补 {@code >} 会让引用块断掉、后半截变成裸正文，排版就散了。
     */
    private static String prompt(Model m, String key, StringBuilder sb, String hint) {
        String text = m == null || m.notes == null ? null : m.notes.get(key);
        if (text != null && !text.trim().isEmpty()) {
            sb.append("\n").append(blockquote("判断", text)).append("\n");
        } else {
            sb.append("\n> ✍️ 判断：").append(hint).append("\n");
        }
        return trim(sb);
    }

    /** 一段可能多行的文字，包成一个 markdown 引用块。 */
    private static String blockquote(String label, String text) {
        StringBuilder out = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (i == 0) {
                out.append("> **").append(label).append("**：").append(line);
            } else {
                out.append(line.isEmpty() ? ">" : "> " + line);
            }
            if (i < lines.length - 1) {
                out.append("\n");
            }
        }
        return out.toString();
    }

    /**
     * 键不在 {@link #NOTE_KEYS} 里的正文。渲染器不猜它该进哪一节，但也绝不把它变没——
     * 单列一节挂到免责声明前面，让人一眼看到"这份存了一段挂不上号的字"。
     */
    private static String orphanNotes(Model m) {
        if (m.notes == null || m.notes.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : m.notes.entrySet()) {
            String text = entry.getValue();
            if (entry.getKey() == null || text == null || text.trim().isEmpty()
                    || NOTE_KEYS.contains(entry.getKey())) {
                continue;
            }
            if (sb.length() == 0) {
                sb.append("## 【附、未归节的文字】\n\n");
            }
            sb.append(blockquote(entry.getKey(), text)).append("\n\n");
        }
        return sb.length() == 0 ? null : trim(sb);
    }

    private static String trim(StringBuilder sb) {
        String s = sb.toString().replaceAll("[ \\t]+\\n", "\n");
        while (s.endsWith("\n")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ---- 取值 / 格式化 ----

    private static String vol(BigDecimal v) {
        return v == null ? "—" : plain(v) + " 亿";
    }

    /** 价格是 DECIMAL(10,3) 补出来的（11.170）；A 股报价两位，就按两位写。 */
    private static String price(BigDecimal v) {
        return v == null ? "—" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String breadth(Integer up, Integer down) {
        if (up == null || down == null) {
            return plain(up) + " / " + plain(down);
        }
        int total = up + down;
        String pct = total == 0 ? "—" : Math.round(up * 100.0 / total) + "%";
        return up + " / " + down + "（上涨占比 " + pct + "）";
    }

    private static String signed(BigDecimal v, String unit) {
        return ReviewMdFormatter.signed(v, unit);
    }

    private static String plain(Object v) {
        return ReviewMdFormatter.plain(v);
    }

    private static String dash(Object v) {
        return ReviewMdFormatter.dash(v);
    }

    /** 表格里的一格：null/空 → —，竖线换成全角以免撑破表格。 */
    private static String cell(Object v) {
        if (v == null) {
            return "—";
        }
        String s = String.valueOf(v).trim().replace("|", "｜").replace("\r", "").replace("\n", " ");
        return s.isEmpty() ? "—" : s;
    }

    private static String oneLine(String v) {
        return v == null ? "" : v.replace("\r", " ").replace("\n", " ").trim();
    }

    /** 表头写实际日期：prev 是「库里上一条记录」，在没记录的日子上并不等于昨天。 */
    private static String todayHead(Model m) {
        return "今日 " + md(m.date);
    }

    private static String prevHead(Model m) {
        LocalDate d = m.prev == null ? null : m.prev.getTradeDate();
        return d == null ? "上一记录" : "上一记录 " + md(d);
    }

    /** 今昨对照一行：今日、昨日、带符号差（都为空则整行 —）。scale=0 按整数差。 */
    private static void compareRow(StringBuilder sb, String label, BigDecimal today, BigDecimal prev, int scale) {
        sb.append("| ").append(label).append(" | ").append(num(today, scale)).append(" | ")
                .append(num(prev, scale)).append(" | ").append(delta(today, prev, scale)).append(" |\n");
    }

    private static String num(BigDecimal v, int scale) {
        return v == null ? "—" : v.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static String delta(BigDecimal today, BigDecimal prev, int scale) {
        if (today == null || prev == null) {
            return "—";
        }
        BigDecimal d = today.subtract(prev).setScale(scale, RoundingMode.HALF_UP);
        int cmp = d.signum();
        if (cmp == 0) {
            return "持平";
        }
        return (cmp > 0 ? "+" : "") + d.toPlainString();
    }

    private static BigDecimal decimal(DailyRecord r, Func<DailyRecord, BigDecimal> getter) {
        return r == null ? null : getter.apply(r);
    }

    private static BigDecimal count(DailyRecord r, Func<DailyRecord, Integer> getter) {
        if (r == null) {
            return null;
        }
        Integer v = getter.apply(r);
        return v == null ? null : BigDecimal.valueOf(v);
    }

    /** 避免为几个方法引用引 java.util.function 的泛型噪音。 */
    private interface Func<T, R> {
        R apply(T t);
    }

    private static IndexClose findIndex(List<IndexClose> list, String code) {
        if (list == null) {
            return null;
        }
        for (IndexClose ic : list) {
            if (code.equals(ic.getIndexCode())) {
                return ic;
            }
        }
        return null;
    }

    private static List<Prediction> filter(List<Prediction> list, boolean answer) {
        List<Prediction> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (Prediction p : list) {
            boolean isAnswer = Prediction.KIND_ANSWER.equals(p.getKind());
            if (isAnswer == answer) {
                out.add(p);
            }
        }
        return out;
    }

    private static String names(List<MarketStocksVO.Item> items) {
        if (items == null || items.isEmpty()) {
            return "—";
        }
        List<String> ns = new ArrayList<>();
        for (MarketStocksVO.Item it : items) {
            ns.add(it.getName());
        }
        return String.join("、", ns);
    }

    private static String roleCn(String role) {
        if (AnchorServiceRole.CYCLE.equals(role)) {
            return "周期阵眼";
        }
        if (AnchorServiceRole.LEADER.equals(role)) {
            return "周期总龙";
        }
        return dash(role);
    }

    private static String span(Anchor a) {
        if (a.getStartDate() == null) {
            return "起点未设";
        }
        return md(a.getStartDate()) + (a.getEndDate() == null ? " 起在位" : " 至 " + md(a.getEndDate()));
    }

    // ---- 日期 ----

    private static final String[] CN_WEEK = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static String weekday(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return CN_WEEK[dow.getValue() - 1];
    }

    private static String md(LocalDate d) {
        return d.getMonthValue() + "/" + d.getDayOfMonth();
    }

    /** 下一日历日，跳过周六日。只是排版用的近似，不是交易日历。 */
    private static LocalDate nextSession(LocalDate d) {
        LocalDate n = d.plusDays(1);
        while (n.getDayOfWeek() == DayOfWeek.SATURDAY || n.getDayOfWeek() == DayOfWeek.SUNDAY) {
            n = n.plusDays(1);
        }
        return n;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 与 AnchorService 的 role 常量对齐；在此私有化只为渲染层不反向依赖 service。 */
    private static final class AnchorServiceRole {
        static final String CYCLE = "CYCLE";
        static final String LEADER = "LEADER";

        private AnchorServiceRole() {
        }
    }
}
