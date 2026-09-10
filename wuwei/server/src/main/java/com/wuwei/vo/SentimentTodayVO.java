package com.wuwei.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** P1 仪表盘聚合视图：总分+五维+节点+主线+龙头+轮动+操作指令 */
@Data
public class SentimentTodayVO {
    private String tradeDate;
    private Double totalScore;
    private Double prevTotal;
    private Double scoreMarket;
    private Double scoreConcept;
    private Double scoreLianban;
    private Double scoreShouban;
    private Double scoreZhenyan;
    private Boolean forceExit;
    private String forceReason;

    /** 五维明细（引擎落库快照） */
    private Map<String, Object> details = new LinkedHashMap<String, Object>();

    // 节点
    private String node;
    private String prevNode;
    private String transition;
    private String triggerReason;
    private String forecast;
    private List<String> watchPoints = new ArrayList<String>();

    // 主线
    private String mainConcept;
    private String mainStage;
    private Integer catalystHardness;

    // 龙头
    private String leaderName;
    private Integer leaderBoard;
    private String leaderAction;

    // 轮动信号 [{type,message}]
    private List<Map<String, Object>> rotationSignals = new ArrayList<Map<String, Object>>();

    // 操作指令 {position, direction, forbidden, anchor}
    private Map<String, Object> instruction = new LinkedHashMap<String, Object>();
}
