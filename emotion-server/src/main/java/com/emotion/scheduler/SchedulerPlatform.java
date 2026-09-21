package com.emotion.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.SchedulerJob;
import com.emotion.entity.SchedulerRun;
import com.emotion.mapper.SchedulerJobMapper;
import com.emotion.mapper.SchedulerRunMapper;
import com.emotion.vo.SchedulerJobVO;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * 定时任务平台的本体：把 t_scheduler_job 里的定义同步进 Quartz，并对外开放维护动作。
 *
 * <p>任务排期不在代码里写死：代码里只有一个个 ManagedTask 实现，什么时候跑、还跑不跑，
 * 全在这张表里。改 cron / 停用 / 立刻跑一次都不需要重启整机。
 *
 * <p>启动时同步一次（{@link ApplicationRunner}），之后改了表数据要显式调一次 sync()
 * 或打 /api/scheduler/reload —— 不轮询表：本机单机跑，没人会绕过接口直接改库。
 */
@Service
public class SchedulerPlatform implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchedulerPlatform.class);

    /** cron 一律按 A股 时区解释，不跟着部署机器的时区漂。 */
    private static final TimeZone CN = TimeZone.getTimeZone("Asia/Shanghai");
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String TRIGGER_SUFFIX = "-trigger";
    private static final String KEY_BEAN = "beanName";
    private static final String KEY_SOURCE = "source";

    private final Scheduler scheduler;
    private final SchedulerJobMapper jobMapper;
    private final SchedulerRunMapper runMapper;
    private final ApplicationContext applicationContext;

    public SchedulerPlatform(Scheduler scheduler,
                             SchedulerJobMapper jobMapper,
                             SchedulerRunMapper runMapper,
                             ApplicationContext applicationContext) {
        this.scheduler = scheduler;
        this.jobMapper = jobMapper;
        this.runMapper = runMapper;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("定时任务平台装载 {} 条任务", sync());
    }

    /**
     * 把表里的定义灌进 Quartz：新增任务、改了 cron、改了停用开关都会在这一步生效。
     *
     * @return 成功排上的条数
     */
    public int sync() {
        if (!ensureStarted()) {
            log.error("Quartz 没起来，本次同步跳过——这一次的后果是所有任务都不跑");
            return 0;
        }
        List<SchedulerJob> jobs = jobMapper.selectList(null);
        int ok = 0;
        for (SchedulerJob job : jobs) {
            try {
                apply(job);
                if (isEnabled(job)) {
                    ok++;
                }
            } catch (Exception e) {
                // 一条装载失败不能拖累其它任务：错的那条留给日志和下一次 reload
                log.error("任务[{}]装载失败 bean={} cron={}", job.getJobName(), job.getBeanName(),
                        job.getCronExpr(), e);
            }
        }
        return ok;
    }

    /** 页面上看到的任务列表：定义 + 下一次 + 上一次结果。 */
    public List<SchedulerJobVO> list() {
        List<SchedulerJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<SchedulerJob>()
                .orderByAsc(SchedulerJob::getJobName));
        List<SchedulerJobVO> vos = new ArrayList<>();
        for (SchedulerJob job : jobs) {
            vos.add(toVO(job));
        }
        return vos;
    }

    /** 改 cron。先验证再写库，别把一个非法表达式存进去让 Quartz 在启动时才炸。 */
    public SchedulerJob updateCron(String jobName, String cron) {
        SchedulerJob job = require(jobName);
        String expr = cron == null ? "" : cron.trim();
        if (expr.isEmpty() || !CronExpression.isValidExpression(expr)) {
            throw new IllegalArgumentException("cron 表达式不合法：" + expr);
        }
        job.setCronExpr(expr);
        jobMapper.updateById(job);
        try {
            apply(job);
        } catch (SchedulerException e) {
            throw new IllegalStateException("cron 已入库但同步到 Quartz 失败：" + e.getMessage(), e);
        }
        return job;
    }

    /** 停用 / 启用。停用会把 Quartz 里的触发器一并摘掉，不只是改个状态位。 */
    public SchedulerJob updateEnabled(String jobName, boolean enabled) {
        SchedulerJob job = require(jobName);
        job.setEnabled(enabled ? 1 : 0);
        jobMapper.updateById(job);
        try {
            apply(job);
        } catch (SchedulerException e) {
            throw new IllegalStateException("状态已入库但同步到 Quartz 失败：" + e.getMessage(), e);
        }
        return job;
    }

    /** 立刻跑一次（留痕记 MANUAL，和定时触发区分开）。 */
    public void triggerNow(String jobName) {
        SchedulerJob job = require(jobName);
        if (!ensureStarted()) {
            throw new IllegalStateException("Quartz 没起来，无法执行");
        }
        try {
            JobKey jk = JobKey.jobKey(job.getJobName(), job.getJobGroup());
            if (!scheduler.checkExists(jk)) {
                // 停用的任务也允许「现在试一次」：先注册上去再触发，测完不动它的停用状态。
                apply(job);
            }
            JobDataMap data = new JobDataMap();
            data.put(KEY_SOURCE, "MANUAL");
            scheduler.triggerJob(jk, data);
        } catch (SchedulerException e) {
            throw new IllegalStateException("立即执行失败：" + e.getMessage(), e);
        }
    }

    /** 最近的执行留痕。jobName 为空时不挑任务。 */
    public List<SchedulerRun> runs(String jobName, int limit) {
        int size = limit <= 0 || limit > 200 ? 20 : limit;
        LambdaQueryWrapper<SchedulerRun> q = new LambdaQueryWrapper<SchedulerRun>()
                .orderByDesc(SchedulerRun::getStartedAt)
                .last("LIMIT " + size);
        if (jobName != null && !jobName.trim().isEmpty()) {
            q.eq(SchedulerRun::getJobName, jobName.trim());
        }
        return runMapper.selectList(q);
    }

    // ------------------------------------------------------------------ 内部实现

    private void apply(SchedulerJob job) throws SchedulerException {
        requireCron(job);
        if (!isEnabled(job)) {
            unschedule(job);
            return;
        }
        // 早发现的手段：bad bean 名此刻就暴露，不要等到 19:00 那次触发才发现任务跑不起来。
        requireManagedTask(job);

        JobKey jk = JobKey.jobKey(job.getJobName(), job.getJobGroup());
        TriggerKey tk = TriggerKey.triggerKey(job.getJobName() + TRIGGER_SUFFIX, job.getJobGroup());

        JobDetail detail = JobBuilder.newJob(TaskDispatchJob.class)
                .withIdentity(jk)
                .withDescription(job.getDescription())
                .usingJobData(KEY_BEAN, job.getBeanName())
                .storeDurably()
                .build();
        scheduler.addJob(detail, true);

        Trigger old = scheduler.getTrigger(tk);
        if (old == null) {
            scheduler.scheduleJob(triggerOf(job));
        } else if (!sameCron(old, job.getCronExpr().trim())) {
            scheduler.rescheduleJob(tk, triggerOf(job));
        }
    }

    private void unschedule(SchedulerJob job) throws SchedulerException {
        TriggerKey tk = TriggerKey.triggerKey(job.getJobName() + TRIGGER_SUFFIX, job.getJobGroup());
        if (scheduler.checkExists(tk)) {
            scheduler.unscheduleJob(tk);
        }
        JobKey jk = JobKey.jobKey(job.getJobName(), job.getJobGroup());
        if (scheduler.checkExists(jk)) {
            scheduler.deleteJob(jk);
        }
    }

    private Trigger triggerOf(SchedulerJob job) {
        CronScheduleBuilder schedule = CronScheduleBuilder.cronSchedule(job.getCronExpr().trim())
                .inTimeZone(CN);
        // 错过触发怎么办：默认放过这次（行情类任务凌晨补跑拉到的是昨天的数，纯污染）；
        // 表里标 FIRE_ONCE_NOW 的才补跑一次。
        if ("FIRE_ONCE_NOW".equalsIgnoreCase(job.getMisfirePolicy())) {
            schedule = schedule.withMisfireHandlingInstructionFireAndProceed();
        } else {
            schedule = schedule.withMisfireHandlingInstructionDoNothing();
        }
        return TriggerBuilder.newTrigger()
                .withIdentity(TriggerKey.triggerKey(job.getJobName() + TRIGGER_SUFFIX, job.getJobGroup()))
                .forJob(JobKey.jobKey(job.getJobName(), job.getJobGroup()))
                .withSchedule(schedule)
                .build();
    }

    private boolean sameCron(Trigger old, String cronExpr) {
        if (!(old instanceof CronTrigger)) {
            return false;
        }
        String expr = ((CronTrigger) old).getCronExpression();
        return expr != null && expr.equals(cronExpr);
    }

    private SchedulerJobVO toVO(SchedulerJob job) {
        SchedulerJobVO vo = new SchedulerJobVO();
        vo.setJobName(job.getJobName());
        vo.setJobGroup(job.getJobGroup());
        vo.setBeanName(job.getBeanName());
        vo.setCronExpr(job.getCronExpr());
        vo.setMisfirePolicy(job.getMisfirePolicy());
        vo.setEnabled(job.getEnabled());
        vo.setDescription(job.getDescription());

        try {
            Trigger trigger = scheduler.getTrigger(
                    TriggerKey.triggerKey(job.getJobName() + TRIGGER_SUFFIX, job.getJobGroup()));
            if (trigger != null) {
                vo.setNextFireTime(toLocal(trigger.getNextFireTime()));
            }
        } catch (SchedulerException e) {
            log.warn("读取任务[{}]的下次触发时间失败", job.getJobName(), e);
        }

        SchedulerRun last = lastRun(job.getJobName());
        if (last != null) {
            vo.setLastStatus(last.getStatus());
            vo.setLastMessage(last.getMessage());
            vo.setLastFinishedAt(last.getFinishedAt());
            vo.setLastDurationMs(last.getDurationMs());
        }
        return vo;
    }

    private SchedulerRun lastRun(String jobName) {
        List<SchedulerRun> rows = runMapper.selectList(new LambdaQueryWrapper<SchedulerRun>()
                .eq(SchedulerRun::getJobName, jobName)
                .orderByDesc(SchedulerRun::getStartedAt)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LocalDateTime toLocal(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(date.getTime()), CN_ZONE);
    }

    private SchedulerJob require(String jobName) {
        if (jobName == null || jobName.trim().isEmpty()) {
            throw new IllegalArgumentException("任务名不能为空");
        }
        List<SchedulerJob> rows = jobMapper.selectList(new LambdaQueryWrapper<SchedulerJob>()
                .eq(SchedulerJob::getJobName, jobName.trim())
                .last("LIMIT 1"));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("没有这个任务：" + jobName);
        }
        return rows.get(0);
    }

    private void requireCron(SchedulerJob job) {
        if (job.getCronExpr() == null || job.getCronExpr().trim().isEmpty()) {
            throw new IllegalArgumentException("任务[" + job.getJobName() + "]的 cron_expr 是空的");
        }
    }

    private void requireManagedTask(SchedulerJob job) {
        if (job.getBeanName() == null || job.getBeanName().trim().isEmpty()) {
            throw new IllegalArgumentException("任务[" + job.getJobName() + "]没配 bean_name");
        }
        if (!applicationContext.containsBean(job.getBeanName().trim())) {
            throw new IllegalArgumentException("容器里没有叫 " + job.getBeanName() + " 的 bean");
        }
        Object bean = applicationContext.getBean(job.getBeanName().trim());
        if (!(bean instanceof ManagedTask)) {
            throw new IllegalArgumentException("bean[" + job.getBeanName() + "]不是 ManagedTask，实际类型："
                    + bean.getClass().getName());
        }
    }

    private boolean isEnabled(SchedulerJob job) {
        return job.getEnabled() != null && job.getEnabled() == 1;
    }

    /**
     * 确认调度器真的在跑。
     *
     * <p>配了 startup-delay 时 ApplicationRunner 会比 Quartz 起得早，此刻 addJob 会被拒；
     * 与其让每个操作方法各自踩一遍，不如在这里统一兜住并留下日志。
     */
    private boolean ensureStarted() {
        try {
            if (!scheduler.isStarted()) {
                scheduler.start();
            }
            return true;
        } catch (SchedulerException e) {
            log.error("Quartz 启动失败", e);
            return false;
        }
    }
}
