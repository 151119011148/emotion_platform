package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_leading_stock")
public class LeadingStock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long themeId;
    private String name;
    private String role;
    private Integer maxConsecutive;
    private LocalDate startDate;
    private String status;
    private LocalDateTime createdAt;
}
