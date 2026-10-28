package com.emotion.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import com.emotion.entity.SchedulerJob;
import com.emotion.mapper.SchedulerJobMapper;
import com.emotion.mapper.SchedulerRunMapper;

/**
 * scheduler.enabled=false 时的行为（本地联调 profile 就是这一档）。
 *
 * <p>要点不是「别跑任务」，而是<b>一行都不许写</b>：本地连的库和生产是同一个，
 * QRTZ_* 也是同一批表，装载动作本身就会改写生产排期。所以断言全落在
 * 「Quartz 没被 start、任务表没被查、维护动作当场拒绝」上。
 */
class SchedulerPlatformDisabledTest {

    private Scheduler scheduler;
    private SchedulerJobMapper jobMapper;
    private SchedulerPlatform platform;

    @BeforeEach
    void setUp() {
        scheduler = mock(Scheduler.class);
        jobMapper = mock(SchedulerJobMapper.class);
        platform = new SchedulerPlatform(scheduler, jobMapper, mock(SchedulerRunMapper.class),
                mock(ApplicationContext.class));
        ReflectionTestUtils.setField(platform, "schedulerEnabled", false);
    }

    @Test
    void 启动装载直接跳过() {
        platform.run(null);

        verifyNoInteractions(jobMapper);
        assertEquals(0, platform.sync());
        verifyNoInteractions(jobMapper);
    }

    @Test
    void 不会替Quartz补一次start() throws Exception {
        when(scheduler.isStarted()).thenReturn(false);

        platform.run(null);
        platform.sync();

        verifyNoInteractions(scheduler);
    }

    @Test
    void 改cron与启停当场拒绝() {
        when(jobMapper.selectList(any())).thenReturn(Collections.singletonList(job("daily_scan")));

        IllegalStateException cron = assertThrows(IllegalStateException.class,
                () -> platform.updateCron("daily_scan", "0 30 19 * * ?"));
        IllegalStateException toggle = assertThrows(IllegalStateException.class,
                () -> platform.updateEnabled("daily_scan", true));

        assertTrue(cron.getMessage().contains("scheduler.enabled=false"));
        assertTrue(toggle.getMessage().contains("scheduler.enabled=false"));
    }

    @Test
    void 立刻跑一次也走不通() throws Exception {
        when(jobMapper.selectList(any())).thenReturn(Collections.singletonList(job("daily_scan")));
        when(scheduler.isStarted()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> platform.triggerNow("daily_scan"));
    }

    private SchedulerJob job(String name) {
        SchedulerJob job = new SchedulerJob();
        job.setJobName(name);
        job.setJobGroup("DEFAULT");
        job.setBeanName("reviewObjectiveEntryTask");
        job.setCronExpr("0 30 19 * * ?");
        job.setEnabled(1);
        return job;
    }
}
