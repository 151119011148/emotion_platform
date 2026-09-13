package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 连板天梯某日人工指定的"总龙头"。
 *
 * <p>全市场最高连板自动标"空间板"（引擎判，见 {@code TiantiService#roleOf}）；
 * "总龙头"这个身份是用户自己的判断，绑 {@code user_id}，每账号每交易日一行。
 * 保存只落库不打行情——腾讯一抽风不能连用户标个龙头都改不了，名称反查回填、不信前端。
 */
@Data
@TableName("t_manual_leader")
public class ManualLeader {

    @TableId
    private Long userId;
    private LocalDate tradeDate;
    private String code;
    private String name;
    private LocalDateTime updatedAt;
}