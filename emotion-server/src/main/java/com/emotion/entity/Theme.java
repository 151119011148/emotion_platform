package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_theme")
public class Theme {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long cycleId;
    private String name;
    private LocalDate startDate;
    private String status;
    private Integer strength;
    /** 题材催化硬度1-5（5=政策/产业级，1=Pure情绪）；PRD D2·催化剂硬度取数源。 */
    private Integer catalystHardness;
    /** 是否人工主线题材（题材表「设为主线」落 1）。 */
    private Integer isMainLine;
    private LocalDateTime createdAt;
}
