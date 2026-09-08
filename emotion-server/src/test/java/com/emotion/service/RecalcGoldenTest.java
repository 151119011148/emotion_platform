package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.emotion.entity.DailyRecord;
import com.emotion.market.MarketMetrics;
import com.emotion.market.PoolCounts;
import com.emotion.util.CycleStageMachine;
import com.emotion.util.ScoreInputs;
import com.emotion.util.TemperatureCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 16 个真实交易日的全链路重算基线：把库里「人工填的那几列 + 当日公开输入」倒进内存，
 * 跑一遍和 {@code DailyRecordService.scoreAndPlace} 完全同序的流程，逐列比对库里已经存着的结论。
 *
 * <p>为什么值得单独养一份 fixture：温度是九维合成分，改一档阈值、改一条判据、
 * 改一处 coalesce 都会同时挪动这 16 天的读数。人工覆盖那一层一旦写歪，最先出现的症状是
 * "上周还是 62.5，今天打开变 58.3"——这种漂移用假数据的单测抓不住，只有真实输入能钉住。
 *
 * <p>第 8/9 维的输入取自库里那三列（anchor_score / surv_count / surv_premium）：
 * 它们的上游是网络，这里要钉的是"从输入到结论"那一段，不是取数。
 * 档位溢价走 {@link MarketMetrics.TierPremium#ofStored} 重建，与 {@code PremiumTierStore.read}
 * 同一条路，所以基线里的分和卡面上的分是同一个数。
 *
 * <p>重新生成：见同目录 {@code recalc-14d.dump.sql}。改了判据就要重新 dump，
 * 并在提交说明里写清哪几天的哪一列动了、为什么动。
 */
class RecalcGoldenTest {

    private static final String FIXTURE = "golden/recalc-14d.jsonl";
    private static final int LOOKBACK = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void replayingTheStoredDaysReproducesEveryDerivedColumn() throws Exception {
        List<Day> days = load();
        // 基线要够宽才有意义：段号问的是"这是第几个退潮段"，只有几天的 fixture 钉不住它。
        assertTrue(days.size() >= 14, "fixture 只有 " + days.size() + " 天，不足以钉住子段序号");

        List<DailyRecord> records = new ArrayList<>();
        for (Day day : days) {
            records.add(day.inputRecord());
        }

        for (int i = 0; i < days.size(); i++) {
            Day day = days.get(i);
            DailyRecord record = records.get(i);
            // scoreAndPlace 读的是"严格早于这天的最后 5 条，倒序"，量能维和连板维都按这个基准算
            List<DailyRecord> recent = new ArrayList<>();
            for (int j = Math.max(0, i - LOOKBACK); j < i; j++) {
                recent.add(0, records.get(j));
            }

            TemperatureCalculator.calculate(record, recent, day.scoreInputs());
            if (!recent.isEmpty()) {
                record.setPrevTemperature(recent.get(0).getTemperature());
            }

            int dims = record.getScoredDims() == null ? 0 : record.getScoredDims();
            double temp = record.getTemperature() == null ? 0 : record.getTemperature().doubleValue();
            Double prev = record.getPrevTemperature() == null ? null : record.getPrevTemperature().doubleValue();
            if (dims < TemperatureCalculator.MIN_DIMS_FOR_STAGE) {
                record.setStage("");
                record.setStageDirection("");
            } else {
                record.setStage(TemperatureCalculator.determineStage(temp, prev));
                record.setStageDirection(TemperatureCalculator.determineDirection(temp, prev));
            }
        }
        CycleStageMachine.assign(records);

        List<String> diffs = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            days.get(i).diff(records.get(i), diffs);
        }
        if (!diffs.isEmpty()) {
            fail("重算结果与库里存的结论不一致（" + diffs.size() + " 处）：\n" + join(diffs));
        }
    }

    private static String join(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(line);
        }
        return text.toString();
    }

    private static List<Day> load() throws IOException {
        List<Day> days = new ArrayList<>();
        InputStream raw = Thread.currentThread().getContextClassLoader().getResourceAsStream(FIXTURE);
        assertTrue(raw != null, "找不到 fixture " + FIXTURE + "，重算基线就成了空跑");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    days.add(Day.read(MAPPER.readTree(line)));
                }
            }
        }
        days.sort((a, b) -> a.tradeDate.compareTo(b.tradeDate));
        return days;
    }

    /** 一天的全部输入与"库里当初算出来的结论"。 */
    private static final class Day {
        private LocalDate tradeDate;
        private JsonNode inputs;
        private PoolCounts poolCounts;
        private MarketMetrics.PremiumTiers tiers;
        private Integer anchorScore;
        private String anchorNote;
        private Integer survCount;
        private BigDecimal survPremium;
        private String survNote;
        private JsonNode expected;

        static Day read(JsonNode node) {
            Day day = new Day();
            day.tradeDate = LocalDate.parse(node.get("tradeDate").asText());
            day.inputs = node.get("inputs");
            day.poolCounts = poolCounts(node.get("poolCounts"));
            day.tiers = tiers(node.get("tiers"));
            day.anchorScore = integer(node.get("anchorScore"));
            day.anchorNote = text(node.get("anchorNote"));
            day.survCount = integer(node.get("survCount"));
            day.survPremium = decimal(node.get("survPremium"));
            day.survNote = text(node.get("survNote"));
            day.expected = node.get("expected");
            return day;
        }

        /** 只带人工录入的那几列：派生列一律留空，逼打分把它重新算出来。 */
        DailyRecord inputRecord() {
            DailyRecord record = new DailyRecord();
            record.setTradeDate(tradeDate);
            record.setMaxConsecutiveLimit(integer(inputs.get("maxConsecutiveLimit")));
            record.setLimitUpCount(integer(inputs.get("limitUpCount")));
            record.setLimitDownCount(integer(inputs.get("limitDownCount")));
            record.setYesterdayLimitPremium(decimal(inputs.get("yesterdayLimitPremium")));
            record.setBrokenBoardRate(decimal(inputs.get("brokenBoardRate")));
            record.setBigLossCount(integer(inputs.get("bigLossCount")));
            record.setTotalVolume(decimal(inputs.get("totalVolume")));
            record.setScoreTheme(integer(inputs.get("scoreTheme")));
            return record;
        }

        ScoreInputs scoreInputs() {
            ScoreInputs in = ScoreInputs.empty();
            in.setPremiumTiers(tiers);
            in.setPoolCounts(poolCounts);
            in.setAnchorScore(anchorScore);
            in.setAnchorNote(anchorNote);
            in.setSurvCount(survCount);
            in.setSurvPremium(survPremium);
            in.setSurvNote(survNote);
            return in;
        }

        void diff(DailyRecord actual, List<String> diffs) {
            for (String column : SCORE_COLUMNS) {
                diffInteger(diffs, column, integer(expected.get(column)), getter(actual, column));
            }
            for (String column : DECIMAL_COLUMNS) {
                diffDecimal(diffs, column, decimal(expected.get(column)), decimalGetter(actual, column));
            }
            for (String column : TEXT_COLUMNS) {
                diffText(diffs, column, text(expected.get(column)), textGetter(actual, column));
            }
            diffInteger(diffs, "stageSeq", integer(expected.get("stageSeq")), actual.getStageSeq());
        }

        private void diffInteger(List<String> diffs, String column, Integer want, Integer got) {
            if (want == null ? got != null : !want.equals(got)) {
                diffs.add(tradeDate + " " + column + " 期望 " + want + " 实得 " + got);
            }
        }

        /** 按数值比：库里 DECIMAL(5,2) 会把 77.6 存成 77.60，scale 不是这里要守的东西。 */
        private void diffDecimal(List<String> diffs, String column, BigDecimal want, BigDecimal got) {
            boolean same = want == null ? got == null : (got != null && want.compareTo(got) == 0);
            if (!same) {
                diffs.add(tradeDate + " " + column + " 期望 " + want + " 实得 " + got);
            }
        }

        /**
         * 依据串逐字一比（它是界面上唯一解释这个分从哪来的话），只宽容末尾的 0：{@code broken_board_rate} 是 DECIMAL(5,2)，
         * 而 09-07/09-08 那两天入库时手上拿的是算出来的一位小数（31.6），note 里就印着 "31.6%"，
         * 重算读回列得到 31.60，分数一点没动、串却差一个 0。那是尺子的刻度不是结论。
         */
        private void diffText(List<String> diffs, String column, String want, String got) {
            boolean same = want == null || want.isEmpty() ? got == null || got.isEmpty()
                    : canonical(want).equals(canonical(got));
            if (!same) {
                diffs.add(tradeDate + " " + column + "\n  期望 " + want + "\n  实得 " + got);
            }
        }

        private static final Pattern DECIMAL = Pattern.compile("-?\\d+\\.\\d+");

        private static String canonical(String text) {
            Matcher m = DECIMAL.matcher(text);
            StringBuffer out = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(out, Matcher.quoteReplacement(
                        new BigDecimal(m.group()).stripTrailingZeros().toPlainString()));
            }
            m.appendTail(out);
            return out.toString();
        }

        private static final String[] SCORE_COLUMNS = {
                "scoreHeight", "scorePremium", "scoreBreadth", "scoreBroken", "scoreLoss",
                "scoreVolume", "scoreTheme", "anchorScore", "survCount", "totalScore", "scoredDims"};
        private static final String[] DECIMAL_COLUMNS = {
                "premiumWeighted", "sealedHomeRate", "resealRate", "temperature", "prevTemperature", "survPremium"};
        private static final String[] TEXT_COLUMNS = {
                "anchorNote", "survNote", "brokenNote", "stage", "stageDirection", "stagePhase"};
    }

    private static Integer getter(DailyRecord r, String column) {
        switch (column) {
            case "scoreHeight": return r.getScoreHeight();
            case "scorePremium": return r.getScorePremium();
            case "scoreBreadth": return r.getScoreBreadth();
            case "scoreBroken": return r.getScoreBroken();
            case "scoreLoss": return r.getScoreLoss();
            case "scoreVolume": return r.getScoreVolume();
            case "scoreTheme": return r.getScoreTheme();
            case "anchorScore": return r.getAnchorScore();
            case "survCount": return r.getSurvCount();
            case "totalScore": return r.getTotalScore();
            case "scoredDims": return r.getScoredDims();
            default: throw new IllegalArgumentException(column);
        }
    }

    private static BigDecimal decimalGetter(DailyRecord r, String column) {
        switch (column) {
            case "premiumWeighted": return r.getPremiumWeighted();
            case "sealedHomeRate": return r.getSealedHomeRate();
            case "resealRate": return r.getResealRate();
            case "temperature": return r.getTemperature();
            case "prevTemperature": return r.getPrevTemperature();
            case "survPremium": return r.getSurvPremium();
            default: throw new IllegalArgumentException(column);
        }
    }

    private static String textGetter(DailyRecord r, String column) {
        switch (column) {
            case "anchorNote": return r.getAnchorNote();
            case "survNote": return r.getSurvNote();
            case "brokenNote": return r.getBrokenNote();
            case "stage": return r.getStage();
            case "stageDirection": return r.getStageDirection();
            case "stagePhase": return r.getStagePhase();
            default: throw new IllegalArgumentException(column);
        }
    }

    private static PoolCounts poolCounts(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Integer zt = integer(node.get("ztCount"));
        Integer zb = integer(node.get("zbCount"));
        Integer reseal = integer(node.get("resealCount"));
        if (zt == null || zb == null) {
            return null;
        }
        PoolCounts counts = new PoolCounts();
        counts.setZtCount(zt);
        counts.setZbCount(zb);
        counts.setResealCount(reseal == null ? 0 : reseal);
        return counts;
    }

    private static MarketMetrics.PremiumTiers tiers(JsonNode node) {
        if (node == null || node.isNull() || node.size() == 0) {
            return null;
        }
        List<MarketMetrics.TierPremium> stored = new ArrayList<>();
        for (JsonNode tier : node) {
            stored.add(MarketMetrics.TierPremium.ofStored(tier.get("board").asInt(),
                    integer(tier.get("stockCount")) == null ? 0 : integer(tier.get("stockCount")),
                    integer(tier.get("matched")) == null ? 0 : integer(tier.get("matched")),
                    decimal(tier.get("avgPct")), decimal(tier.get("maxPct")), decimal(tier.get("minPct"))));
        }
        return MarketMetrics.tiersFromStored(stored);
    }

    private static Integer integer(JsonNode node) {
        return node == null || node.isNull() ? null : node.asInt();
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.isTextual() ? node.asText() : node.toString();
        return value == null || value.isEmpty() ? null : new BigDecimal(value);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
