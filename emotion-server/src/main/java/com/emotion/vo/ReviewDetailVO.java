package com.emotion.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复盘页下方那块明细：持仓 / 预判与兑现 / 指数 / 涨跌家数 / 我的仓位 / 各节判断文字 / 当日题材快照。
 *
 * <p>这一份既要能读也要能改：涨跌家数、我的仓位、各节判断文字走 {@code PUT /api/records/{id}}，
 * 持仓与预判走 {@code PUT /api/records/positions|predictions}（整日替换语义）。
 * 只有<b>题材</b>仍然只有 md 导入一个写入口——它绑在原文的 {@code 题材:} 行上，没有自己的表。
 *
 * <p>题材几行是从 {@code review_md} 现读回来的，<b>不读 t_theme</b>：那张表没有日粒度，
 * 按 (user, 题材名) 最后一次导入赢，拿它当"当天的题材"会让上周的强度出现在今天的格子里。
 */
@Data
public class ReviewDetailVO {

    private LocalDate date;
    /** 那天没导过复盘（review_md 为空）时为 false。对照与题材两栏就无从显示。 */
    private boolean hasMd;
    /** 手记对照原文，如「手记 46涨停/17跌停，系统取到 44/16」。只存不解析。 */
    private String compareNote;

    private Integer upCount;
    private Integer downCount;
    private BigDecimal myPositionPct;
    /** 小节键 → 判断正文，原样回填（键序见 {@code ReviewDocFormatter.NOTE_KEYS}）。没填过的键不出现。 */
    private Map<String, String> docNotes = new LinkedHashMap<String, String>();

    private List<PositionItem> positions = new ArrayList<PositionItem>();
    /** 当天写的路径预判。 */
    private List<PredictionItem> plans = new ArrayList<PredictionItem>();
    /** 当天回写的对答案，答的是<b>前一日</b>那些路径。 */
    private List<PredictionItem> answers = new ArrayList<PredictionItem>();
    private List<IndexItem> indexes = new ArrayList<IndexItem>();
    private List<ThemeItem> themes = new ArrayList<ThemeItem>();

    @Data
    public static class PositionItem {
        private String code;
        private String name;
        private BigDecimal costPrice;
        private BigDecimal currentPrice;
        /** 你手记的浮动盈亏%，原样存，不由成本/现价反推。 */
        private BigDecimal floatPct;
        private String action;
        private String plannedAction;
        /** 遵守 / 违约 / 待执行；null = 那行没填，不参与纪律统计。 */
        private String discipline;
    }

    @Data
    public static class PredictionItem {
        private String name;
        /** PLAN 用 prob + condition，ANSWER 用 result + note；另一对为 null。 */
        private Integer prob;
        private String condition;
        private String result;
        private String note;
    }

    @Data
    public static class IndexItem {
        private String code;
        private String name;
        private BigDecimal closePrice;
        private BigDecimal changePct;
    }

    @Data
    public static class ThemeItem {
        private String theme;
        private Integer strength;
        private String status;
        private String leaderCode;
        private String leaderName;
    }
}
