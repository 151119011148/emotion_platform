package com.emotion.market;

import lombok.Data;

/** 一个概念板块：东财 BK 代码 + 名称（D2 题材索引扫描时用）。 */
@Data
public class ConceptBoard {
    private String code;
    private String name;
}