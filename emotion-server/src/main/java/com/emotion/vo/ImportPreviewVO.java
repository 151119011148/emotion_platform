package com.emotion.vo;

import com.emotion.util.ReviewDoc;
import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 导入预览。整个接口永远返回 200，坏行放在 {@link #errors} 里——
 * 因为 {@code GlobalExceptionHandler} 会把异常压成一条红色 toast，多行报错会被截断成一坨。
 */
@Data
public class ImportPreviewVO {

    private LocalDate date;
    /** meta 块之外还有正文；整篇（含 meta 块）会原样进 review_md。 */
    private boolean proseIncluded;
    /** 有坏行就是 false，前端的确认按钮跟着禁用。 */
    private boolean okToConfirm;
    private List<ReviewDoc.ParseError> errors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    /** 标量字段：现值 → 将写入值。只列真的有变化的那些。 */
    private List<Change> changes = new ArrayList<>();
    /** 按日删除重建的那三张表：会覆盖掉几行、换成几行。 */
    private List<RowCount> rowCounts = new ArrayList<>();
    /** 题材走的是 upsert（无日粒度），单独列，别和"覆盖 N 行"混在一起看。 */
    private List<ThemeChange> themeChanges = new ArrayList<>();
    /** 系统七数原样摆在这里，手记的对照值挂在 compareNote 上，两边都不改。 */
    private List<MarketRow> marketCompare = new ArrayList<>();
    private String compareNote;
    /** 不传 confirm 时会不会改变温度/阶段。这一步是给你否决用的，不是通知。 */
    private ScoreImpact scoreImpact;

    @Data
    public static class Change {
        /** md 里那个键名，前端直接当标题用。 */
        private String key;
        private String label;
        private String oldValue;
        private String newValue;
        /** true = 这一列进打分（目前只有 主线明确度）。前端会给它加一个"会改分"的标记。 */
        private boolean scored;
        /** true = 这次要把它清空。空值和"键没写"是两回事，混起来看就会以为数据丢了。 */
        private boolean clearing;
        /** 这一行该怎么读，例如"只入库，不进分母"。口径上的分歧写在这里而不是让前端猜。 */
        private String note;
        /**
         * 现值与将写入值是否真的不同。没变的键<b>照样列出来</b>——不然"这行值本来就对"
         * 和"这个键根本没被认出来"在表里长得一样，而后者才是这次导入最该怕的事。
         */
        private boolean changed;
    }

    @Data
    public static class RowCount {
        private String table;
        private int existing;
        private int incoming;
        private String note;

        public RowCount(String table, int existing, int incoming, String note) {
            this.table = table;
            this.existing = existing;
            this.incoming = incoming;
            this.note = note;
        }
    }

    @Data
    public static class ThemeChange {
        private String theme;
        private Integer strength;
        private String status;
        private String leader;
        /** 新建 / 强度 40→70 / 状态 退潮→扩散 之类，人眼扫一遍就知道有没有写反。 */
        private String note;
    }

    @Data
    public static class MarketRow {
        private String name;
        private String stored;
        private String source;

        public MarketRow(String name, String stored, String source) {
            this.name = name;
            this.stored = stored;
            this.source = source;
        }
    }

    @Data
    public static class ScoreImpact {
        private Integer dimsBefore;
        private Integer dimsAfter;
        private String temperatureBefore;
        private String temperatureAfter;
        private String stageBefore;
        private String stageAfter;
        /** 分数预览失败时的中文原因（阵眼/监管两维要联网取日 K）。失败不拦导入。 */
        private String unavailable;
    }
}
