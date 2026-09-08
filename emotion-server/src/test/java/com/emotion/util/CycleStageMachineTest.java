package com.emotion.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.emotion.entity.DailyRecord;

/**
 * 子段序号。要钉住的是"回合"这个语义：连着三天退潮是同一个一阶段，
 * 中间插一次反弹再落回退潮才叫二阶段——按天递增就完全答错了问题。
 *
 * <p>这里的输入一律按 {@code determineStage} 真能产出的形状造（退潮/分歧 那天必然是跌的，
 * 反弹日的主阶段必然是 启动/冰点），否则就是在测一个到不了的分支——上一版正是这样写绿的。
 */
class CycleStageMachineTest {

    private static DailyRecord row(String stage, Double temp) {
        DailyRecord r = new DailyRecord();
        r.setTradeDate(LocalDate.of(2026, 9, 1));
        r.setStage(stage);
        r.setTemperature(temp == null ? null : BigDecimal.valueOf(temp));
        return r;
    }

    /** 退潮一阶段 → 反弹一阶段 → 退潮二阶段：反弹不消耗主阶段的号，落回去才进下一回合。 */
    @Test
    void reboundThenFallAdvancesTheTideByOneRound() {
        List<DailyRecord> asc = new ArrayList<>(Arrays.asList(
                row("发酵", 60.0),
                row("退潮", 40.0),
                row("启动", 48.0),
                row("退潮", 35.0)));

        CycleStageMachine.assign(asc);

        assertEquals(Arrays.asList(1, 1, 1, 2), seqs(asc));
        assertEquals(Arrays.asList("", "", "反弹", ""), phases(asc));
        assertEquals("退潮 · 一阶段", label(asc.get(1)));
        // 反弹日的主阶段仍是 启动：ADVICE_MAP 照旧按启动给建议，子段只说"这是退潮途中的回升"
        assertEquals("启动", asc.get(2).getStage());
        assertEquals("反弹 · 一阶段", label(asc.get(2)));
        assertEquals("退潮 · 二阶段", label(asc.get(3)));
    }

    /** 判据问的是<b>前一日</b>是不是下行阶段。当天不是不行：退潮/分歧 唯一的来源就是当日跌 ≥12°。 */
    @Test
    void onlyTheDayAfterADownStageCanRebound() {
        List<DailyRecord> afterTide = new ArrayList<>(Arrays.asList(
                row("分歧", 30.0), row("启动", 40.0)));
        CycleStageMachine.assign(afterTide);
        assertEquals(Arrays.asList("", "反弹"), phases(afterTide));

        // 同样 +10°，前一天是发酵就不叫反弹
        List<DailyRecord> afterFerment = new ArrayList<>(Arrays.asList(
                row("发酵", 60.0), row("发酵", 70.0)));
        CycleStageMachine.assign(afterFerment);
        assertEquals(Arrays.asList("", ""), phases(afterFerment));
        assertEquals(Arrays.asList(1, 1), seqs(afterFerment));
    }

    /** 涨回发酵线（55°）以上那是反转不是反弹：09-01 从分歧日 +29.2° 直接进高潮就是这一类。 */
    @Test
    void aJumpBackOverTheFermentLineIsNotARebound() {
        List<DailyRecord> asc = new ArrayList<>(Arrays.asList(
                row("分歧", 30.0), row("高潮", 60.0)));

        CycleStageMachine.assign(asc);

        assertEquals(Arrays.asList("", ""), phases(asc));
        assertEquals(Arrays.asList(1, 1), seqs(asc));
        // 正好压在 55° 线上算回到发酵，不叫反弹
        List<DailyRecord> onLine = new ArrayList<>(Arrays.asList(
                row("退潮", 30.0), row("发酵", 55.0)));
        CycleStageMachine.assign(onLine);
        assertEquals(Arrays.asList("", ""), phases(onLine));
    }

    /** 反弹只标第一天：反弹日的主阶段是 启动，所以下一个涨日不再满足"前一日下行"。 */
    @Test
    void reboundLabelsTheFirstUpDayOnly() {
        List<DailyRecord> asc = new ArrayList<>(Arrays.asList(
                row("退潮", 25.0), row("启动", 34.0), row("启动", 44.0), row("退潮", 30.0)));

        CycleStageMachine.assign(asc);

        assertEquals(Arrays.asList("反弹", ""), phases(asc).subList(1, 3));
        assertEquals(Arrays.asList(1, 1, 1, 2), seqs(asc));
    }

    /** 缺维的日子 stage 是空串：不给段号，也不许把正在进行的回合切断。 */
    @Test
    void blankStageDaysNeitherCountNorBreakTheRound() {
        List<DailyRecord> asc = new ArrayList<>(Arrays.asList(
                row("退潮", 40.0), row("", null), row("启动", 50.0), row("", null), row("退潮", 30.0)));

        CycleStageMachine.assign(asc);

        assertEquals(Arrays.asList(1, null, 1, null, 2), seqs(asc));
        assertEquals(Arrays.asList("", null, "反弹", null, ""), phases(asc));
    }

    /** 整段都没阶段（例如新账号只有残值日子）：一律 null，界面上就只剩主阶段。 */
    @Test
    void allBlankStagesProduceNoSequenceAtAll() {
        List<DailyRecord> asc = new ArrayList<>(Arrays.asList(row("", null), row("", null)));

        CycleStageMachine.assign(asc);

        assertEquals(Arrays.asList(null, null), seqs(asc));
        assertEquals("", label(asc.get(0)));
    }

    /** 第一天没有"前一日"可比较，就算温度再高也不能叫反弹。 */
    @Test
    void firstRowNeverRebounds() {
        List<DailyRecord> asc = new ArrayList<>(Arrays.asList(row("启动", 45.0), row("启动", 46.0)));

        CycleStageMachine.assign(asc);

        assertEquals(Arrays.asList("", ""), phases(asc));
        assertEquals(Arrays.asList(1, 1), seqs(asc));
    }

    @Test
    void emptyAndNullInputAreNoOps() {
        CycleStageMachine.assign(null);
        CycleStageMachine.assign(new ArrayList<DailyRecord>());
    }

    @Test
    void labelsUseChineseNumeralsAndFallBackBeyondNineteen() {
        assertEquals("一", CycleStageMachine.chinese(1));
        assertEquals("十", CycleStageMachine.chinese(10));
        assertEquals("十一", CycleStageMachine.chinese(11));
        assertEquals("十九", CycleStageMachine.chinese(19));
        assertEquals("20", CycleStageMachine.chinese(20));
        assertEquals("", CycleStageMachine.label("退潮", "", null));
        assertEquals("", CycleStageMachine.label(null, "反弹", 1));
    }

    private static List<Integer> seqs(List<DailyRecord> rows) {
        List<Integer> out = new ArrayList<>(rows.size());
        for (DailyRecord r : rows) {
            out.add(r.getStageSeq());
        }
        return out;
    }

    private static List<String> phases(List<DailyRecord> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (DailyRecord r : rows) {
            out.add(r.getStagePhase());
        }
        return out;
    }

    private static String label(DailyRecord r) {
        return CycleStageMachine.label(r.getStage(), r.getStagePhase(), r.getStageSeq());
    }
}
