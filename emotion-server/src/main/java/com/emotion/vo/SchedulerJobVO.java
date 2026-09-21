package com.emotion.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 定时任务在维护页面上的一行：定义 + 下一次什么时候跑 + 上一次跑成了没有。 */
@Data
public class SchedulerJobVO {

    private String jobName;

    private String jobGroup;

    private String beanName;

    private String cronExpr;

    private String misfirePolicy;

    private Integer enabled;

    private String description;

    /** 下一次触发时间；没排上（停用或没注册）时为 null。 */
    private LocalDateTime nextFireTime;

    /** 上一次执行的结果，取自 t_scheduler_run。 */
    private String lastStatus;

    private String lastMessage;

    private LocalDateTime lastFinishedAt;

    private Long lastDurationMs;
}
