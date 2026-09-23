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
    /**
     * 空间破局节点所需的 5 列（V34 迁移 V34__node_event_space_break.sql）。
     *
     * <p><strong>破局日 = 空间板断板日 = 观察日（0 候选）</strong>，
     * <strong>破局次日 = 修复日 = 出手日（唯一产候选的日子）</strong>，
     * 两者都是 t_node_event 既有「D0 → T+1 → 确认」两日结构的一种取法，所以不新开表。
     *
     * <p>nodeType 是「类型轴」，只认 START/DIVERGE/SWITCH/SPACE_BREAK/SPACE_BREAK_NEXT 五个具体值；
     * 策略没识别出来的就是 NULL，展示层显示「未识别」——不要回落成「普通 / 常规 / 其他」，
     * 那是反义定义，每加一个 node_type 词义就要跟着变一次（PRD §2 命名约定）。
     */
    private String nodeType;
    /** 破局股代码：破局日里那只断了板的空间板本身。它是锚，不进候选池。 */
    private String breakStockCode;
    /** 破局股断板前的连板数（当时的空间板高度），破局次日降级判定要用。 */
    private Integer breakBoard;
    /** 断板形态：ZB_BREAK 炸板断板 / MILD_BREAK 温和断板 / A_KILL A杀（判退潮，不判破局）。 */
    private String breakForm;
    /** 破局次日的修复判定：PENDING 待判定 / SUCCESS 修复成功可出手 / FAILED 修复失败降级为观察日。周期节点此列恒 NULL。 */
    private String repairStatus;
    private LocalDateTime createdAt;
}
