package com.wuwei.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** P1/P7 温度曲线 */
@Data
public class CurveVO {
    private List<String> dates = new ArrayList<String>();
    private List<Double> totals = new ArrayList<Double>();
    private List<String> nodes = new ArrayList<String>();
    private List<String> mainConcepts = new ArrayList<String>();
    /** 五维分数序列，key = market/concept/lianban/shouban/zhenyan */
    private Map<String, List<Double>> scores = new LinkedHashMap<String, List<Double>>();
}
