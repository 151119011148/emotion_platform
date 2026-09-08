package com.emotion.util;

import com.emotion.entity.DailyRecord;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@link ReviewDoc} → 复盘 md。{@link ReviewImportParser} 的反向，同样是纯函数：不查库、不联网、不要 Spring。
 *
 * <p>存在的理由是<b>别让格式知识长两份</b>。键表、枚举、多行键的标签形状全部现取解析器的
 * （{@code SINGLE_KEYS} / {@code MULTI_KEYS}），这里一个都不抄——抄一份就意味着改一边、忘一边，
 * 而错的形状是静默的：解析器只会说「不认识的键」。
 *
 * <p>输出<b>一律以 ```meta 块开头</b>，正文跟在后面。这个约定换来一件实用的事：
 * 自检时坏行的行号能直接换算成条目下标，降级才找得准是哪一行。
 *
 * <p>自检回路：生成完自己 parse 回去。吃不回去的那一行<b>降级成 {@code #} 注释</b>并往 doc.warnings
 * 里写一条说明，而不是让整个导出接口失败——少一行数据你能补，一堆坏行等于这次导出白做。
 * 值里的换行折成 {@code ；}、英文冒号折成全角，所以真需要降级的通常是「库里缺了必填项」
 * 或「值里撞上别的标签词」这两类。
 *
 * <p>{@code doc} 会被写入降级说明，所以只把<b>专为渲染新建的</b>那份 doc 传进来。
 */
public final class ReviewMdFormatter {

    private ReviewMdFormatter() {
    }

    /** 正文里那段自动生成的系统读数。重复生成时按这个哨兵整段替换，用户手写的正文一个字都不该丢。 */
    public static final String SNAPSHOT_HEADING = "## 〇、系统读数快照";

    private static final String FENCE_OPEN = "```meta";
    private static final String FENCE_CLOSE = "```";
    private static final Pattern WHITESPACE = Pattern.compile("[\\s　]+");
    /** 每轮至少吃掉一条坏行，正常 1~2 轮就收敛。这个上限只是防死循环，不是给用户看的数。 */
    private static final int MAX_ROUNDS = 60;
    private static final String MULTI_HEADER = "# —— 多行键：行序即排序（题材按强度、预判按概率） ——";

    /**
     * @param metaNotes meta 块顶部附加的注释行，可空
     * @param body      ```meta 块之后的正文，可空
     */
    public static String render(ReviewDoc doc, List<String> metaNotes, String body) {
        if (doc == null || doc.getDate() == null) {
            throw new IllegalArgumentException("生成 md 需要 date：没有日期就拼不出可导入的 meta 块");
        }
        List<Line> lines = build(doc);
        ReviewDoc back = null;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Block block = metaBlock(lines, metaNotes);
            back = ReviewImportParser.parse(block.text, doc.getDate());
            if (back.getErrors().isEmpty()) {
                return withBody(block, body);
            }
            if (degrade(doc, lines, block.lineOf, back) == 0) {
                throw new IllegalStateException("生成的 md 仍然无法通过解析：" + errorsOf(back));
            }
        }
        throw new IllegalStateException("降级 " + MAX_ROUNDS + " 轮仍有坏行，这份数据本身有问题，"
                + "请贴回导入页看错误表：" + errorsOf(back));
    }

    private static String errorsOf(ReviewDoc back) {
        if (back == null) {
            return "（无）";
        }
        return back.errorsText().replace('\n', '；');
    }

    // ---- 一条 meta 行 ----

    /** {@code bare} 是正常形状；被降级时只在前面加 {@code #}，形状本身不改——改了就看不出库里原本是什么。 */
    private static final class Line {
        private final String bare;
        private final String why;
        private boolean commented;

        private Line(String bare, String why) {
            this.bare = bare;
            this.why = why;
        }

        private boolean missing() {
            return bare == null;
        }

        private String text() {
            if (missing()) {
                return "# （拼不出这一行）" + why;
            }
            return commented ? "# " + bare : bare;
        }
    }

    private static List<Line> build(ReviewDoc doc) {
        List<Line> lines = new ArrayList<>();
        // date 是唯一的硬前提：解析器没有 date 就整块判死，所以它由 doc.getDate() 直接打头写出来，
        // 不依赖 singles 里那一格——专为渲染新建的 doc 通常只 setDate。
        lines.add(new Line("date: " + doc.getDate(), "date"));
        for (String key : ReviewImportParser.SINGLE_KEYS) {
            if ("date".equals(key)) {
                continue;
            }
            ReviewDoc.Value v = doc.single(key);
            if (v == null) {
                continue;
            }
            String value = clean(v.getRaw());
            lines.add(new Line(value.isEmpty() ? key + ":" : key + ": " + value, key));
        }
        for (ReviewDoc.PositionRow r : doc.getPositions()) {
            addRow(doc, lines, positionLine(r), "持仓 " + safe(r.getCode()) + "："
                    + "库里缺代码或名称，或者某个值里撞上了 成本/现价/浮动/动作/应做/纪律 这些标签词");
        }
        for (ReviewDoc.ThemeRow r : doc.getThemes()) {
            addRow(doc, lines, themeLine(r), "题材 " + safe(r.getTheme()) + "：缺题材名，"
                    + "或者值里撞上了 强度/状态/龙头 这些标签词");
        }
        for (ReviewDoc.PlanRow r : doc.getPlans()) {
            addRow(doc, lines, planLine(r), "预判：" + "缺路径名，或者条件里撞上了「概率」这个词");
        }
        for (ReviewDoc.AnswerRow r : doc.getAnswers()) {
            addRow(doc, lines, answerLine(r), "对答案：" + safe(r.getName()) + " 缺路径名或结果");
        }
        for (ReviewDoc.IndexRow r : doc.getIndexes()) {
            addRow(doc, lines, indexLine(r), "指数：" + safe(r.getCode()) + " 缺代码、名称或涨跌");
        }
        return lines;
    }

    /** 拼不出来的行（库里缺必填项）一进来就是注释态，并把原因写进 warnings——不等到自检那轮才发现。 */
    private static void addRow(ReviewDoc doc, List<Line> lines, String bare, String why) {
        Line line = new Line(bare, why);
        if (bare == null) {
            doc.addWarning("已省略并降级为注释：" + why);
        }
        lines.add(line);
    }

    private static String positionLine(ReviewDoc.PositionRow r) {
        if (isBlank(r.getCode()) || isBlank(r.getName())) {
            return null;
        }
        StringBuilder sb = start("持仓", r.getCode() + " " + clean(r.getName()));
        num(sb, "成本", r.getCost(), false);
        num(sb, "现价", r.getCurrent(), false);
        num(sb, "浮动", r.getFloatPct(), true);
        text(sb, "动作", r.getAction());
        text(sb, "应做", r.getPlannedAction());
        text(sb, "纪律", r.getDiscipline());
        return sb.toString();
    }

    private static String themeLine(ReviewDoc.ThemeRow r) {
        if (isBlank(r.getTheme())) {
            return null;
        }
        StringBuilder sb = start("题材", clean(r.getTheme()));
        sb.append(" 强度").append(r.getStrength() == null ? "" : String.valueOf(r.getStrength()));
        sb.append(" 状态").append(r.getStatus() == null ? "" : clean(r.getStatus()));
        if (!isBlank(r.getLeaderCode()) && !isBlank(r.getLeaderName())) {
            sb.append(" 龙头").append(r.getLeaderCode()).append(' ').append(clean(r.getLeaderName()));
        }
        return sb.toString();
    }

    private static String planLine(ReviewDoc.PlanRow r) {
        if (isBlank(r.getName())) {
            return null;
        }
        StringBuilder sb = start("预判", clean(r.getName()));
        sb.append(" 概率").append(r.getProb() == null ? "" : String.valueOf(r.getProb()));
        if (!isBlank(r.getCondition())) {
            sb.append(" 条件 ").append(clean(r.getCondition()));
        }
        return sb.toString();
    }

    private static String answerLine(ReviewDoc.AnswerRow r) {
        if (isBlank(r.getName()) || isBlank(r.getResult())) {
            return null;
        }
        StringBuilder sb = start("对答案", clean(r.getName()) + " " + clean(r.getResult()));
        if (!isBlank(r.getNote())) {
            sb.append(' ').append(clean(r.getNote()));
        }
        return sb.toString();
    }

    private static String indexLine(ReviewDoc.IndexRow r) {
        if (isBlank(r.getCode()) || isBlank(r.getName()) || r.getChangePct() == null) {
            return null;
        }
        StringBuilder sb = start("指数", r.getCode() + " " + clean(r.getName()));
        num(sb, "收盘", r.getClose(), false);
        num(sb, "涨跌", r.getChangePct(), true);
        return sb.toString();
    }

    private static StringBuilder start(String key, String head) {
        return new StringBuilder(key).append(": ").append(head);
    }

    /** 必填标签即使值为空也要写出来——解析器判的是「标签在不在」，漏掉标签就是坏行。 */
    private static void text(StringBuilder sb, String label, String value) {
        sb.append(' ').append(label).append(isBlank(value) ? "" : clean(value));
    }

    /** 可选数字标签：库里没值就整段不出现。必填的那些（强度 / 概率）在各自的方法里硬写。 */
    private static void num(StringBuilder sb, String label, BigDecimal value, boolean signed) {
        if (value != null) {
            sb.append(' ').append(label).append(number(value, signed));
        }
    }

    // ---- 拼装与自检 ----

    /**
     * meta 块本身。{@code lineOf[i]} 是第 i 个条目实际落在第几行（1 起）——注释行和那段多行键
     * 小标题都会把行号往后推，降级必须按这张表反查，硬算「行号减 2」会盖错行。
     */
    private static final class Block {
        private final String text;
        private final int[] lineOf;

        private Block(String text, int[] lineOf) {
            this.text = text;
            this.lineOf = lineOf;
        }
    }

    private static Block metaBlock(List<Line> lines, List<String> metaNotes) {
        StringBuilder sb = new StringBuilder(FENCE_OPEN).append('\n');
        int at = 2;
        if (metaNotes != null) {
            for (String note : metaNotes) {
                String one = clean(note);
                if (!one.isEmpty()) {
                    sb.append("# ").append(one).append('\n');
                    at++;
                }
            }
        }
        int[] lineOf = new int[lines.size()];
        boolean headerDone = false;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (!headerDone && !line.missing() && isMulti(line.bare)) {
                sb.append('\n').append(MULTI_HEADER).append('\n');
                at += 2;
                headerDone = true;
            }
            sb.append(line.text()).append('\n');
            lineOf[i] = at++;
        }
        sb.append(FENCE_CLOSE);
        return new Block(sb.toString(), lineOf);
    }

    private static String withBody(Block block, String body) {
        if (body == null || body.trim().isEmpty()) {
            return block.text + "\n";
        }
        return block.text + "\n\n" + body.trim() + "\n";
    }

    private static boolean isMulti(String bare) {
        for (String key : ReviewImportParser.MULTI_KEYS) {
            if (bare.startsWith(key + ":") || bare.startsWith(key + "：")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把自检发现的坏行盖上。返回盖了几条——0 表示坏行不在我能定位的地方
     * （围栏本身被正文里第二个 ```meta 抢掉了之类），那种情况交给调用方抛出去。
     */
    private static int degrade(ReviewDoc doc, List<Line> lines, int[] lineOf, ReviewDoc back) {
        int n = 0;
        for (ReviewDoc.ParseError e : back.getErrors()) {
            int idx = indexOf(lineOf, e.getLine());
            if (idx < 0 || lines.get(idx).commented || lines.get(idx).missing()) {
                continue;
            }
            lines.get(idx).commented = true;
            doc.addWarning("「" + lines.get(idx).bare + "」程序读不回去，已降级成注释：" + e.describe()
                    + "。要留这条数据请改写成正常的值，或挪进正文。");
            n++;
        }
        return n;
    }

    private static int indexOf(int[] lineOf, int line) {
        for (int i = 0; i < lineOf.length; i++) {
            if (lineOf[i] == line) {
                return i;
            }
        }
        return -1;
    }

    // ---- 值与数字 ----

    /**
     * meta 行的值不能带换行，也不能带英文冒号（解析器按第一个冒号切键值，多一个就错位）。
     * 全角冒号安全，所以直接换过去，内容一个字都不删。
     */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replace('\n', '；').replace(':', '：');
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    /** 涨/跌 缺一个就不拼：{@code 1846/} 是坏行，而「半个广度」本来也没有意义。 */
    public static String upDown(Integer up, Integer down) {
        return up == null || down == null ? null : up + "/" + down;
    }

    /** DECIMAL 列带着补出来的 0（22.00 / 8.50），写回 md 时去掉；正数补 + 好和「涨」对齐，零不补。 */
    static String number(BigDecimal v, boolean signed) {
        String s = v.stripTrailingZeros().toPlainString();
        if (signed && v.signum() > 0) {
            s = "+" + s;
        }
        return s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safe(String s) {
        return isBlank(s) ? "（空）" : s.trim();
    }

    // ---- 正文 ----

    /** ```meta 块之外的部分。导入块要重建，正文原样留着——那是你写的，程序没资格改。 */
    public static String proseOf(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return "";
        }
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int open = -1;
        int close = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (open < 0) {
                if (t.startsWith(FENCE_OPEN)) {
                    open = i;
                }
            } else if (t.startsWith(FENCE_CLOSE)) {
                close = i;
                break;
            }
        }
        if (open < 0) {
            return markdown.trim();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i >= open && i <= (close < 0 ? lines.length : close)) {
                continue;
            }
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 换掉正文里那一段系统读数。没有就插在最前面，有就整段替换——哨兵到下一个 {@code ## } 之间
     * 全是它的地盘，所以重复生成不会攒出一堆快照。
     *
     * @param snapshot {@code null} 表示把已有那一段去掉
     */
    public static String replaceSnapshot(String body, String snapshot) {
        String prose = body == null ? "" : body;
        String[] lines = prose.split("\n", -1);
        int from = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith(SNAPSHOT_HEADING)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return snapshot == null ? prose : insertAfterTitle(prose, snapshot.trim());
        }
        int to = lines.length;
        for (int i = from + 1; i < lines.length; i++) {
            if (lines[i].trim().startsWith("## ")) {
                to = i;
                break;
            }
        }
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < from; i++) {
            head.append(lines[i]).append('\n');
        }
        StringBuilder tail = new StringBuilder();
        for (int i = to; i < lines.length; i++) {
            tail.append(lines[i]).append(i < lines.length - 1 ? "\n" : "");
        }
        return join(head.toString().trim(), snapshot == null ? "" : snapshot.trim(), tail.toString().trim());
    }

    /** 快照插在文件标题之后而不是最前面——标题那一行是这份复盘的名字，被自己的读数压住不像话。 */
    private static String insertAfterTitle(String prose, String snapshot) {
        String[] lines = prose.split("\n", -1);
        int at = 0;
        while (at < lines.length && lines[at].trim().isEmpty()) {
            at++;
        }
        if (at < lines.length && lines[at].trim().startsWith("# ") && !lines[at].trim().startsWith("## ")) {
            StringBuilder head = new StringBuilder();
            for (int i = 0; i <= at; i++) {
                head.append(lines[i]).append('\n');
            }
            StringBuilder tail = new StringBuilder();
            for (int i = at + 1; i < lines.length; i++) {
                tail.append(lines[i]).append(i < lines.length - 1 ? "\n" : "");
            }
            return join(head.toString().trim(), snapshot, tail.toString().trim());
        }
        return join(snapshot, "", prose.trim());
    }

    private static String join(String a, String b, String c) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{a, b, c}) {
            if (part != null && !part.isEmpty()) {
                sb.append(sb.length() > 0 ? "\n\n" : "").append(part);
            }
        }
        return sb.toString();
    }

    // ---- 系统读数快照 ----

    /**
     * 七个行情数、九维各分、温度和阶段。放正文不放 meta 是因为导入器<b>一律拒收</b>这些键
     * （见 {@link ReviewImportParser#SYSTEM_OWNED}）——放 meta 里等于每天生成一份自己导不进去的文件。
     */
    public static String snapshot(DailyRecord r) {
        StringBuilder sb = new StringBuilder(SNAPSHOT_HEADING).append("\n\n");
        sb.append("> 这一段由平台生成，导入时不解析。你的手记数和它不一致时写在下面的正文里，或用 `对照:` 键。\n\n");
        if (r == null) {
            return sb.append("这天还没有系统读数——没拉过行情，也就没有温度、阶段和九维分。\n").toString();
        }
        String label = CycleStageMachine.label(r.getStage(), r.getStagePhase(), r.getStageSeq());
        sb.append("温度 ").append(head(r.getTemperature(), "°"))
                .append(" · 阶段 ").append(label.isEmpty() ? dash(r.getStage()) : label)
                .append("（").append(dash(r.getStageDirection())).append("）")
                .append(" · 进分 ").append(r.getScoredDims() == null ? "—" : r.getScoredDims() + "/9")
                .append(" · 总分 ").append(dash(r.getTotalScore()));
        if (r.getStageOverridden() != null && r.getStageOverridden() == 1) {
            sb.append(" · 阶段已人工改判");
        }
        sb.append("\n\n").append(dimTable(r));
        return sb.toString();
    }

    /**
     * 九维各分的读数表，含第 9 维那条脚注。同包的 {@link ReviewDocFormatter} 也要这一张表，
     * 抽出来是为了九维的名字、口径和分列位置只长在一处。
     */
    static String dimTable(DailyRecord r) {
        StringBuilder sb = new StringBuilder("| 维度 | 系统读数 | 分 |\n|---|---|---|\n");
        dim(sb, "1 连板高度", plain(r.getMaxConsecutiveLimit()), r.getScoreHeight());
        dim(sb, "2 分档溢价", signed(r.getPremiumWeighted(), "%") + "（昨日涨停 "
                + signed(r.getYesterdayLimitPremium(), "%") + "）", r.getScorePremium());
        dim(sb, "3 涨停/跌停", plain(r.getLimitUpCount()) + "/" + plain(r.getLimitDownCount()),
                r.getScoreBreadth());
        dim(sb, "4 炸板率", signed(r.getBrokenBoardRate(), "%") + cell(r.getBrokenNote()), r.getScoreBroken());
        dim(sb, "5 大面数", plain(r.getBigLossCount()), r.getScoreLoss());
        dim(sb, "6 成交额", plain(r.getTotalVolume())
                + (r.getTotalVolume() == null ? "" : "亿"), r.getScoreVolume());
        dim(sb, "7 主线明确度", themeClarity(r.getScoreTheme()), r.getScoreTheme());
        dim(sb, "8 周期阵眼", bare(r.getAnchorNote()), r.getAnchorScore());
        sb.append("| 9 异动监管 | ").append(survReading(r)).append(" | — |\n");
        sb.append("\n第 9 维的分不单列存储，只并进总分与温度；表里那个 `—` 是「没有这一列」，不是「那天没评」。\n");
        return sb.toString();
    }

    private static void dim(StringBuilder sb, String name, String reading, Integer score) {
        sb.append("| ").append(name).append(" | ").append(reading)
                .append(" | ").append(score == null ? "—" : String.valueOf(score)).append(" |\n");
    }

    /** 第 7 维的读数就是判断本身，重抄一遍分数没意义，写清三档各指什么。 */
    private static String themeClarity(Integer score) {
        if (score == null) {
            return "未评（整维剔出分母）";
        }
        if (score == 3) {
            return "有清晰主线 + 龙头";
        }
        if (score == 1) {
            return "有热点无主线";
        }
        if (score == 0) {
            return "无主线";
        }
        return String.valueOf(score);
    }

    private static String survReading(DailyRecord r) {
        if (r.getSurvCount() == null && r.getSurvPremium() == null) {
            return "—";
        }
        return (r.getSurvCount() == null ? "—" : r.getSurvCount() + " 家")
                + " / " + signed(r.getSurvPremium(), "%") + cell(r.getSurvNote());
    }

    /** 依据串直接进表格：换行折成空格、竖线换全角，否则整张表被撑歪。 */
    private static String cell(String note) {
        return isBlank(note) ? "" : "（" + clean(note).replace("|", "｜") + "）";
    }

    private static String bare(String note) {
        return isBlank(note) ? "—" : clean(note).replace("|", "｜");
    }

    /** 同包的 {@link ReviewDocFormatter} 复用这三个数字格式化，格式知识只长一份。 */
    static String plain(Object v) {
        if (v == null) {
            return "—";
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }

    static String signed(BigDecimal v, String unit) {
        return v == null ? "—" : number(v, true) + unit;
    }

    private static String head(BigDecimal v, String unit) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString() + unit;
    }

    static String dash(Object v) {
        return v == null || String.valueOf(v).trim().isEmpty() ? "—" : String.valueOf(v);
    }
}
