package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务定义：一个人在手机上看得懂的那层排班。
 *
 * <p>Quartz 自己的 QRTZ_* 只记「触发器现在什么状态」，cron 的原义、为什么设这个点、
 * 挂的是哪个执行体，都在这一行里。改这张表即改排班，启动时由
 * {@code SchedulerPlatform} 同步进 Quartz。
 */
@Data
@TableName("t_scheduler_job")
public class SchedulerJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务名，同时是 Quartz 的 JobKey；改名等于换一个任务（旧的会被摘掉）。 */
    private String jobName;

    private String jobGroup;

    /** 执行体：Spring 容器里实现了 ManagedTask 的 bean 名。 */
    private String beanName;

    /** Quartz cron（含秒位），一律按 Asia/Shanghai 解释。 */
    private String cronExpr;

    /** DO_NOTHING（默认，错过就错过）/ FIRE_ONCE_NOW（立刻补跑一次）。 */
    private String misfirePolicy;

    private Integer enabled;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
