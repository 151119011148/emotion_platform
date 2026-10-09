package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 连板天梯某日人工指定的「总龙头」。
 *
 * <p>全市场最高连板由引擎判（见 {@code TiantiService#roleOf}），「总龙头」这个身份是人工判断。
 * 业务数据全平台共享后，一天只该有一个总龙头，所以库里的主键就是 {@code trade_date}。
 *
 * <p>这里刻意不挂 {@code @TableId}：主键是 DATE，MyBatis-Plus 的默认主键策略会去发一个雪花 id，
 * 挂上去反而要先关掉它。增删改一律由 {@code ManualLeaderService} 用 {@code trade_date} 条件走，
 * 不调 {@code selectById}/{@code updateById}——上一版把 {@code @TableId} 挂在 userId 上时，
 * {@code updateById} 实际是 {@code WHERE user_id=?}，会把该账号所有交易日一起刷。
 */
@Data
@TableName("t_manual_leader")
public class ManualLeader {

    private LocalDate tradeDate;
    private String code;
    private String name;
    private LocalDateTime updatedAt;
}
