package com.emotion.scheduler;

/**
 * 可被定时任务平台调用的执行体。
 *
 * <p>新增一个定时任务的两步：写一个实现本接口的 Spring bean，往 t_scheduler_job 插一行
 * （bean_name 填这个 bean 在容器里的名字，cron 填排班）。剩下的装载、触发、留痕由平台负责。
 *
 * <p>约定：不要用抛异常表达「这次不用做事」。那是正常分支，返回
 * {@link TaskResult#skip(String)} 并说清为什么——翻留痕的时候，"今天不是交易日"和
 * "连不上上游"是两种完全不同的事情，混在异常栈里就看不出来了。
 */
public interface ManagedTask {

    /**
     * 执行一次。
     *
     * @return 结果，含给人看的一句话结论；抛异常由平台兜底记成 FAILED。
     */
    TaskResult run();
}
