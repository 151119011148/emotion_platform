package com.emotion.market;

/**
 * 异动监管的三种触发。窗口长度是这里唯一的拍板数，改这一处即可。
 *
 * 只存事件、不存状态：监管期是查的时候按该票自己的日 K 数出来的，
 * 调窗口长度不需要回补数据。
 */
public enum SurveillanceKind {

    /** 交易所认定的"股票交易异常波动"。 */
    ZD("异常波动", 3),
    /** "严重异常波动"，通常伴随停牌核查风险，监管期长得多。 */
    SEVERE("严重异常波动", 10),
    /** 交易所直接发的监管工作函 / 监管警示 / 纪律处分，比公司自己发的公告更硬。 */
    EXCH("交易所监管", 10);

    private final String label;
    private final int days;

    SurveillanceKind(String label, int days) {
        this.label = label;
        this.days = days;
    }

    /** 中文标签，进 surv_note 给卡片用。 */
    public String label() {
        return label;
    }

    /** 公告日之后计入监管期的交易日数。 */
    public int days() {
        return days;
    }

    /**
     * 这一类进不进第 9 维的人群。ZD 不进：它是 2 连板以上的例行公告，实测占事件表的 92%
     * （194/205），单日能把人群撑到 43 只，算进来这一维就退化成"涨停池今天的均值"本身，
     * 和溢价维同源重复。只有 SEVERE 与 EXCH 才是踩坑文档里"本应继续涨却被按进风控"那种压力。
     * 名单仍然展示全部三类，靠 {@code Item.scored} 区分。
     */
    public boolean scores() {
        return this != ZD;
    }
}
