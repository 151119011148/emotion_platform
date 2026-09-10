package com.wuwei.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/** 3.6 监管异动池。status: ORDINARY/SERIOUS/KEY_MONITOR/SUSPEND/RESUME */
@Data
@TableName("t_monitor_pool")
public class MonitorPool {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tsCode;
    private String name;
    private String status;
    private LocalDate enterDate;
    private LocalDate exitDate;
    private String relatedConcept;
    private Boolean isHighPosition;
}
