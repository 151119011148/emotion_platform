package com.emotion.market;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 一个概念板块的成分股名单（一页或多页合并后的容器）。 */
@Data
public class ConceptMembers {
    private boolean ok;
    private String reason;
    private int total;
    private List<StockRow> rows = new ArrayList<>();
}