package com.emotion.scheduler;

import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Quartz 与 Spring 容器的接合处。
 *
 * <p>默认情况下 Quartz 自己 new 出 Job 实例，那条链路上拿到不到任何 Spring bean，
 * TaskDispatchJob 里的 @Autowired 全是 null。换成 SpringBeanJobFactory 之后 Job 才参与注入。
 *
 * <p>顺手把 ApplicationContext 挂到 SchedulerContext 上：那是 {@link TaskDispatchJob}
 * 的第二条取容器的退路，万一哪天这里的 JobFactory 被别处的定制顶掉，任务还能跑。
 */
@Configuration
public class QuartzConfig {

    @Bean
    public SchedulerFactoryBeanCustomizer emotionQuartzCustomizer(ApplicationContext applicationContext) {
        SpringBeanJobFactory jobFactory = new SpringBeanJobFactory();
        jobFactory.setApplicationContext(applicationContext);

        Map<String, Object> schedulerContext = new HashMap<>();
        schedulerContext.put("applicationContext", applicationContext);

        return factoryBean -> {
            factoryBean.setJobFactory(jobFactory);
            factoryBean.setSchedulerContextAsMap(schedulerContext);
        };
    }
}
