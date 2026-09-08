package com.emotion.market;

import java.time.LocalDate;

import lombok.Data;

/**
 * 一条异动监管公告，已从上游列表里判定类别。
 *
 * 只描述"发生过这件事"，不描述"现在还在监管期"——后者是查的时候数交易日数出来的。
 */
@Data
public class SurveillanceNotice {

    private String code;
    private String name;
    /** 公告日 D0。 */
    private LocalDate annDate;
    private SurveillanceKind kind;
    private String title;
    /** 上游类目码，判据就落在这里。 */
    private String columnCode;
    /** 上游公告 ID，落库按 (code, artCode) 幂等。 */
    private String artCode;
}
