package com.emotion.service;

import com.emotion.vo.StageAdviceVO;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class CycleService {

    private static final Map<String, StageAdviceVO> ADVICE_MAP = new HashMap<>();

    static {
        ADVICE_MAP.put("冰点", new StageAdviceVO(
                "冰点", "空仓观望", "0-1成",
                "极轻仓试反抽龙头或逆势新题材首板，严禁抄高位股底部",
                "冰点不是利空出尽，是多头被消灭。结束靠的是新龙头走出来，不是好消息。"));
        ADVICE_MAP.put("修复", new StageAdviceVO(
                "修复", "小仓试错", "1-3成",
                "新题材首板试错，敢做新龙头首板。重点在新，老龙头多是反抽出货",
                "修复可能被证伪退回冰点，别急上重仓。"));
        ADVICE_MAP.put("启动", new StageAdviceVO(
                "启动", "加仓进攻", "4-6成",
                "上龙头（打板/接力龙头加速）、低吸主线跟风。从试错切换到进攻",
                "确认标志：空间板打开+跟风响应。这是确定性最高、赔率最好的窗口。"));
        ADVICE_MAP.put("发酵", new StageAdviceVO(
                "发酵", "持股为主", "7成-满仓",
                "龙头加速段+补涨/分支轮动。仓位可放大，让利润奔跑",
                "越往后越要想退路。发酵中后段增量资金边际递减，为高潮兑现做准备。"));
        ADVICE_MAP.put("高潮", new StageAdviceVO(
                "高潮", "逐步兑现", "逐步降到5成以下",
                "分批止盈，落袋为安，坚决不追高不加仓",
                "多赚最后一个铜板是万恶之源。一致即拐点。"));
        ADVICE_MAP.put("分歧", new StageAdviceVO(
                "分歧", "去弱留强", "维持不加",
                "卖跟风留核心，盯龙头反包。反包成功=再加一波，反包失败=全面防守",
                "分歧本身不是坏事，关键是分歧后能否转一致。"));
        ADVICE_MAP.put("退潮", new StageAdviceVO(
                "退潮", "降仓清仓", "0-2成",
                "只做低位首板/超跌反抽，绝对不做高位接力",
                "退潮里冒头的小周期多是陷阱，别把反弹当反转。"));
    }

    public StageAdviceVO getAdvice(String stage) {
        return ADVICE_MAP.getOrDefault(stage, ADVICE_MAP.get("修复"));
    }

    /**
     * 页面展示带名 → 上面那张表的阶段名。
     *
     * <p>两套词表本来不是一回事：库里那套是引擎按绝对阈值落的 7 档
     * （冰点/修复/启动/发酵/高潮/分歧/退潮 ＋ "退潮(强制)"），
     * 前端读时重切只有 5 条带（冰点/退潮/混沌/发酵/沸点，按可比样本分位线切）。
     * 仪表盘上的建议要跟的是**页面上那条带**，所以在这里做一次对位。
     *
     * <p>{@code 混沌 → 修复} 这条是权宜：P25–P75 那一整段在库里对应修复/启动/分歧三档，
     * 没有哪一份文案能代表它，先吃「修复」（小仓试错 1-3成）。换文案是策略决定，不在这张表里自己改。
     */
    private static final Map<String, String> BAND_TO_STAGE = new HashMap<>();

    static {
        BAND_TO_STAGE.put("冰点", "冰点");
        BAND_TO_STAGE.put("退潮", "退潮");
        BAND_TO_STAGE.put("退潮(强制)", "退潮");
        BAND_TO_STAGE.put("混沌", "修复");
        BAND_TO_STAGE.put("发酵", "发酵");
        BAND_TO_STAGE.put("沸点", "高潮");
    }

    /**
     * 按带名出建议。标题印的是**带名**，不是库里的阶段名——
     * 否则曲线上标着沸点，这块写「操作建议 · 高潮」，看起来又像两处口径打架。
     *
     * <p>缺维的日子前端不重切带，直接把落库标签印出来（{@code utils/stages.js#bandOfRecord}），
     * 那些标签就是上面 7 档的本名，所以查不到对位时先按本名直接查一次。
     *
     * @return 认不出来的带名返回 null，让调用方走「不给建议」那一支；
     *         绝不退化成 getAdvice 那种「查不到就当修复」，那等于把未知串读成一次建仓指令。
     */
    public StageAdviceVO adviceForBand(String band) {
        if (band == null) {
            return null;
        }
        String name = band.trim();
        String stage = BAND_TO_STAGE.get(name);
        StageAdviceVO mapped = stage == null ? ADVICE_MAP.get(name) : ADVICE_MAP.get(stage);
        if (mapped == null) {
            return null;
        }
        return new StageAdviceVO(name, mapped.getTone(), mapped.getPosition(),
                mapped.getAction(), mapped.getWarning());
    }
}
