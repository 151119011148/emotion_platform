package com.emotion.market;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 一只票在一个日期窗口内的公告拉取结果。
 *
 * ok=false 与"拉到了、窗口里确实没有异动公告"必须可区分：前者不能让调用方把这只票当成
 * "已经查过、没有事"，否则一次网络抖动就把一个真实的监管期永久漏掉。
 */
@Data
public class SurveillanceResult {

    private boolean ok;
    private String reason;
    private String code;
    /** 上游这个窗口里的公告总条数（含不属于异动的），用于判断是否翻页完。 */
    private int totalHits;
    /** 本次翻到的上游列表行数：翻页收口用它，不能用 notices.size()（那是过滤后的）。 */
    private int rows;
    private int pages;
    /**
     * 标题里带"异常波动/监管"却没有命中任何类目码的行数。上游哪天改类目结构，
     * 这个数会 nonzero——判据只认类目码，所以必须留一个哨兵，否则第 9 维会静默变成"永远无票在列"。
     */
    private int unmatchedSignals;
    private List<SurveillanceNotice> notices = new ArrayList<>();

    public static SurveillanceResult failed(String code, String reason) {
        SurveillanceResult result = new SurveillanceResult();
        result.setOk(false);
        result.setCode(code);
        result.setReason(reason);
        return result;
    }

    public static SurveillanceResult of(String code) {
        SurveillanceResult result = new SurveillanceResult();
        result.setOk(true);
        result.setCode(code);
        return result;
    }
}
