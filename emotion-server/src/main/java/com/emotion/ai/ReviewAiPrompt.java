package com.emotion.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复盘 AI 草稿的提示词。<b>纯函数，全文一个阿拉伯数字都没有</b>——这是整件事的兑现点：
 * 模型手上根本没有数，所以它写不出数，只能写{@code {温度}}这种令牌，值由
 * {@link TokenFiller} 在事后用系统算好的那份填进去。
 *
 * <p>为什么不把值一起写进 prompt 再叮嘱"不许改数"：那种 prompt 里 {@code 52.8°} 和模型自己编的
 * {@code 52.8°} 事后看不出区别，而他那份文档里的数一旦混进一个来路不明的，就再没人知道哪个是读数。
 * 让它看不见值，比让它答应不编值可靠。
 *
 * <p>只列当天<b>真取到</b>的令牌：没取到的东西连令牌都不出现，模型于是也没法写出
 * 「温度 {@code {温度}} 偏低」这种把缺数当有数用的句子。
 *
 * <p>产出只是<b>草稿</b>：他自己的 {@code ✍️} 那栏不覆盖、不进 md、不进留痕，
 * 这一段永远走独立接口，不掺进 {@code GET /api/review/export} 那份按同一条件必须逐字节相同的文档。
 */
public final class ReviewAiPrompt {

    /** 令牌名 → 给模型看的语义说明。{@code ReviewDocFormatter.aiFacts} 的键必须是它的子集。 */
    public static final Map<String, String> GLOSSARY = glossary();

    private static Map<String, String> glossary() {
        Map<String, String> g = new LinkedHashMap<String, String>();
        g.put("日期", "这一天的交易日");
        g.put("次日", "下一交易日");
        g.put("温度", "当日情绪温度（系统算的分，不是市场成交额）");
        g.put("昨温度", "上一条记录的情绪温度");
        g.put("温度差", "温度相对上一条记录的增减");
        g.put("阶段", "当日周期阶段名");
        g.put("昨阶段", "上一条记录的周期阶段名");
        g.put("总分", "当日九维总分");
        g.put("进分维", "今天有几维进了分（分母是九维，缺维不进分母）");
        g.put("成交额", "全市场成交额");
        g.put("成交额差", "成交额较上一条记录的增减，正值是放量");
        g.put("涨家", "上涨家数");
        g.put("跌家", "下跌家数");
        g.put("红盘率", "涨 /(涨 + 跌)，分母不含平盘");
        g.put("昨红盘率", "上一条记录的红盘率");
        g.put("涨停", "涨停家数");
        g.put("跌停", "跌停家数");
        g.put("昨涨停", "上一条记录的涨停家数");
        g.put("昨跌停", "上一条记录的跌停家数");
        g.put("最高板", "全场最高连板高度");
        g.put("连板家数", "二板及以上的连板家数");
        g.put("一字", "一字板家数");
        g.put("炸板", "炸板池家数");
        g.put("封板率", "涨停池 /(涨停池 + 炸板池)");
        g.put("大面", "当日大面家数（大幅亏钱效应的票）");
        g.put("上证", "上证指数收盘与涨跌");
        g.put("深证", "深证成指收盘与涨跌");
        g.put("创业板", "创业板指收盘与涨跌");
        g.put("科创", "科创板指数收盘与涨跌");
        g.put("北证", "北交所指数收盘与涨跌");
        g.put("主线", "当日主线题材名（他手填的那格）");
        g.put("总龙头", "总龙头及其状态");
        g.put("中军", "中军票");
        g.put("持仓", "当日台账里的标的");
        g.put("持仓只数", "当日台账里的标的只数");
        return Collections.unmodifiableMap(g);
    }

    private static final String SYSTEM =
            "你在替一份 A 股每日复盘文档写一段草稿，位置是【指数与量能】这一节末尾的"
                    + "「核心定性」：这一天到底是转强、弱修复还是退潮，指数和个股背不背离，"
                    + "量能与涨跌家数支不支持这个定性。\n"
                    + "规则，逐条都是硬约束：\n"
                    + "一、你不许写出任何数字，也不许改写、估算、补全任何一个数值。所有数值都不在你手上，"
                    + "系统会在你交回来之后把它自己算好的值填进你留下的令牌。\n"
                    + "二、要提到某个数，就原样写出下面「可用令牌」里给的花括号令牌，一个字都不要改；"
                    + "令牌不在列表里，说明系统这天没取到它，你要么不提这件事，要么照实说这一项没有读数，"
                    + "严禁用「暂无」「待定」之外的方式蒙一个看起来像数的东西。\n"
                    + "三、不许引入任何列表以外的信息：不查行情、不猜题材、不讲故事、不提消息面与外围。\n"
                    + "四、你的活儿是把已有读数组织成一句人能用的判断——谁和谁背离、量支持不支持广度、"
                    + "这个温度和这个阶段的说法是否一致——不是替他做交易决定。\n"
                    + "五、产出两到四句中文白话，不要标题、不要列表符号、不要解释你做了什么，"
                    + "直接把那几句话当全部输出交回来。";

    private ReviewAiPrompt() {
    }

    /** 一次调用要发出去的两段文字。 */
    public static final class Prompt {
        public final String system;
        public final String user;

        Prompt(String system, String user) {
            this.system = system;
            this.user = user;
        }
    }

    /**
     * 组装提示词。{@code facts} 是系统当天的令牌表（{@code ReviewDocFormatter.aiFacts}），
     * 这里<b>只用它的键，值一个都不发出去</b>。
     */
    public static Prompt build(Map<String, String> facts) {
        Map<String, String> f = facts == null ? Collections.<String, String>emptyMap() : facts;
        StringBuilder sb = new StringBuilder("可用令牌（左边是你要原样写回的令牌，右边是它的含义）：\n");
        List<String> extra = new ArrayList<String>();
        for (Map.Entry<String, String> e : GLOSSARY.entrySet()) {
            if (f.containsKey(e.getKey())) {
                sb.append("- {").append(e.getKey()).append("} — ").append(e.getValue()).append("\n");
            }
        }
        for (String key : f.keySet()) {
            if (!GLOSSARY.containsKey(key)) {
                extra.add(key);
            }
        }
        if (!extra.isEmpty()) {
            // 令牌表比词表跑得快（改了 aiFacts 忘了这里）：宁可把语义丢了发出去，也别让草稿整条失败。
            sb.append("（以下令牌系统给了但这里没有说明，按名字理解使用）\n");
            for (String key : extra) {
                sb.append("- {").append(key).append("}\n");
            }
        }
        sb.append("\n请就这一天写「核心定性」那一段。");
        return new Prompt(SYSTEM, sb.toString());
    }

    /** 门卫拦下之后的追加指令：只报上下文片段，绝不把原数念回给它。 */
    public static Prompt retry(Prompt first, String correction) {
        return new Prompt(first.system, first.user + "\n\n上一次你交回来的文字里有违规，纠正要求：\n"
                + correction + "\n请重新交一份，规则不变。");
    }
}
