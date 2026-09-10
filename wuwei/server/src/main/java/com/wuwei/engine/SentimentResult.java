package com.wuwei.engine;

import lombok.Data;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 5维评分引擎的单日计算结果（PRD §4.1）。
 * 除五个维分外，携带节点判定引擎所需的当日盘面上下文，避免二次查询。
 */
@Data
public class SentimentResult {

    private LocalDate tradeDate;

    // 五维分数 0-100
    private double market;
    private double concept;
    private double lianban;
    private double shouban;
    private double zhenyan;
    private double total;

    // 强制退潮
    private boolean forceExit;
    private String forceReason;

    // 五维明细（给前端卡片展开用）
    private Map<String, Object> details = new LinkedHashMap<>();

    // ================= 供节点引擎使用的当日上下文 =================
    private int ztCount;        // 涨停家数（含首板）
    private int dtCount;        // 跌停家数
    private int bombCount;      // 炸板家数
    private int firstCount;     // 首板家数
    private int maxBoard;       // 全市场最高板
    private int bigNoodleCount; // 大面+核按钮家数
    private double midPromoteRate;   // 中位(3-4板)晋级率
    private double prevTotal;        // 前一日总分

    private String leaderName;       // 总龙头名称（当日）
    private int leaderBoard;         // 总龙头板数
    private String leaderAction;     // PROMOTE/HOLD/BREAK/NUKE

    // 主线信息
    private String mainConceptId;
    private String mainConceptName;
    private String mainStage;
    private int mainContinuousDays;
    private int mainZtCount;

    // 主线5要素原始比例
    private double ztRatio;      // 涨停聚集度
    private double heightRatio;  // 高度聚集度
    private double amountRatio;  // 成交额聚集度
}
