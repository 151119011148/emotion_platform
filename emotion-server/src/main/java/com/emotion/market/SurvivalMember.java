package com.emotion.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 某一日处于监管期内的一只票。
 *
 * 一只一条，但它可能同时被多起事件罩着（连板途中异常波动 → 监管工作函 → 再次异常波动是常态，
 * 实测龙版传媒 09-02/09-03/09-04 三天连着三起）。第 9 维要的是"这只票今天涨了多少"，
 * 所以样本按只算而不是按事件算，事件列表面板展示"在列第 k/N 日"。
 */
@Data
public class SurvivalMember {

    private String code;
    private String name;
    /** 起数：D0 之后的第几个交易日，1 起。 */
    private int dayIndex;
    private int days;
    /** 当日涨跌幅 %；null = 这只票今天没取到价，不计入样本。 */
    private BigDecimal pct;
    /** 全部生效中的事件，按剩余天数从多到少排。 */
    private List<ActiveEvent> events = new ArrayList<>();

    /** 一起还在生效的事件。 */
    @Data
    public static class ActiveEvent {
        private SurveillanceKind kind;
        private LocalDate annDate;
        private int dayIndex;
        private int days;

        public static ActiveEvent of(SurveillanceKind kind, LocalDate annDate, int dayIndex, int days) {
            ActiveEvent event = new ActiveEvent();
            event.setKind(kind);
            event.setAnnDate(annDate);
            event.setDayIndex(dayIndex);
            event.setDays(days);
            return event;
        }

        /** 还剩几个交易日出窗：0 表示今天就是最后一天。 */
        public int remaining() {
            return days - dayIndex;
        }
    }

    /** 卡片上的一行依据：「严重08-21 第10/10日」。多个事件用 + 连。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (ActiveEvent event : events) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(event.getKind().label()).append(' ')
                    .append(event.getAnnDate().toString().substring(5).replace('-', '/'))
                    .append(" 第").append(event.getDayIndex()).append('/').append(event.getDays()).append("日");
        }
        return sb.toString();
    }

    /** 样本数口径：取到价才算。 */
    public boolean hasPct() {
        return pct != null;
    }

    /**
     * 今天进不进第 9 维：任一在列事件是 SEVERE/EXCH 就进。
     * 只有例行 ZD 的票照样在名单上，但只是展示——见 {@link SurveillanceKind#scores()}。
     */
    public boolean scored() {
        for (ActiveEvent event : events) {
            if (event.getKind() != null && event.getKind().scores()) {
                return true;
            }
        }
        return false;
    }
}
