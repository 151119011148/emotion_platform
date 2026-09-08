package com.emotion.util;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.emotion.entity.DailyRecord;

/**
 * 子段序号：把"退潮 → 反弹 → 再退潮"这种往复表达出来。
 *
 * <p>七个主阶段是平的，说不出"这是第几轮退潮"。这里给每一天补两个派生值：
 * {@code stage_seq}（这一段是第几个回合）和 {@code stage_phase}（'反弹' 或空）。
 * 主阶段本身一个都不动——{@code CycleService.ADVICE_MAP} 继续按主阶段给建议，
 * 因为"反弹"在 02 篇里的语义是减仓机会，不是一个新阶段。
 *
 * <p>段号按<b>回合</b>递增，不按天：连着三天退潮都是 退潮一阶段，退潮→反弹→退潮 才进二阶段。
 * 反弹单独占自己的计数（反弹一阶段、反弹二阶段），它不消耗主阶段的号——
 * 所以 退潮一阶段 之后 +8° 而温度仍在水位下的那天是 反弹一阶段，再回落才是 退潮二阶段。
 *
 * <p>纯静态：只读 {@code stage} 和 {@code temperature} 两个字段，只写
 * {@code stageSeq}/{@code stagePhase}，不碰库也不碰网络。缺维的日子 stage 是空串，
 * 段号留 null，既不计数也不打断回合。
 */
public final class CycleStageMachine {

    /**
     * 反弹的温差：+8°。和判转弱的 12° 一样，都是"跳两档以上才算动"——
     * 九维 27 分制下 8° = 2.16 个得分点。这是本次第二个可调旋钮。
     */
    public static final double REBOUND_THRESHOLD = 8;

    /** 下行阶段的集合：只有退潮/分歧途中的回升才叫反弹。 */
    private static final List<String> DOWN_STAGES = Arrays.asList("退潮", "分歧");

    private static final String REBOUND = "反弹";

    private static final String[] CN_NUM = {"〇", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

    private CycleStageMachine() {
    }

    /** 就地补上段号与子段标签。传进来必须是<b>按日期升序</b>的连续记录。 */
    public static void assign(List<DailyRecord> asc) {
        if (asc == null || asc.isEmpty()) {
            return;
        }
        Map<String, Integer> runs = new HashMap<>();
        String lastKind = null;
        String lastStage = null;
        BigDecimal lastTemp = null;
        for (DailyRecord record : asc) {
            String stage = record.getStage();
            if (stage == null || stage.trim().isEmpty()) {
                // 缺维的日子没有阶段可言：不给它段号，也不让它把正在进行的回合切断
                record.setStageSeq(null);
                record.setStagePhase(null);
                continue;
            }
            BigDecimal temp = record.getTemperature();
            String kind = isRebound(lastStage, temp, lastTemp) ? REBOUND : stage;
            Integer current = runs.get(kind);
            if (!kind.equals(lastKind) || current == null) {
                current = (current == null ? 0 : current) + 1;
                runs.put(kind, current);
            }
            record.setStageSeq(current);
            record.setStagePhase(REBOUND.equals(kind) ? REBOUND : "");
            lastKind = kind;
            lastStage = stage;
            if (temp != null) {
                lastTemp = temp;
            }
        }
    }

    /**
     * 反弹：<b>前一日</b>处在退潮/分歧、今天涨回 8° 以上、且温度还没爬回发酵线
     * （{@link TemperatureCalculator#FERMENT_LINE}）——三条缺一条就不是反弹。
     *
     * <p>为什么不能问"当天阶段是不是下行"：{@code determineStage} 里的 退潮/分歧 唯一来源就是
     * 当日跌幅 ≥12°，同一天不可能既 ≤-12° 又 ≥+8°，那样写的判据永远不成立（14 天实测 0 次）。
     * 上限那条同样必要：09-01 从分歧日 +29.2° 直接进高潮，那是反转不是反弹。
     */
    private static boolean isRebound(String lastStage, BigDecimal temp, BigDecimal lastTemp) {
        if (temp == null || lastTemp == null || lastStage == null || !DOWN_STAGES.contains(lastStage)) {
            return false;
        }
        double delta = temp.doubleValue() - lastTemp.doubleValue();
        return delta >= REBOUND_THRESHOLD && temp.doubleValue() < TemperatureCalculator.FERMENT_LINE;
    }

    /**
     * 界面上那一格：{@code 退潮 · 一阶段}，反弹日显示 {@code 反弹 · 一阶段}。
     * 缺维或没算过就返回空串，让前端直接显示主阶段。
     */
    public static String label(String stage, String phase, Integer seq) {
        if (stage == null || stage.trim().isEmpty() || seq == null) {
            return "";
        }
        String head = phase == null || phase.trim().isEmpty() ? stage : phase;
        return head + " · " + chinese(seq) + "阶段";
    }

    /** 十一往回都写成 十一、十二…；再往上退化到阿拉伯数字，回合数真到两位数时不该硬凑。 */
    static String chinese(int n) {
        if (n <= 0) {
            return String.valueOf(n);
        }
        if (n < CN_NUM.length) {
            return CN_NUM[n];
        }
        if (n <= 19) {
            return "十" + CN_NUM[n - 10];
        }
        return String.valueOf(n);
    }
}
