package com.emotion.market;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 一次 A股代码名单拉取的结果。
 *
 * dropped 必须显式带出来：北交所的 fs 条件会捎回定向可转债（810014 莱特定转这种），
 * 它们不是股票，静默丢掉会让"5909 只里只剩 5906 只"变成一个对不上的数。
 */
@Data
public class StockListResult {

    private boolean ok;
    /** 上游 total，含被丢弃的非股票行。 */
    private int total;
    private List<StockRow> rows = new ArrayList<>();
    private List<String> dropped = new ArrayList<>();
    /** 翻页没走完（撞上页数护栏或中途失败），rows 只是全表的一部分。 */
    private boolean truncated;
    private String reason;

    public static StockListResult failed(String reason) {
        StockListResult result = new StockListResult();
        result.setOk(false);
        result.setReason(reason);
        return result;
    }
}
