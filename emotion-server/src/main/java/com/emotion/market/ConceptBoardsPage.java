package com.emotion.market;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 概念板块清单的一页（pz 硬上限 100，翻页合并）。 */
@Data
public class ConceptBoardsPage {
    private boolean ok;
    private String reason;
    private int total;
    private List<ConceptBoard> rows = new ArrayList<>();
}