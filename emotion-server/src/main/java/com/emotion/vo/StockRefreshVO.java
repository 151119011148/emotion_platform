package com.emotion.vo;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/** 一次 A股代码表刷新的结果，原样回给界面，让"灌了多少只"这件事可核对。 */
@Data
public class StockRefreshVO {

    /** 上游 total，含被过滤掉的行。 */
    private int upstreamTotal;
    /** 本次写入的股票数（已过滤）。 */
    private int fetched;
    private int inserted;
    /** 已存在、被重写一遍的行数——包含名称没变的行，所以它是"重写"而不是"改名"。 */
    private int rewritten;
    /** 被白名单挡掉的行（"810014 莱特定转"），列出来而不是静默丢弃。 */
    private List<String> dropped = new ArrayList<>();
    private boolean truncated;
    private String note;
}
