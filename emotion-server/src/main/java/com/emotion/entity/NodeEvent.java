package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_node_event")
public class NodeEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long cycleId;
    private String systemType;
    private String anchorStock;
    private Integer anchorMaxBoard;
    private LocalDate d0Date;
    private String d0Candidates;
    private LocalDate t1Date;
    private Integer t1AnchorRepack;
    private Integer t1PromotionCount;
    private BigDecimal t1PromotionRate;
    private Integer nodeValid;
    private String nodeStock;
    private Integer nodeStockMaxBoard;
    private Long anchorId;
    private String theme;
    private BigDecimal d0Score;
    private String d0Cycle;
    private LocalDateTime lastRecalcAt;
    private String conclusionReason;
    private Integer filterPassed;
    private String filterDetail;
    private String status;
    /**
     * 状态来路：这条「有效/失效」是平台按哪几个数算出来的，一句话。
     *
     * <p>和 {@link #note} 分成两列是有意的——note 是他自己的地盘，采纳一次不该把他的备注盖掉。
     * ALWAYS 让「撤销采纳」能把这格清回 NULL，MP 默认的 NOT_NULL 会跳过 null、写进去就出不来。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String statusNote;
    private String note;
    private LocalDateTime createdAt;
}
