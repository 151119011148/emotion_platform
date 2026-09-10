package com.wuwei.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 3.8 每日节点。node: 冰点/启动/发酵/高潮/分歧/退潮 */
@Data
@TableName("t_node_daily")
public class NodeDaily {
    @TableId(type = IdType.INPUT)
    private LocalDate tradeDate;
    private String node;
    private String prevNode;
    private String transition;
    private String triggerReason;
    private String forecast;
    /** JSON 数组字符串，如 ["总龙头能否晋级8板","大面家数"] */
    private String watchPoints;
    private String mainConcept;
    private String mainStage;
    private LocalDateTime createdAt;
}
