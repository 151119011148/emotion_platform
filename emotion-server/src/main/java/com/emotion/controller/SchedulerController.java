package com.emotion.controller;

import com.emotion.entity.SchedulerRun;
import com.emotion.scheduler.SchedulerPlatform;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.SchedulerJobVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 定时任务的维护口。
 *
 * <p>接口而不是命令行：改 cron 要是得 SQL 连上去敲 UPDATE，人就会攒着不动，
 * 半年后没人记得当初为什么设在那个点。放成接口好处是顺手，代价是——这里没做鉴权分级，
 * 能登录的人都能改，所以每个动作都建议保留留痕（执行结果本来也是入库的）。
 */
@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    private final SchedulerPlatform platform;

    public SchedulerController(SchedulerPlatform platform) {
        this.platform = platform;
    }

    /** 任务列表：定义 + 下次触发时间 + 上次执行结果。 */
    @GetMapping("/jobs")
    public ApiResponse<List<SchedulerJobVO>> jobs() {
        return ApiResponse.ok(platform.list());
    }

    /**
     * 重新把表里的定义同步进 Quartz。
     *
     * <p>绕开接口直接改库之后打一下这个；正常走本接口的改动不需要。
     */
    @PostMapping("/reload")
    public ApiResponse<Integer> reload() {
        return ApiResponse.ok(platform.sync());
    }

    /** 改排班。cron 是 Quartz 六位/七位式（带秒），例如每天 19:00 = 0 0 19 * * ? */
    @PostMapping("/jobs/{name}/cron")
    public ApiResponse<SchedulerJobVO> updateCron(@PathVariable String name,
                                                  @RequestParam String cron) {
        platform.updateCron(name, cron);
        return ApiResponse.ok(platform.list().stream()
                .filter(v -> name.equals(v.getJobName()))
                .findFirst()
                .orElse(null));
    }

    /** 停用 / 启用。停用时连带摘掉 Quartz 里的触发器。 */
    @PostMapping("/jobs/{name}/enabled")
    public ApiResponse<SchedulerJobVO> updateEnabled(@PathVariable String name,
                                                     @RequestParam boolean enabled) {
        platform.updateEnabled(name, enabled);
        return ApiResponse.ok(platform.list().stream()
                .filter(v -> name.equals(v.getJobName()))
                .findFirst()
                .orElse(null));
    }

    /** 立刻跑一次，不等到点。留痕里记为 MANUAL。 */
    @PostMapping("/jobs/{name}/run")
    public ApiResponse<String> runOnce(@PathVariable String name) {
        platform.triggerNow(name);
        return ApiResponse.ok("已触发：" + name + "（异步执行，结果看 runs）");
    }

    /** 执行留痕。不传 job 就是全部任务的最近记录。 */
    @GetMapping("/runs")
    public ApiResponse<List<SchedulerRun>> runs(@RequestParam(required = false) String job,
                                                @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(platform.runs(job, limit));
    }
}
