package com.emotion.util;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 复盘 md 的 ```meta 块解析器。纯函数：文本进、{@link ReviewDoc} 出，不查库、不联网、不要 Spring。
 *
 * <p>语法（和 复盘模板.md 里写死的那套一致）：
 * <ul>
 *   <li>一行一条 {@code 键: 值}；中文冒号和英文冒号都接受当分隔符，但<b>值里不能再出现英文冒号</b>；</li>
 *   <li>{@code #} 开头是注释，空行跳过；</li>
 *   <li>多行键（持仓/题材/预判/指数）的值是<b>标签段</b>：前导自由文本 + {@code 标签紧跟值}，
 *       值的边界是下一个已知标签，所以 {@code 应做次日竞价止损} 这种带空格的值写得下来；</li>
 *   <li>键<b>没写</b> = 这天没说，库里不动；键写了<b>空值</b> = 明确要求清空。
 *       这两个语义必须分得开，否则改一个字就得从头再贴一遍。</li>
 * </ul>
 *
 * <p>坏行<b>一次全报</b>而不是撞到第一个就停：整块拒绝的前提下，逐条试错等于让你把同一份文件贴五遍。
 * 代码是否真实存在于 t_stock 不在这里判——那是查库的事，留给 service，好让这个类能被单测直接喂字符串。
 */
public final class ReviewImportParser {

    private ReviewImportParser() {
    }

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final String FENCE_OPEN = "```meta";
    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern NUMBER = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");
    private static final Pattern CODE = Pattern.compile("^\\d{6}$");
    private static final Pattern UP_DOWN = Pattern.compile("^\\d+\\s*/\\s*\\d+$");

    /** 单值键。 */
    public static final List<String> SINGLE_KEYS = Collections.unmodifiableList(Arrays.asList(
            "date", "主线", "主线明确度", "总龙头", "龙头状态", "中军",
            "轮动观察", "明日计划", "我的仓位", "涨跌家数", "对照"));

    /** 多行键，一天可以出现任意条。 */
    public static final List<String> MULTI_KEYS = Collections.unmodifiableList(Arrays.asList(
            "持仓", "题材", "预判", "对答案", "指数"));

    /**
     * 系统自己取的七个数加两个派生读数。写进导入块一律<b>拒绝并说明理由</b>，而不是报"未知键"——
     * 真实分歧已经存在过：9/3 你记 46 涨停 / 17 跌停，系统从东财池子取到 44 / 16。
     * 两边都往同一张表里写，事后没人知道该信谁。要留手记数请写在正文里，或用 {@code 对照:} 键。
     */
    public static final Map<String, String> SYSTEM_OWNED = systemOwned();

    private static Map<String, String> systemOwned() {
        Map<String, String> m = new LinkedHashMap<>();
        String bySnapshot = "这个数由 GET /api/market/snapshot 自己取，手写值不接收；要留对照请写在正文或 对照: 键";
        for (String key : Arrays.asList("连板高度", "涨停", "涨停家数", "跌停", "跌停家数", "涨跌停",
                "炸板率", "大面", "大面数", "成交额", "成交")) {
            m.put(key, bySnapshot);
        }
        m.put("溢价", "分档溢价由 t_premium_tier 现算，手写值（尤其含首板那种口径）会和打分口径打架");
        m.put("昨日溢价", "同上");
        m.put("温度", "温度是九维打分的产物，从来不该被录入");
        m.put("阶段", "阶段是 determineStage 的产物；人工改判请走复盘页的改判开关，会记 stage_overridden");
        return Collections.unmodifiableMap(m);
    }

    public static final Set<String> LEADER_STATUS = unmodifiable("加速", "滞涨", "断板", "反包", "正常");
    public static final Set<String> DISCIPLINE = unmodifiable("遵守", "违约", "待执行");
    public static final Set<String> ANSWER_RESULT = unmodifiable("命中", "落空", "部分", "违约");
    public static final Set<String> THEME_STATUS = unmodifiable("萌芽", "确认", "扩散", "亢奋", "退潮");
    public static final Set<String> SCORE_THEME = unmodifiable("3", "1", "0");

    /** 五只默认指数。不在里面只警告不拒绝——你想加万得全A是你的自由。 */
    public static final Map<String, String> KNOWN_INDEXES = knownIndexes();

    private static Map<String, String> knownIndexes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("000001", "上证指数");
        m.put("399001", "深证成指");
        m.put("399006", "创业板指");
        m.put("000688", "科创50");
        m.put("899050", "北证50");
        return Collections.unmodifiableMap(m);
    }

    private static Set<String> unmodifiable(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    /** 每类多行键允许的标签。值的边界就是靠这份表切出来的。 */
    private static final List<String> POSITION_LABELS =
            Arrays.asList("成本", "现价", "浮动", "动作", "应做", "纪律");
    private static final List<String> THEME_LABELS = Arrays.asList("强度", "状态", "龙头");
    private static final List<String> PLAN_LABELS = Arrays.asList("概率", "条件");
    private static final List<String> INDEX_LABELS = Arrays.asList("收盘", "涨跌");

    /** 哨兵：标签写了但值不是合法数字。用它而不是 null，因为 null 表示"标签没写"。 */
    private static final BigDecimal NOT_A_NUMBER = new BigDecimal("999999999.99");
    private static final Integer NOT_AN_INT = Integer.valueOf(-999999);

    public static ReviewDoc parse(String markdown) {
        return parse(markdown, LocalDate.now(CN));
    }

    /**
     * @param today 用来判"日期不得晚于今天"和周末提醒。显式传进来而不是读系统时钟，
     *              这样单测能钉住边界——跨零点写复盘是常态。
     */
    public static ReviewDoc parse(String markdown, LocalDate today) {
        ReviewDoc doc = new ReviewDoc();
        if (markdown == null || markdown.trim().isEmpty()) {
            doc.addError(0, null, "内容是空的", "至少要有一个 ```meta 围栏块和一条 date");
            return doc;
        }

        String[] lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        int open = -1;
        int contentFrom = -1;
        int close = -1;
        int extraFence = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (open < 0) {
                if (t.startsWith(FENCE_OPEN)) {
                    open = i;
                    contentFrom = i + 1;
                }
                continue;
            }
            if (close < 0) {
                if (t.startsWith("```")) {
                    close = i;
                }
                continue;
            }
            if (t.startsWith(FENCE_OPEN)) {
                extraFence = i;
                break;
            }
        }
        if (open < 0) {
            doc.addError(1, null, "没找到 ```meta 围栏块", "整份文件里要有且只有一个 ```meta … ``` 块");
            return doc;
        }
        if (extraFence >= 0) {
            doc.addError(open + 1, null, "出现了第二个 ```meta 块（第 " + (extraFence + 1) + " 行）",
                    "有且只有一个；多个块没人知道该以哪份为准");
            return doc;
        }
        if (close < 0) {
            doc.addError(open + 1, null, "```meta 块没有闭合", "块尾要有单独一行的 ```");
            return doc;
        }

        for (int i = 0; i < lines.length; i++) {
            boolean inBlock = i >= contentFrom && i < close;
            String trimmed = lines[i].trim();
            if (!inBlock) {
                if (i != open && i != close && !trimmed.isEmpty()) {
                    doc.setProse(true);
                }
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            parseLine(doc, i + 1, trimmed, today);
        }

        finish(doc);
        Collections.sort(doc.getErrors(), (a, b) -> Integer.compare(a.getLine(), b.getLine()));
        return doc;
    }

    // ---- 单行 ----

    private static void parseLine(ReviewDoc doc, int lineNo, String raw, LocalDate today) {
        int sep = indexOfSeparator(raw);
        if (sep < 0) {
            doc.addError(lineNo, null, "这一行不是 键: 值 的形式", "例如 主线: 液冷服务器");
            return;
        }
        String key = raw.substring(0, sep).trim();
        String value = raw.substring(sep + 1).trim();
        if (key.isEmpty()) {
            doc.addError(lineNo, null, "冒号左边是空的，键名没了", "例如 主线: 液冷服务器");
            return;
        }
        String owned = SYSTEM_OWNED.get(key);
        if (owned != null) {
            doc.addError(lineNo, key, owned, "删掉这一行");
            return;
        }
        boolean single = SINGLE_KEYS.contains(key);
        boolean multi = MULTI_KEYS.contains(key);
        if (!single && !multi) {
            doc.addError(lineNo, key, "不认识的键" + nearKeys(key), allKeys());
            return;
        }
        if (value.contains(":")) {
            doc.addError(lineNo, key, "值里有英文冒号，程序会读错边界", "分段请用空格或分号");
            return;
        }
        if (multi) {
            parseMulti(doc, key, lineNo, value);
        } else {
            parseSingle(doc, key, lineNo, value, today);
        }
    }

    /** 第一个出现的半角或全角冒号。 */
    private static int indexOfSeparator(String raw) {
        int half = raw.indexOf(':');
        int full = raw.indexOf('：');
        if (half < 0) {
            return full;
        }
        return full < 0 ? half : Math.min(half, full);
    }

    private static void parseSingle(ReviewDoc doc, String key, int lineNo, String value, LocalDate today) {
        if ("date".equals(key)) {
            parseDate(doc, lineNo, value, today);
            return;
        }
        ReviewDoc.Value existing = doc.getSingles().get(key);
        if (existing != null) {
            doc.addError(lineNo, key, "这个键出现了两次（第 " + existing.getLine() + " 行已经有一次）",
                    "单值键一天只能写一条；要写多条请用 持仓: / 题材: / 预判: / 指数: / 对答案:");
            return;
        }
        switch (key) {
            case "主线明确度":
                if (!value.isEmpty() && !SCORE_THEME.contains(value)) {
                    doc.addError(lineNo, key, "取值只能是 3 / 1 / 0，收到「" + value + "」",
                            "3 有清晰主线+龙头 / 1 有热点无主线 / 0 无主线 / 留空 = 未判断，整维剔出分母");
                    return;
                }
                break;
            case "龙头状态":
                if (!value.isEmpty() && !LEADER_STATUS.contains(value)) {
                    doc.addError(lineNo, key, "龙头状态只能是 " + enumText(LEADER_STATUS)
                            + "，收到「" + value + "」", "例如 加速");
                    return;
                }
                break;
            case "总龙头":
            case "中军":
                if (!value.isEmpty() && !isCodeName(doc, lineNo, key, value)) {
                    return;
                }
                break;
            case "我的仓位":
                if (!value.isEmpty() && !inRange(doc, lineNo, key, value, 0, 100, "仓位百分比")) {
                    return;
                }
                break;
            case "涨跌家数":
                if (!value.isEmpty() && !UP_DOWN.matcher(value).matches()) {
                    doc.addError(lineNo, key, "要写成 涨/跌 两个整数，收到「" + value + "」", "1846/3570");
                    return;
                }
                break;
            default:
                break;
        }
        doc.getSingles().put(key, new ReviewDoc.Value(value, lineNo));
    }

    private static void parseDate(ReviewDoc doc, int lineNo, String value, LocalDate today) {
        if (doc.getDate() != null) {
            doc.addError(lineNo, "date", "date 写了不止一次", "只留一条");
            return;
        }
        Matcher m = DATE.matcher(value);
        if (!m.matches()) {
            doc.addError(lineNo, "date", "日期格式不对，收到「" + value + "」", "2026-09-03");
            return;
        }
        LocalDate date;
        try {
            date = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
        } catch (RuntimeException e) {
            doc.addError(lineNo, "date", "这个日期不存在：" + value, "2026-09-03");
            return;
        }
        doc.setDate(date);
        doc.getSingles().put("date", new ReviewDoc.Value(value, lineNo));
        if (date.isAfter(today)) {
            doc.addError(lineNo, "date", "日期晚于今天（" + today + "）", "复盘不能写到未来");
        } else if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            doc.addWarning("第 " + lineNo + " 行：date 落在周末，那天没有行情取数，只能当预期笔记看");
        }
    }

    private static boolean isCodeName(ReviewDoc doc, int lineNo, String key, String value) {
        String[] parts = value.split("\\s+");
        if (parts.length != 2 || !CODE.matcher(parts[0]).matches()) {
            doc.addError(lineNo, key, "股票要六位代码打头、名字在后，收到「" + value + "」", "601086 国芳集团");
            return false;
        }
        return true;
    }

    private static boolean inRange(ReviewDoc doc, int lineNo, String key, String value,
                                   int min, int max, String what) {
        if (!NUMBER.matcher(value).matches()) {
            doc.addError(lineNo, key, what + "要写成数字，收到「" + value + "」", "例如 5");
            return false;
        }
        double d = Double.parseDouble(value);
        if (d < min || d > max) {
            doc.addError(lineNo, key, what + "超出 " + min + "~" + max + "，收到 " + value, "请核对");
            return false;
        }
        return true;
    }

    // ---- 多行键 ----

    private static void parseMulti(ReviewDoc doc, String key, int lineNo, String value) {
        if (value.isEmpty()) {
            doc.addError(lineNo, key, "这一行的值是空的", "例如 " + key + ": " + exampleOf(key));
            return;
        }
        switch (key) {
            case "持仓":
                parsePosition(doc, lineNo, value);
                break;
            case "题材":
                parseTheme(doc, lineNo, value);
                break;
            case "预判":
                parsePlan(doc, lineNo, value);
                break;
            case "对答案":
                parseAnswer(doc, lineNo, value);
                break;
            case "指数":
                parseIndex(doc, lineNo, value);
                break;
            default:
                break;
        }
    }

    private static void parsePosition(ReviewDoc doc, int lineNo, String value) {
        Segments seg = split(doc, lineNo, "持仓", value, POSITION_LABELS, exampleOf("持仓"));
        if (seg == null) {
            return;
        }
        String code = codeAndName(doc, lineNo, "持仓", seg.leading, exampleOf("持仓"));
        if (code == null) {
            return;
        }
        String[] head = seg.leading.split("\\s+");
        for (ReviewDoc.PositionRow exist : doc.getPositions()) {
            if (exist.getCode().equals(code)) {
                doc.addError(lineNo, "持仓", code + " 在第 " + exist.getLine() + " 行已经列过一次",
                        "同一只票一天只能一行");
                return;
            }
        }
        String missing = firstMissing(seg.labels, "动作", "应做", "纪律");
        if (missing != null) {
            doc.addError(lineNo, "持仓", "缺标签 " + missing,
                    "成本… 现价… 浮动… 动作… 应做… 纪律…（前三个可省，后三个必填）");
            return;
        }
        BigDecimal cost = numberOrNull(doc, lineNo, "持仓", seg, "成本");
        BigDecimal current = numberOrNull(doc, lineNo, "持仓", seg, "现价");
        BigDecimal flt = numberOrNull(doc, lineNo, "持仓", seg, "浮动");
        if (cost == NOT_A_NUMBER || current == NOT_A_NUMBER || flt == NOT_A_NUMBER) {
            return;
        }
        String discipline = seg.labels.get("纪律");
        if (!discipline.isEmpty() && !DISCIPLINE.contains(discipline)) {
            doc.addError(lineNo, "持仓", "纪律只能是 " + enumText(DISCIPLINE)
                    + "，收到「" + discipline + "」", "连续应做未做是这张表存在的理由，别用同义词");
            return;
        }
        if (discipline.isEmpty()) {
            doc.addWarning("第 " + lineNo + " 行 持仓 " + head[1] + "：纪律没填，这行不参与「连续几次应做未做」统计");
        }
        doc.getPositions().add(new ReviewDoc.PositionRow(lineNo, code, head[1],
                cost, current, flt, seg.labels.get("动作"), seg.labels.get("应做"), discipline));
    }

    private static void parseTheme(ReviewDoc doc, int lineNo, String value) {
        Segments seg = split(doc, lineNo, "题材", value, THEME_LABELS, exampleOf("题材"));
        if (seg == null) {
            return;
        }
        if (seg.leading.isEmpty()) {
            doc.addError(lineNo, "题材", "题材名写在最前面，收到「" + value + "」", exampleOf("题材"));
            return;
        }
        String missing = firstMissing(seg.labels, "强度", "状态");
        if (missing != null) {
            doc.addError(lineNo, "题材", "缺标签 " + missing, "强度… 状态…（龙头可选）");
            return;
        }
        Integer strength = intOrNull(doc, lineNo, "题材", seg, "强度", 0, 100);
        if (strength == NOT_AN_INT) {
            return;
        }
        String status = seg.labels.get("状态");
        if (!THEME_STATUS.contains(status)) {
            doc.addError(lineNo, "题材", "题材状态只能是 " + enumText(THEME_STATUS)
                    + "，收到「" + status + "」", "04 篇那五段，写在外面会被当成没填");
            return;
        }
        String leaderCode = null;
        String leaderName = null;
        String leader = seg.labels.get("龙头");
        if (leader != null && !leader.isEmpty()) {
            String[] parts = leader.split("\\s+");
            if (parts.length != 2 || !CODE.matcher(parts[0]).matches()) {
                doc.addError(lineNo, "题材", "龙头要六位代码 + 名称，收到「" + leader + "」", "龙头002909 集泰股份");
                return;
            }
            leaderCode = parts[0];
            leaderName = parts[1];
        }
        for (ReviewDoc.ThemeRow exist : doc.getThemes()) {
            if (exist.getTheme().equals(seg.leading)) {
                doc.addError(lineNo, "题材", "「" + seg.leading + "」在第 " + exist.getLine() + " 行已经列过一次",
                        "一个题材一天一行");
                return;
            }
        }
        doc.getThemes().add(new ReviewDoc.ThemeRow(lineNo, seg.leading, strength, status, leaderCode, leaderName));
    }

    private static void parsePlan(ReviewDoc doc, int lineNo, String value) {
        Segments seg = split(doc, lineNo, "预判", value, PLAN_LABELS, exampleOf("预判"));
        if (seg == null) {
            return;
        }
        if (seg.leading.isEmpty()) {
            doc.addError(lineNo, "预判", "路径名写在最前面，收到「" + value + "」", exampleOf("预判"));
            return;
        }
        if (!seg.labels.containsKey("概率")) {
            doc.addError(lineNo, "预判", "缺标签 概率", "概率55。三路径的概率加起来要能看出你留没留意外");
            return;
        }
        Integer prob = intOrNull(doc, lineNo, "预判", seg, "概率", 0, 100);
        if (prob == NOT_AN_INT) {
            return;
        }
        for (ReviewDoc.PlanRow exist : doc.getPlans()) {
            if (exist.getName().equals(seg.leading)) {
                doc.addError(lineNo, "预判", "路径名「" + seg.leading + "」在第 " + exist.getLine() + " 行已经有一次",
                        "次日对答案靠名称回填，同名两条会撞车");
                return;
            }
        }
        doc.getPlans().add(new ReviewDoc.PlanRow(lineNo, seg.leading, prob, seg.labels.get("条件")));
    }

    private static void parseAnswer(ReviewDoc doc, int lineNo, String value) {
        String[] parts = value.split("\\s+", 3);
        if (parts.length < 2) {
            doc.addError(lineNo, "对答案", "至少要有 路径名 和 结果 两段，收到「" + value + "」",
                    exampleOf("对答案"));
            return;
        }
        if (!ANSWER_RESULT.contains(parts[1])) {
            doc.addError(lineNo, "对答案", "第二段必须是结果，只能是 " + enumText(ANSWER_RESULT)
                    + "，收到「" + parts[1] + "」", "路径名 结果 一句话");
            return;
        }
        String note = parts.length > 2 ? parts[2] : "";
        for (ReviewDoc.AnswerRow exist : doc.getAnswers()) {
            if (exist.getName().equals(parts[0])) {
                doc.addError(lineNo, "对答案", "「" + parts[0] + "」在第 " + exist.getLine() + " 行已经回写过",
                        "一个路径一天一个结论");
                return;
            }
        }
        if (note.isEmpty()) {
            doc.addWarning("第 " + lineNo + " 行 对答案 " + parts[0]
                    + "：没写一句话依据，事后看不出错在定位、选股还是执行");
        }
        doc.getAnswers().add(new ReviewDoc.AnswerRow(lineNo, parts[0], parts[1], note));
    }

    private static void parseIndex(ReviewDoc doc, int lineNo, String value) {
        Segments seg = split(doc, lineNo, "指数", value, INDEX_LABELS, exampleOf("指数"));
        if (seg == null) {
            return;
        }
        String code = codeAndName(doc, lineNo, "指数", seg.leading, exampleOf("指数"));
        if (code == null) {
            return;
        }
        String[] head = seg.leading.split("\\s+");
        if (!seg.labels.containsKey("涨跌")) {
            doc.addError(lineNo, "指数", "缺标签 涨跌", "涨跌+0.02（收盘可省）");
            return;
        }
        BigDecimal change = numberOrNull(doc, lineNo, "指数", seg, "涨跌");
        BigDecimal close = numberOrNull(doc, lineNo, "指数", seg, "收盘");
        if (change == NOT_A_NUMBER || close == NOT_A_NUMBER) {
            return;
        }
        for (ReviewDoc.IndexRow exist : doc.getIndexes()) {
            if (exist.getCode().equals(code)) {
                doc.addError(lineNo, "指数", code + " 在第 " + exist.getLine() + " 行已经有过一行",
                        "一个指数一天一行");
                return;
            }
        }
        if (!KNOWN_INDEXES.containsKey(code)) {
            doc.addWarning("第 " + lineNo + " 行：指数 " + code + " 不在默认那五只里，导入不拦但自动取数取不到它");
        }
        doc.getIndexes().add(new ReviewDoc.IndexRow(lineNo, code, head[1], close, change));
    }

    /** 前导段必须是「六位代码 名称」，返回代码；不合法时报错并返回 null。 */
    private static String codeAndName(ReviewDoc doc, int lineNo, String key, String leading, String example) {
        String[] parts = leading.split("\\s+");
        if (parts.length != 2 || !CODE.matcher(parts[0]).matches()) {
            doc.addError(lineNo, key, "开头要六位代码 + 名称两段，收到「" + leading + "」", example);
            return null;
        }
        return parts[0];
    }

    // ---- 标签段切分 ----

    /** 一次切分的结果：前导自由文本 + 每个标签的值。没出现的标签不在 map 里。 */
    private static final class Segments {
        final String leading;
        final Map<String, String> labels;

        Segments(String leading, Map<String, String> labels) {
            this.leading = leading;
            this.labels = labels;
        }
    }

    /**
     * 按已知标签切值。标签<b>必须落在 token 边界</b>（行首或紧跟空白）才算边界，
     * 这样「状态」不会把「形态」里的两个字吃掉。
     *
     * <p>代价写进模板的硬规则：值里再出现别的标签词会被切开，那一段得改用正文写。
     */
    private static Segments split(ReviewDoc doc, int lineNo, String key,
                                  String value, List<String> labels, String example) {
        List<Integer> starts = new ArrayList<>();
        List<Integer> labelIdx = new ArrayList<>();
        for (int li = 0; li < labels.size(); li++) {
            String label = labels.get(li);
            int from = 0;
            int hit = -1;
            while (true) {
                int at = value.indexOf(label, from);
                if (at < 0) {
                    break;
                }
                if (at == 0 || Character.isWhitespace(value.charAt(at - 1))) {
                    hit = at;
                    break;
                }
                from = at + label.length();
            }
            if (hit >= 0) {
                starts.add(hit);
                labelIdx.add(li);
            }
        }
        if (starts.isEmpty()) {
            doc.addError(lineNo, key, "值里没有任何标签（" + String.join("/", labels) + "）", example);
            return null;
        }
        order(starts, labelIdx);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : value.length();
            String label = labels.get(labelIdx.get(i));
            if (out.containsKey(label)) {
                doc.addError(lineNo, key, "标签 " + label + " 出现了两次", "一个标签一段，重复的没法判你要哪个");
                return null;
            }
            out.put(label, value.substring(start + label.length(), end).trim());
        }
        return new Segments(value.substring(0, starts.get(0)).trim(), out);
    }

    /** 按出现位置排序，两个并行列表一起动——省掉一个小类。 */
    private static void order(List<Integer> starts, List<Integer> labelIdx) {
        for (int i = 1; i < starts.size(); i++) {
            for (int j = i; j > 0 && starts.get(j - 1) > starts.get(j); j--) {
                Collections.swap(starts, j - 1, j);
                Collections.swap(labelIdx, j - 1, j);
            }
        }
    }

    private static String firstMissing(Map<String, String> labels, String... required) {
        for (String r : required) {
            if (!labels.containsKey(r)) {
                return r;
            }
        }
        return null;
    }

    private static BigDecimal numberOrNull(ReviewDoc doc, int lineNo, String key,
                                           Segments seg, String label) {
        String raw = seg.labels.get(label);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (!NUMBER.matcher(raw).matches()) {
            doc.addError(lineNo, key, label + " 不是数字，收到「" + raw + "」", "允许 +8.5 / -6.7 / 70 这种写法");
            return NOT_A_NUMBER;
        }
        return new BigDecimal(raw.replace("+", ""));
    }

    private static Integer intOrNull(ReviewDoc doc, int lineNo, String key,
                                     Segments seg, String label, int min, int max) {
        BigDecimal raw = numberOrNull(doc, lineNo, key, seg, label);
        if (raw == null) {
            return null;
        }
        if (raw == NOT_A_NUMBER) {
            return NOT_AN_INT;
        }
        if (raw.stripTrailingZeros().scale() > 0) {
            doc.addError(lineNo, key, label + " 要整数，收到 " + raw.toPlainString(), "例如 55");
            return NOT_AN_INT;
        }
        int v = raw.intValue();
        if (v < min || v > max) {
            doc.addError(lineNo, key, label + " 要在 " + min + "~" + max + "，收到 " + v, "请核对");
            return NOT_AN_INT;
        }
        return v;
    }

    // ---- 收尾 ----

    private static void finish(ReviewDoc doc) {
        if (doc.getDate() == null && !doc.hasErrors()) {
            doc.addError(0, "date", "整个 meta 块里没有 date", "date: 2026-09-03");
        }
        int sum = 0;
        for (ReviewDoc.PlanRow p : doc.getPlans()) {
            sum += p.getProb() == null ? 0 : p.getProb();
        }
        if (!doc.getPlans().isEmpty() && Math.abs(sum - 100) > 2) {
            doc.addWarning("预判概率合计 " + sum + "，不是 100。差的那 " + (100 - sum)
                    + "% 是你没想到的路径，还是你手上留着仓？");
        }
    }

    private static String enumText(Set<String> values) {
        return String.join(" / ", values);
    }

    private static String allKeys() {
        Set<String> all = new LinkedHashSet<>(SINGLE_KEYS);
        all.addAll(MULTI_KEYS);
        return "请用 " + String.join(" / ", all);
    }

    /** 打错的键多半是漏字或多字，只按包含关系猜，不做编辑距离。 */
    private static String nearKeys(String key) {
        List<String> near = new ArrayList<>();
        for (String k : allKeyList()) {
            if (k.contains(key) || key.contains(k)) {
                near.add(k);
            }
        }
        return near.isEmpty() ? "" : "，你是不是想写 " + String.join(" / ", near) + "？";
    }

    private static List<String> allKeyList() {
        List<String> all = new ArrayList<>(SINGLE_KEYS);
        all.addAll(MULTI_KEYS);
        return all;
    }

    private static String exampleOf(String key) {
        switch (key) {
            case "持仓":
                return "002229 鸿博股份 成本11.17 现价12.12 浮动+8.5 动作未动 应做竞价清仓 纪律违约";
            case "题材":
                return "液冷服务器 强度70 状态扩散 龙头002909 集泰股份";
            case "预判":
                return "退潮延续 概率55 条件 竞业达低开低走+跌停≥20";
            case "对答案":
                return "路径二 命中 跌停扩至17家";
            case "指数":
                return "000001 上证指数 收盘3942.09 涨跌+0.02";
            default:
                return "值";
        }
    }
}
