package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * WaveRider 策略主实体：只有「叫什么名字、还跑不跑、当前用哪版配置」这三件事。
 *
 * <p>真正的参数全在 {@link StrategyVersion#getConfigJson()} 里。把可变参数放在版本表而不是这张表，
 * 是为了让「跑出这批候选时用的是哪套参数」永远有据可查——主表只留一个指向当前版本的指针。
 */
@Data
@TableName("t_strategy")
public class Strategy {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    /** 1=启用，0=停用。停用只影响定时任务，不影响手工跑。 */
    private Integer enabled;
    /** 当前生效版本。新建策略时自动指向 v1，不会为空。 */
    private Long currentVersionId;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
