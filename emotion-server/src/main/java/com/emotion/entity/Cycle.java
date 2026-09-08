package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_cycle")
public class Cycle {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal maxTemperature;
    private Integer maxHeight;
    private String leadingStock;
    private String mainTheme;
    private String status;
    private LocalDateTime createdAt;
}
