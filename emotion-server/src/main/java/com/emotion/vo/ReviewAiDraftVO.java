package com.emotion.vo;

import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 AI 草稿的结果。与 {@link ReviewExportVO} 分开放，是因为这一份<b>不是文档</b>：
 * 它不进 {@code review_md}、不进 {@code doc_notes}、不参与导出的幂等比较，
 * 前端把它单独摆在【AI 草稿】那一栏，他自己那行 {@code ✍️} 一个字都不动。
 *
 * <p>{@code facts} 原样交出去（这次发给模型的令牌各自的系统值）：草稿里任何一个数他都能在这里
 * 对回出处，不然「这数是系统给的吗」只能靠信。
 */
@Data
public class ReviewAiDraftVO {

    private LocalDate date;
    /** 产出这段文字的模型名。 */
    private String model;
    /** 回填后的草稿；{@code null} = 这次没有产出可用草稿。 */
    private String draft;
    private Map<String, String> facts = new LinkedHashMap<String, String>();
    /** 中文计量词（「三倍」「两天」这类）——不拦，但报给他看。 */
    private List<String> warnings = new ArrayList<String>();
    /** {@code true} = 模型产出被数字门卫拦下，或写出了系统没给的令牌。 */
    private boolean blocked;
    /** 没成时的原因，人话。 */
    private String message;
}
