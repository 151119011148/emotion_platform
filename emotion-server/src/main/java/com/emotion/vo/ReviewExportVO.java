package com.emotion.vo;

import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台 → md 的导出结果。
 *
 * <p>{@code generated} 是唯一需要前端区分的一格：{@code false} 表示那天导入过、
 * 内容就是库里那份原文（一个字没动，贴回去再导入是幂等的）；{@code true} 表示这份是按
 * 库里数据<b>重建</b>的模板——meta 块重建成当前列值，正文要么沿用那天存过的，
 * 要么是新起的空骨架。
 *
 * <p>{@code omittedKeys} 记录"库里这格是空的，所以没写进 meta"。这一栏存在的理由是
 * 键没写和键写了空值是两回事：没写的键导入时那列不动，想清空得自己把键补上、留个冒号。
 */
@Data
public class ReviewExportVO {

    private LocalDate date;
    private String content;
    private boolean generated;
    private List<String> warnings = new ArrayList<String>();
    private List<String> omittedKeys = new ArrayList<String>();
}
