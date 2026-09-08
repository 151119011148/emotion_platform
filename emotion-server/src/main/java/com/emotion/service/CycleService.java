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
}
