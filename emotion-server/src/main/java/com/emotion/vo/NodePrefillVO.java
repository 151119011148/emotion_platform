package com.emotion.vo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 节点页「从今日天梯新增节点」的轻量预填：D0 日期、前置过滤器两项家数、天梯最高板、
 * 今日龙头候选。全部读本地表（t_market_daily + t_market_stock 涨停池），不碰上游，
 * 取不到就空数组，让新建框自己说"未知"而不是造一个数。
 */
@Data
public class NodePrefillVO {

    private LocalDate date;
    /** 天梯最高板（D0 当日连板高度），用于默认"锚定龙头最高板数"。 */
    private Integer maxBoard;
    private Integer limitUpCount;
    private Integer limitDownCount;
    /** 今日涨停池 ≥2 板龙头，从高板到低按封单额排序，供新建框快速选锚定龙头。 */
    private List<Leader> leaders = new ArrayList<>();

    @Data
    public static class Leader {
        private String code;
        private String name;
        private String industry;
        private Integer board;
    }
}