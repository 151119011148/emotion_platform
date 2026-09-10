package com.wuwei.engine;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 节点判定引擎输出（PRD §4.2） */
@Data
public class NodeResult {
    private String node;
    private String prevNode;
    private String transition;
    private String triggerReason;
    private String forecast;
    private List<String> watchPoints = new ArrayList<String>();
    private String mainConcept;
    private String mainStage;
}
