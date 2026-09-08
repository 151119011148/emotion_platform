package com.emotion.market;

import java.util.Collections;
import java.util.List;

import lombok.Data;

/**
 * 一次池子请求的结果。
 *
 * ok=false 和"池子为空"必须区分开：前者是行情源没响应（应当降级、留给人工），
 * 后者是当天确实没有涨停股（是真实数据）。把失败当成空值写进打分字段，会静默压低温度。
 */
@Data
public class PoolResult {

    private boolean ok;
    /** 家数，取上游的 tc 字段；rows 会被 pagesize 截断，不能用来数家数。 */
    private int tc;
    private List<PoolRow> rows = Collections.emptyList();
    private boolean truncated;
    private String reason;

    public static PoolResult failed(String reason) {
        PoolResult result = new PoolResult();
        result.setOk(false);
        result.setReason(reason);
        return result;
    }

    public static PoolResult empty() {
        PoolResult result = new PoolResult();
        result.setOk(true);
        result.setTc(0);
        return result;
    }

    public int maxConsecutive() {
        int max = 0;
        for (PoolRow row : rows) {
            if (row.getLbc() != null && row.getLbc() > max) {
                max = row.getLbc();
            }
        }
        return max;
    }
}
