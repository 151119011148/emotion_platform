package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务执行留痕。
 *
 * <p>Quartz 的账能回答「下一次什么时候触发」，回答不了「上次为什么不成功」。
 * 「今天不是交易日，跳过」这类结论只有这里看得见，所以每次触发都写一行——
 * 包括跳过的那些。留着空才有才是可疑信号：定时压根没跑起来。
 */
@Data
@TableName("t_scheduler_run")
public class SchedulerRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String jobName;

    /** SCHEDULE=到点触发 / MANUAL=手工跑一次。 */
    private String source;

    /** SUCCESS / FAILED / SKIPPED。 */
    private String status;

    private String message;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 耗时毫秒。 */
    private Long durationMs;
}
