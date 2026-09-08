package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一轮周期里"它怎么反馈就是周期怎么定性"的那只票 —— 周期阵眼 / 周期总龙。
 *
 * <p>人工登记，绑 user_id：哪些票算这轮的阵眼是判断，不是行情事实，测试号能设自己的阵眼。
 * 跨度起点 {@code start_date} 是唯一的输入，其余（最高连板、断板日、回撤、当日涨跌）
 * 一律从日 K 现算，不入库、不让人手填——填了就一定会和行情对不上。
 *
 * <p>不接 {@code t_cycle}（那张表零行、零写入路径），{@code cycle_tag} 只是给人看的分组标签。
 */
@Data
@TableName("t_anchor")
public class Anchor {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 6 位代码；存在性由服务端对着 t_stock 校，名称一律反查回填，不信前端传来的。 */
    private String stockCode;
    private String stockName;
    /** CYCLE=周期阵眼 / LEADER=周期总龙。两者进分的方式相同，只是叫法。 */
    private String role;
    /** 同轮归组，如 2026-08；只作显示分组。 */
    // 下面三列都标了 ALWAYS：PUT 是整条替换，没带过来就是清空。默认策略 NOT_NULL 会让
    // "清空终点=仍在位"这条静默失效——更新语句里根本没有这一列。
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String cycleTag;
    /** 跨度起点：起爆日或人工认定的锚点日。 */
    private LocalDate startDate;
    /** 跨度终点，NULL 表示仍在位。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate endDate;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
