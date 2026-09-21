package com.emotion.scheduler;

import com.emotion.entity.SchedulerRun;
import com.emotion.mapper.SchedulerRunMapper;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

import java.time.LocalDateTime;

/**
 * 所有任务的统一入口：Quartz 只认这一个类，真正的活儿交给 t_scheduler_job 里登记的 bean 去做。
 *
 * <p>这么绕一层是为了让「加一个任务」不用改调度代码：新增任务 = 新增一个 ManagedTask 实现 +
 * 表里插一行。不然每加一类任务就要写一个 Job 子类、再改一处装配的地方。
 *
 * <p>留痕写在这里而不是各任务里：留痕的口径（耗时怎么算、异常怎么截断、跳过要不要留）
 * 该只有一处实现，散到各个任务里很快就会各写各的。
 */
public class TaskDispatchJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatchJob.class);

    private static final String KEY_BEAN = "beanName";
    private static final String KEY_SOURCE = "source";
    private static final String MANUAL = "MANUAL";

    @Autowired
    private ApplicationContext springContext;

    @Autowired
    private SchedulerRunMapper runMapper;

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        String jobName = context.getJobDetail().getKey().getName();
        String beanName = context.getJobDetail().getJobDataMap().getString(KEY_BEAN);
        String source = resolveSource(context, jobName);

        SchedulerRun record = new SchedulerRun();
        record.setJobName(jobName);
        record.setSource(source);

        LocalDateTime started = LocalDateTime.now();
        record.setStartedAt(started);
        long t0 = System.currentTimeMillis();

        try {
            ManagedTask task = resolveTask(jobName, beanName, context);
            TaskResult result = task.run();
            String status = result.getStatus() == null ? TaskResult.SUCCESS : result.getStatus();
            record.setStatus(status);
            record.setMessage(trim(result.getMessage()));
            log.info("任务[{}] {}：{}", jobName, status, result.getMessage());
        } catch (Exception e) {
            record.setStatus(TaskResult.FAILED);
            record.setMessage(trim(e.getClass().getSimpleName() + ": " + e.getMessage()));
            log.error("任务[{}] 执行失败 bean={}", jobName, beanName, e);
        } finally {
            LocalDateTime finished = LocalDateTime.now();
            record.setFinishedAt(finished);
            record.setDurationMs(System.currentTimeMillis() - t0);
            saveQuietly(record, jobName);
        }
    }

    /**
     * 触发器级的 JobDataMap 优先：手工点「跑一次」时是塞在这一层带进来的。
     * 落到 detail 层的数据 map 会被当成默认值，反过来会让之后所有的自动执行都记成手工。
     */
    private String resolveSource(JobExecutionContext context, String jobName) {
        String fromTrigger = context.getTrigger().getJobDataMap().getString(KEY_SOURCE);
        if (MANUAL.equalsIgnoreCase(fromTrigger)) {
            return MANUAL;
        }
        return "SCHEDULE";
    }

    private ManagedTask resolveTask(String jobName, String beanName, JobExecutionContext context) {
        ApplicationContext ctx = springContext;
        if (ctx == null) {
            // SpringBeanJobFactory 没配上时的退路：applicationContext 也挂在 Quartz 的 SchedulerContext 上。
            try {
                ctx = (ApplicationContext) context.getScheduler().getContext().get("applicationContext");
            } catch (Exception ignored) {
                // 拿不到就在下面统一报错
            }
        }
        if (ctx == null) {
            throw new IllegalStateException("拿不到 ApplicationContext，任务 bean 无法解析");
        }
        if (beanName == null || beanName.trim().isEmpty()) {
            throw new IllegalStateException("任务[" + jobName + "]没配 bean_name，表里那行的 bean_name 是空的");
        }
        Object bean = ctx.getBean(beanName);
        if (!(bean instanceof ManagedTask)) {
            throw new IllegalStateException("任务[" + jobName + "]的 bean[" + beanName + "]不是 ManagedTask，实际类型："
                    + bean.getClass().getName());
        }
        return (ManagedTask) bean;
    }

    private void saveQuietly(SchedulerRun record, String jobName) {
        if (runMapper == null) {
            log.warn("留痕 mapper 未注入，任务[{}]本次结果没有入库", jobName);
            return;
        }
        try {
            runMapper.insert(record);
        } catch (Exception e) {
            // 留痕写不进去不能反过来把正事拖黄：任务本身已经跑完了
            log.error("任务[{}]留痕写入失败", jobName, e);
        }
    }

    /** 列宽是 1000，超了会整条插不进去，宁可截掉尾巴。 */
    private String trim(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 900 ? message.substring(0, 900) + "…" : message;
    }
}
