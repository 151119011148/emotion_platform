package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 策略配置版本快照，append-only：只插入、不更新、不删除。
 *
 * <p>改配置 = 写一个新版本号，历史版本永远只读。{@link StrategyRun#getVersionId()} 落的是
 * 「这次运行用的哪个版本」，两者合起来才能回答「当时那批候选是怎么算出来的」。
 * 这也是为什么回滚不是「把当前版本改回旧的」，而是「用旧版本的内容创建一个新版本」——
 * 版本号只增不减，序列本身就是审计日志。
 */
@Data
@TableName("t_strategy_version")
public class StrategyVersion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long strategyId;
    /** 同一策略内从 1 递增。 */
    private Integer versionNo;
    /** 完整配置快照。本引擎所有可调项都在这里面，没有散落在别处的参数。 */
    private String configJson;
    /** 配置的 MD5。内容与当前版本一致时直接复用版本号，不制造空版本。 */
    private String configHash;
    private String changeNote;
    private Long createdBy;
    private LocalDateTime createdAt;
}
