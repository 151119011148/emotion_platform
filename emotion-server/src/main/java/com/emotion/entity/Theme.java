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
    private LocalDateTime createdAt;
}
