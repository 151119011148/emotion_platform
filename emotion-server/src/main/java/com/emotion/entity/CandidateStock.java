package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.emotion.vo.NodeTagVO;
import com.emotion.vo.ThemeTagVO;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 候选池明细：某个策略某个交易日选出来的票，逐只一行。
 *
 * <p>「按 run 隔离」在 PRD 里是这么写的，但落地时按 {@code (strategy_id, trade_date)} 整日替换：
 * 同一天重跑三次，run 表留三行历史，候选只保留最新一次的结果（AC-9 要求候选行数不变）。
 * 用户要看的是「那天的候选是谁」，不是「历次运行分别算出谁」——后者会让界面出现三份互相打架的清单。
 *
 * <p>{@code rank_no} 是按<strong>可执行性</strong>排的，不是按分数。理由见
 * {@code WaveRiderEngine}：在「隔夜跳空 ≤ 门槛」这个可执行子样本里，连板数、成交额、
 * 首封时间的排序力都不成立，所以这里不做因子加权排序。
 */
@Data
@TableName("t_candidate_stock")
public class CandidateStock {

    public static final String PRINCIPLE_POSITION = "POSITION";
    public static final String PRINCIPLE_NODE = "NODE";

    /** 一字缩量：封单占成交额过高，次日大概率一字开盘买不进。 */
    public static final String RISK_YIZI_THIN = "YIZI_THIN";
    /** 高位过度换手。 */
    public static final String RISK_HIGH_TURNOVER = "HIGH_TURNOVER";
    /** 龙头断板。 */
    public static final String RISK_DRAGON_DEAD = "DRAGON_DEAD";

    /** 一字断魂刀：执行预警，不是风险扣分——见 {@link #alertFlag}。 */
    public static final String ALERT_DUANDAO = "DUANDAO";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long strategyId;
    /** 候选产生日 D。注意这天它是涨停的，收盘价买不到——收益起算日永远是 D+1。 */
    private LocalDate tradeDate;
    private Integer rankNo;
    private String code;
    private String name;
    /** D 日连板数。 */
    private Integer board;
    /** 主归属题材。 */
    private String topic;
    /** 全部命中题材（多归属），JSON 数组。 */
    private String conceptsJson;
    /** POSITION / NODE，可同时命中，JSON 数组。 */
    private String hitPrinciplesJson;
    private String nodeType;
    private LocalDate nodeDate;
    /** 身位描述，如「出版最高板」。 */
    private String positionType;
    private BigDecimal score;
    /** 建议仓位（小数）。单票上限由配置的 max_single_position 决定。 */
    private BigDecimal suggestPosition;
    private String riskFlag;
    /** 过滤器逐条判定明细，界面「点开追溯」看到的就是它。 */
    private String filterDetailJson;
    private LocalDateTime createdAt;

    /**
     * 来自「节点追踪」的标记（<strong>瞬态，不落库</strong>）：这只票在 {@code t_node_event} 里是什么角色。
     *
     * <p>与上面的身位无关——这里不看连板数，只看它有没有出现在某条节点事件里。
     * 一只票可能命中多条事件，也可能同时是甲事件的节点票、乙事件的 D0 候选，所以是列表。
     * 由 {@code NodeService.tagCandidates} 在读侧填充。
     */
    @TableField(exist = false)
    private List<NodeTagVO> nodeTags;

    /**
     * 所属的「通达信题材」（<strong>瞬态，不落库</strong>），按当日该题材的涨停家数降序。
     *
     * <p>与 {@code topic} 不是一个东西：{@code topic} 是引擎分组的行业（东财口径、截断 4 字），
     * 这里是给人看的题材标签（{@code t_stock_concept}，通达信概念板块）。
     * 一只票常在 5~11 个题材里，由 {@code TopicHeatService.tagTdxThemes} 按热度排好序。
     */
    @TableField(exist = false)
    private List<ThemeTagVO> tdxThemes;

    /**
     * 执行预警（<strong>瞬态，不落库</strong>）：这只票大概率<b>买不进</b>，而不是「不该选」。
     *
     * <p>与 {@link #riskFlag} 分开是刻意的：{@code riskFlag} 说「这只票质地有风险」，
     * 并参与仓位折算（引擎 {@code build()} 里带 riskFlag 的仓位折半）；这里只说执行难度，
     * <b>不扣分、不折仓、不参与排序</b>。封单锁死恰恰是本策略最强的正向因子——封成比五档
     * 打板收益 +1.71 → +7.56%——把它当风险去降权就南辕北辙了。
     *
     * <p>当前唯一取值 {@link #ALERT_DUANDAO}，由 {@code TiantiService.duanDaoCodes} 在读侧算。
     * 不落库的理由同 {@code nodeTags}：它依赖「今日 + 昨日」两天池子，明天再看今天的候选仍算得出，
     * 落库反而多一份可能与判据漂移的旧快照。
     */
    @TableField(exist = false)
    private String alertFlag;
}
