package com.emotion.scheduler;

/**
 * 一次任务执行的结果。
 *
 * <p>三个状态各有归宿：成功、跳过（这次不用做，是正常分支）、失败（该做没做成，要人来看）。
 */
public class TaskResult {

    public static final String SUCCESS = "SUCCESS";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";

    private final String status;
    private final String message;

    private TaskResult(String status, String message) {
        this.status = status;
        this.message = message;
    }

    public static TaskResult ok(String message) {
        return new TaskResult(SUCCESS, message);
    }

    public static TaskResult skip(String message) {
        return new TaskResult(SKIPPED, message);
    }

    public static TaskResult fail(String message) {
        return new TaskResult(FAILED, message);
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
