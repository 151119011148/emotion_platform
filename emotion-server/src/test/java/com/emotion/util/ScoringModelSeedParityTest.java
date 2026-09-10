package com.emotion.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 钉住 {@code schema.sql} 的 t_scoring_model / t_scoring_dim / t_scoring_sub / t_scoring_rule 幂等种子，
 * 逐字段对齐引擎的内置兜底：
 *   - 旧 {@code TemperatureCalculator.builtinModel()} 对应 ultra_short 的 9 维行（dim_key 前缀 @mid）。
 *   - 新 {@code BoardScoreCalculator.builtinTree()} 对应 five_dim 的 5 维 + 全 subs + 各 BAND_LADDER 阶梯（@fid）。
 *
 * <p>为什么值得单独一个测试：改引擎常量与改种子是两条独立通道，任何一条静默漂移都会导致
 * "无 DB 时走内置兜底"与"有 DB 时走注册表"产出两套分数；golden 只能抓其一。这个测试是 R1 风险的
 * 主防线：改种子必须同步改引擎常量（或反之），否则本测试立刻红。
 *
 * <p>不连 DB、纯文本扫描 VALUES 元组；同一 anchor 支持多 INSERT 块（ultra_short/five_dim 各自一批）。
 */
class ScoringModelSeedParityTest {

    private static final String MODEL_ANCHOR = "INSERT IGNORE INTO t_scoring_model";
    private static final String DIM_ANCHOR = "INSERT IGNORE INTO t_scoring_dim";
    private static final String SUB_ANCHOR = "INSERT IGNORE INTO t_scoring_sub";
    private static final String RULE_ANCHOR = "INSERT IGNORE INTO t_scoring_rule";

    // ==================== 模型行 ====================

    /** t_scoring_model 种子：ultra_short(旧，max_score=NULL) + five_dim(新，max_score=100) 都种上；ultra_short 由紧随的 UPDATE 下线。 */
    @Test
    void seedModelsIncludeBothUltraShortRetiredAndFiveDimActive() throws Exception {
        String sql = readSchema();
        List<List<String>> models = parseAllValueTuples(sql, MODEL_ANCHOR);
        assertEquals(2, models.size(), "t_scoring_model 应有两行种子：ultra_short + five_dim");

        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (List<String> m : models) {
            byKey.put(unq(m.get(0)), m);
        }
        List<String> ultra = byKey.get("ultra_short");
        assertNotNull(ultra, "缺 ultra_short 种子");
        assertEquals("NULL", ultra.get(2).trim(),
                "旧 9 维模型 max_score 保持 NULL(=按权重和推导分母 39)，改动会连带 golden 分母重定标");

        List<String> five = byKey.get("five_dim");
        assertNotNull(five, "缺 five_dim 种子");
        assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(five.get(2).trim())),
                "five_dim 总分满分列 max_score 应为 100.00(0-100 直加权)");
        assertEquals("1", five.get(3).trim(), "five_dim 种子默认 active=1");

        // ultra_short 必须紧随一个 UPDATE 显式下线（幂等种子让 active=1 起、由 UPDATE 收敛为 0）。
        assertTrue(
                sql.contains("UPDATE t_scoring_model SET active = 0 WHERE model_key = 'ultra_short'"),
                "ultra_short 应被 UPDATE 显式置 active=0；否则两张模型都 active=1 时 store 会选错");
    }

    // ==================== 9 维（legacy ultra_short，仅种子保留、不再装配）====================

    /** 兜底路径：{@code ScoreInputs.scoringModel=null} 时 TemperatureCalculator 走 builtinModel()；改种子必须同步。 */
    @Test
    void legacyUltraShortDimSeedMatchesBuiltinModel() throws Exception {
        String sql = readSchema();
        List<List<String>> dimRows = filterModelRef(parseAllValueTuples(sql, DIM_ANCHOR), 0, "@mid");

        ScoringModel builtin = TemperatureCalculator.builtinModel();
        assertEquals(builtin.getDims().size(), dimRows.size(),
                "旧 9 维种子行数应与 builtinModel 一致；漏一维=老 golden 静默缺席");

        Map<String, List<String>> seed = new LinkedHashMap<>();
        for (List<String> row : dimRows) {
            // (model_id, dim_key, dim_no, label, weight, sort_no, record_column, rule_engine, note)
            String key = unq(row.get(1));
            assertTrue(seed.put(key, row) == null, "旧 9 维种子重复：" + key);
        }

        Set<String> builtinKeys = new LinkedHashSet<>();
        for (DimWeight d : builtin.getDims()) {
            builtinKeys.add(d.getDimKey());
            List<String> row = seed.get(d.getDimKey());
            assertNotNull(row, "旧种子缺维度：" + d.getDimKey());

            int seedDimNo = Integer.parseInt(row.get(2).trim());
            assertEquals(d.getDimNo(), seedDimNo, "旧种子 dim_no 不一致：dim_key=" + d.getDimKey());

            assertEquals(d.getLabel(), unq(row.get(3)), "旧种子 label 不一致：dim_key=" + d.getDimKey());

            BigDecimal seedWeight = new BigDecimal(row.get(4).trim());
            assertEquals(0, BigDecimal.valueOf(d.getWeight()).compareTo(seedWeight),
                    "旧种子权重不一致：dim_key=" + d.getDimKey()
                            + " 引擎=" + d.getWeight() + " 种子=" + seedWeight);
        }
        assertEquals(builtinKeys, seed.keySet(), "旧种子维名集合与引擎注册不一致（typo 会在这里暴露）");

        double sum = 0;
        for (DimWeight d : builtin.getDims()) {
            sum += d.getWeight();
        }
        assertEquals(0, new BigDecimal("13.0").compareTo(new BigDecimal(Double.toString(sum)).stripTrailingZeros()),
                "旧 9 维权重和应=13.0（温度分母=39 与 golden 对齐）");
    }

    // ==================== 5 维（新 active=five_dim）====================

    /** 5 个一级维度：dimKey / label / weight / dimNo / recordColumn 与 builtinTree().dims 逐字段一致；维权和=1。 */
    @Test
    void fiveDimSeedDimsMatchBuiltinTreeDims() throws Exception {
        String sql = readSchema();
        List<List<String>> dimRows = filterModelRef(parseAllValueTuples(sql, DIM_ANCHOR), 0, "@fid");
        ScoringTree tree = BoardScoreCalculator.builtinTree();
        assertEquals(tree.getDims().size(), dimRows.size(), "five_dim 种子维数应与 builtinTree 一致");

        Map<String, List<String>> seed = new LinkedHashMap<>();
        for (List<String> row : dimRows) {
            String key = unq(row.get(1));
            assertTrue(seed.put(key, row) == null, "five_dim 种子重复：" + key);
        }

        double weightSum = 0;
        Set<String> builtinKeys = new LinkedHashSet<>();
        for (DimNode d : tree.getDims()) {
            builtinKeys.add(d.getDimKey());
            List<String> row = seed.get(d.getDimKey());
            assertNotNull(row, "five_dim 种子缺维度：" + d.getDimKey());

            assertEquals(d.getDimNo(), Integer.parseInt(row.get(2).trim()),
                    "five_dim dim_no 不一致：" + d.getDimKey());
            assertEquals(d.getLabel(), unq(row.get(3)),
                    "five_dim label 不一致：" + d.getDimKey());
            BigDecimal seedWeight = new BigDecimal(row.get(4).trim());
            assertEquals(0, BigDecimal.valueOf(d.getWeight()).compareTo(seedWeight),
                    "five_dim 维权不一致：" + d.getDimKey());
            assertEquals(d.getRecordColumn(), unq(row.get(6)),
                    "five_dim recordColumn 不一致：" + d.getDimKey());
            assertEquals("WEIGHTED_SUM", unq(row.get(7)),
                    "five_dim 维 rule_engine 应=WEIGHTED_SUM：" + d.getDimKey());
            weightSum += d.getWeight();
        }
        assertEquals(builtinKeys, seed.keySet(), "five_dim 维集合与 builtinTree 不一致");
        assertWeightSumOne(weightSum, "five_dim 维权和");
    }

    /** 每一维下的所有一级 sub（parent='-')与 builtinTree 一致；子权重和=1；层同理。 */
    @Test
    void fiveDimSeedSubsMatchBuiltinTreeSubs() throws Exception {
        String sql = readSchema();
        List<List<String>> subRows = filterModelRef(parseAllValueTuples(sql, SUB_ANCHOR), 0, "@fid");
        // (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note)
        Map<String, List<String>> byDimSub = new LinkedHashMap<>();
        for (List<String> row : subRows) {
            String key = unq(row.get(1)) + "|" + unq(row.get(2));
            assertTrue(byDimSub.put(key, row) == null, "five_dim sub 重复：" + key);
        }

        ScoringTree tree = BoardScoreCalculator.builtinTree();
        int expectedCount = 0;
        for (DimNode d : tree.getDims()) {
            expectedCount += countSubtree(d.getSubs());
            for (SubNode s : d.getSubs()) {
                checkSub(d.getDimKey(), s, byDimSub);
            }
        }
        assertEquals(expectedCount, byDimSub.size(),
                "five_dim sub 种子行数与 builtinTree 展开数不一致（多/少=某层或某叶未同步）");

        // 权重和口径:D1/D2/D4/D5 一级 sub=1.00,LAYER 层=1.00,炸板质量叶=1.00;
        // D3(board)按 spec 25/20/20/15/10=0.90(引擎按已评子权重和归一化,允许 ≠1)。
        for (DimNode d : tree.getDims()) {
            double dimSum = sumChildWeights(d.getSubs());
            if ("board".equals(d.getDimKey())) {
                assertBdConst(dimSum, "0.90", "board 一级 sub 权重和(spec=25/20/20/15/10)");
            } else {
                assertWeightSumOne(dimSum, "dim=" + d.getDimKey() + " 一级 sub 权重和");
            }
            for (SubNode s : d.getSubs()) {
                if (s.getChildren() != null && !s.getChildren().isEmpty()) {
                    assertWeightSumOne(sumChildWeights(s.getChildren()),
                            "dim=" + d.getDimKey() + " sub=" + s.getSubKey() + " 层/叶权重和");
                }
            }
        }
    }

    /** 每个 BAND_LADDER 子（含四层叶子）：种子阶梯按 rule_no 顺序，operator/低/高/分数与 builtinTree 一致。 */
    @Test
    void fiveDimSeedRulesMatchBuiltinTreeLadders() throws Exception {
        String sql = readSchema();
        List<List<String>> ruleRows = filterModelRef(parseAllValueTuples(sql, RULE_ANCHOR), 0, "@fid");
        // (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note)
        Map<String, List<List<String>>> bySub = new HashMap<>();
        for (List<String> row : ruleRows) {
            String key = unq(row.get(1)) + "|" + unq(row.get(2));
            bySub.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        for (List<List<String>> rows : bySub.values()) {
            rows.sort((a, b) -> Integer.compare(Integer.parseInt(a.get(3).trim()),
                    Integer.parseInt(b.get(3).trim())));
        }

        ScoringTree tree = BoardScoreCalculator.builtinTree();
        for (DimNode d : tree.getDims()) {
            for (SubNode s : d.getSubs()) {
                walkLadders(d.getDimKey(), s, bySub);
            }
        }
    }

    // ==================== 递归 walk ====================

    private static void checkSub(String dimKey, SubNode s, Map<String, List<String>> byDimSub) {
        String key = dimKey + "|" + s.getSubKey();
        List<String> row = byDimSub.get(key);
        assertNotNull(row, "five_dim 种子缺 sub：" + key);

        assertEquals(s.getLabel(), unq(row.get(4)), "five_dim sub.label 不一致：" + key);
        BigDecimal seedW = new BigDecimal(row.get(5).trim());
        assertEquals(0, BigDecimal.valueOf(s.getWeight()).compareTo(seedW),
                "five_dim sub.weight 不一致：" + key);
        assertEquals(s.getScoringKind(), unq(row.get(6)),
                "five_dim sub.scoringKind 不一致：" + key);
        // source_key 可空（父节点/复合节点）
        String seedSrc = row.size() > 7 && !"NULL".equalsIgnoreCase(row.get(7).trim()) ? unq(row.get(7)) : null;
        assertEquals(s.getSourceKey(), seedSrc, "five_dim sub.sourceKey 不一致：" + key);

        if (s.getChildren() != null) {
            for (SubNode child : s.getChildren()) {
                checkSub(dimKey, child, byDimSub);
            }
        }
    }

    private static void walkLadders(String dimKey, SubNode s, Map<String, List<List<String>>> bySub) {
        if ("BAND_LADDER".equals(s.getScoringKind())) {
            List<List<String>> rows = bySub.get(dimKey + "|" + s.getSubKey());
            assertNotNull(rows, "缺 BAND_LADDER 阶梯种子：" + dimKey + "|" + s.getSubKey());
            List<BandRule> ladder = s.getLadder();
            assertEquals(ladder.size(), rows.size(),
                    "阶梯长度不一致：" + dimKey + "|" + s.getSubKey());
            for (int i = 0; i < ladder.size(); i++) {
                BandRule b = ladder.get(i);
                List<String> row = rows.get(i);
                assertEquals(b.getOperator(), unq(row.get(4)),
                        "阶梯 operator 不一致：" + dimKey + "|" + s.getSubKey() + " 第 " + (i + 1) + " 档");
                assertBdEquals(b.getThresholdLow(), row.get(5),
                        "阶梯 low 不一致：" + dimKey + "|" + s.getSubKey() + " 第 " + (i + 1) + " 档");
                assertBdEquals(b.getThresholdHigh(), row.get(6),
                        "阶梯 high 不一致：" + dimKey + "|" + s.getSubKey() + " 第 " + (i + 1) + " 档");
                assertBdEquals(b.getScore(), row.get(7),
                        "阶梯 score 不一致：" + dimKey + "|" + s.getSubKey() + " 第 " + (i + 1) + " 档");
            }
        }
        if (s.getChildren() != null) {
            for (SubNode child : s.getChildren()) {
                walkLadders(dimKey, child, bySub);
            }
        }
    }

    private static int countSubtree(List<SubNode> subs) {
        int n = 0;
        for (SubNode s : subs) {
            n += 1 + (s.getChildren() == null ? 0 : countSubtree(s.getChildren()));
        }
        return n;
    }

    private static double sumChildWeights(List<SubNode> subs) {
        double sum = 0;
        for (SubNode s : subs) {
            sum += s.getWeight();
        }
        return sum;
    }

    // ==================== 通用小工具 ====================

    private static void assertWeightSumOne(double sum, String label) {
        assertBdConst(sum, "1", label);
    }

    private static void assertBdConst(double sum, String expected, String label) {
        BigDecimal v = new BigDecimal(Double.toString(sum)).stripTrailingZeros();
        assertEquals(0, new BigDecimal(expected).compareTo(v),
                label + "应=" + expected + "（实际=" + v.toPlainString() + "）");
    }

    private static void assertBdEquals(BigDecimal want, String token, String message) {
        String trimmed = token.trim();
        if (want == null) {
            assertTrue("NULL".equalsIgnoreCase(trimmed), message + "（引擎 null，种子=" + trimmed + "）");
            return;
        }
        assertTrue(!"NULL".equalsIgnoreCase(trimmed), message + "（引擎 " + want.toPlainString() + "，种子 NULL）");
        BigDecimal got = new BigDecimal(trimmed);
        assertEquals(0, want.compareTo(got), message + "（引擎 " + want.toPlainString() + "，种子 " + got.toPlainString() + "）");
    }

    /** 只保留第一个字段的字面量等于 ref 的行（schema 用 @mid/@fid 区分两个模型）。 */
    private static List<List<String>> filterModelRef(List<List<String>> rows, int refIndex, String ref) {
        List<List<String>> out = new ArrayList<>();
        for (List<String> row : rows) {
            if (row.size() > refIndex && ref.equalsIgnoreCase(row.get(refIndex).trim())) {
                out.add(row);
            }
        }
        return out;
    }

    // ==================== schema.sql 文本解析（不连 DB）====================

    private static String readSchema() throws Exception {
        try (InputStream in = ScoringModelSeedParityTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(in, "classpath 里找不到 /schema.sql（main resources 应在测试类路径上）");
            OutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return new String(((ByteArrayOutputStream) out).toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 扫描 anchor 每一次出现的 VALUES 块，合并返回所有元组（支持同表多条 INSERT）。 */
    private static List<List<String>> parseAllValueTuples(String sql, String anchor) {
        List<List<String>> all = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int start = sql.indexOf(anchor, cursor);
            if (start < 0) {
                break;
            }
            int values = sql.indexOf("VALUES", start);
            assertTrue(values >= 0, anchor + " 之后没有 VALUES 关键字");
            all.addAll(scanTuples(sql, values + "VALUES".length()));
            cursor = values + "VALUES".length();
        }
        return all;
    }

    /** 从 valuesStart 起扫到最近的顶层 ';'；括号内是元组、逗号是字段分隔、单引号包围字面量。 */
    private static List<List<String>> scanTuples(String sql, int valuesStart) {
        List<List<String>> tuples = new ArrayList<>();
        boolean inQuote = false;
        List<String> current = null;
        StringBuilder field = new StringBuilder();
        for (int i = valuesStart; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                if (current != null) {
                    field.append(c);
                }
                continue;
            }
            if (!inQuote) {
                if (c == '(') {
                    current = new ArrayList<>();
                    field.setLength(0);
                    continue;
                }
                if (c == ')') {
                    if (current != null) {
                        current.add(field.toString());
                        tuples.add(current);
                        current = null;
                        field.setLength(0);
                    }
                    continue;
                }
                if (c == ',') {
                    if (current != null) {
                        current.add(field.toString());
                        field.setLength(0);
                    }
                    continue;
                }
                if (c == ';') {
                    break;
                }
            }
            if (current != null) {
                field.append(c);
            }
        }
        return tuples;
    }

    /** 去掉字段 token 首尾空白与包裹的单引号。 */
    private static String unq(String token) {
        String t = token.trim();
        if (t.length() >= 2 && t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'') {
            // 内部 '' 转义为 '
            return t.substring(1, t.length() - 1).replace("''", "'");
        }
        return t;
    }
}
