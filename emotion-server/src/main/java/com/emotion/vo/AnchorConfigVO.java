package com.emotion.vo;

import java.time.LocalDate;

import lombok.Data;

/**
 * 阵眼下拉的轻量配置视图：节点页「锚定龙头从当日生效人工阵眼里选」的数据源。
 * 只含登记信息（id/代码/名称/角色/跨度），不含任何行情——取它不该打上游。
 */
@Data
public class AnchorConfigVO {
    private Long id;
    private String stockCode;
    private String stockName;
    private String role;
    private String roleLabel;
    private LocalDate startDate;
    private LocalDate endDate;
    private String cycleTag;
}