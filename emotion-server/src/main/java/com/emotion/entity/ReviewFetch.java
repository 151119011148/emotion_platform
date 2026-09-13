package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日复盘「一键拉取」（T1-T8）最近一次编排的状态留档。
 *
 * <p>只记编排元信息（谁成谁败、写了多少行、汇总提示），行情/评分数据仍在各自业务表里。
 * 一张表服务所有账号——公开行情编排的结果与账号无关，只有 T8（评分回写）依赖登录态但
 * 不在这一张表里记内容（它记的是"算的结果有没有落库"，落库本身在 t_daily_record）。
 */
@Data
@TableName("t_review_fetch")
public class ReviewFetch {

    /** 汇总态：PENDING / RUNNING / DONE / PARTIAL / FAILED。 */
    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_DONE = "DONE";
    public static final String STATE_PARTIAL = "PARTIAL";
    public static final String STATE_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate tradeDate;
    private String overall;
    /** T1-T8 逐任务 {task,status,rows,msg} 的 JSON 数组。 */
    private String tasksJson;
    /** 该日编排的汇总提示（"监管无自动源"这类）。 */
    private String warnings;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}