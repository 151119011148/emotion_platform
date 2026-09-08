package com.emotion.util;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份复盘 md 的解析结果：一条 `键: 值` 都没落地之前的中间表示。
 *
 * <p>这个类刻意不含任何"这行写到哪张表"的知识，也不查库——代码是不是真实存在的股票，
 * 是 {@code ReviewImportService} 拿 t_stock 去问的事。 parser 只管形状和取值范围，
 * 所以它能在单测里直接 new 出来喂字符串，不需要 Spring 上下文。
 *
 * <p>每个行对象都带 {@code line}（全文行号，1 起）。报错必须报行号，
 * 因为你要改的是那份 md 文件而不是这个页面。
 */
@Getter
@Setter
public class ReviewDoc {

    /** meta 块里的 date；缺失或格式不对时为 null，同时 errors 里会有一条。 */
    private LocalDate date;
    /** 围栏块之外有没有正文（用于提示"正文会整篇入库"）。 */
    private boolean prose;

    /** 单值键：键名 → 原文值。已 trim；空串表示"键写了但值空"= 清空，和"键没写"是两回事。 */
    private final Map<String, Value> singles = new LinkedHashMap<>();
    private final List<PositionRow> positions = new ArrayList<>();
    private final List<ThemeRow> themes = new ArrayList<>();
    private final List<PlanRow> plans = new ArrayList<>();
    private final List<AnswerRow> answers = new ArrayList<>();
    private final List<IndexRow> indexes = new ArrayList<>();

    private final List<ParseError> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    /** 一次导入里某个单值键的原文与其行号。 */
    @Getter
    public static class Value {
        private final String raw;
        private final int line;

        public Value(String raw, int line) {
            this.raw = raw;
            this.line = line;
        }

        public boolean isBlank() {
            return raw.isEmpty();
        }
    }

    /** `持仓: 002229 鸿博股份 成本11.17 现价12.12 浮动+8.5 动作未动 应做竞价清仓 纪律违约` */
    @Getter
    public static class PositionRow {
        private final int line;
        private final String code;
        private final String name;
        private final BigDecimal cost;
        private final BigDecimal current;
        private final BigDecimal floatPct;
        private final String action;
        private final String plannedAction;
        private final String discipline;

        public PositionRow(int line, String code, String name, BigDecimal cost, BigDecimal current,
                           BigDecimal floatPct, String action, String plannedAction, String discipline) {
            this.line = line;
            this.code = code;
            this.name = name;
            this.cost = cost;
            this.current = current;
            this.floatPct = floatPct;
            this.action = action;
            this.plannedAction = plannedAction;
            this.discipline = discipline;
        }
    }

    /** `题材: 液冷服务器 强度70 状态扩散 龙头002909 集泰股份` */
    @Getter
    public static class ThemeRow {
        private final int line;
        private final String theme;
        private final Integer strength;
        private final String status;
        private final String leaderCode;
        private final String leaderName;

        public ThemeRow(int line, String theme, Integer strength, String status,
                        String leaderCode, String leaderName) {
            this.line = line;
            this.theme = theme;
            this.strength = strength;
            this.status = status;
            this.leaderCode = leaderCode;
            this.leaderName = leaderName;
        }
    }

    /** `预判: 退潮延续 概率55 条件 竞业达低开低走+跌停≥20` */
    @Getter
    public static class PlanRow {
        private final int line;
        private final String name;
        private final Integer prob;
        private final String condition;

        public PlanRow(int line, String name, Integer prob, String condition) {
            this.line = line;
            this.name = name;
            this.prob = prob;
            this.condition = condition;
        }
    }

    /** `对答案: 路径二 命中 跌停扩至17家+竞业达5板失败` */
    @Getter
    public static class AnswerRow {
        private final int line;
        private final String name;
        private final String result;
        private final String note;

        public AnswerRow(int line, String name, String result, String note) {
            this.line = line;
            this.name = name;
            this.result = result;
            this.note = note;
        }
    }

    /** `指数: 000001 上证指数 收盘3942.09 涨跌+0.02` */
    @Getter
    public static class IndexRow {
        private final int line;
        private final String code;
        private final String name;
        private final BigDecimal close;
        private final BigDecimal changePct;

        public IndexRow(int line, String code, String name, BigDecimal close, BigDecimal changePct) {
            this.line = line;
            this.code = code;
            this.name = name;
            this.close = close;
            this.changePct = changePct;
        }
    }

    /** 一行坏数据。三句话缺一不可：哪一行、哪个键、为什么，外加你本来该写成什么样。 */
    @Getter
    public static class ParseError {
        private final int line;
        private final String key;
        private final String reason;
        private final String expected;

        public ParseError(int line, String key, String reason, String expected) {
            this.line = line;
            this.key = key;
            this.reason = reason;
            this.expected = expected;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (line > 0) {
                sb.append("第 ").append(line).append(" 行");
            }
            if (key != null && !key.isEmpty()) {
                sb.append(sb.length() > 0 ? " · " : "").append(key);
            }
            sb.append(sb.length() > 0 ? "： " : "").append(reason);
            if (expected != null && !expected.isEmpty()) {
                sb.append("（应为 ").append(expected).append("）");
            }
            return sb.toString();
        }
    }

    public void addError(int line, String key, String reason, String expected) {
        errors.add(new ParseError(line, key, reason, expected));
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** 所有坏行按行号拼成一段中文。报错信息和单测断言都读它。 */
    public String errorsText() {
        StringBuilder sb = new StringBuilder();
        for (ParseError e : errors) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.describe());
        }
        return sb.toString();
    }

    /** 键写了就用它的值（含空值 = 清空）；键没写返回 null 表示"这天没说，别动"。 */
    public Value single(String key) {
        return singles.get(key);
    }

    public String textOr(String key, String fallback) {
        Value v = singles.get(key);
        return v == null ? fallback : v.getRaw();
    }
}
