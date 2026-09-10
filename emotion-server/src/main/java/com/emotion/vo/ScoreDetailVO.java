package com.emotion.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.emotion.util.BoardScoreCalculator;
import lombok.Data;

/**
 * 每日分数细粒度快照（Stage 9 只读端点 {@code GET /api/records/score-detail} 的返回体）。
 *
 * <p>不落库、不改 {@code t_daily_record}：走 {@code ScoreContextService.forDate} 现装 metrics + tree，
 * 交给 {@link BoardScoreCalculator#evaluate} 得到整棵 eval 树。改一维权重或改一阶梯阈值 → 刷新卡片立刻反映，
 * 无需 recalc-all。Dashboard 卡片 tooltip 与复盘页五维条都从这一份读。
 */
@Data
public class ScoreDetailVO {

    /** 请求的交易日。 */
    private LocalDate tradeDate;
    /** 生效模型标识（five_dim / ultra_short）；缺树时 "BUILTIN"。 */
    private String modelKey;
    /** 5 维的完整 eval 树：直属 subs + 每复合子的层。未评的子 {@code score=null}。 */
    private List<BoardScoreCalculator.NodeEval> dims = new ArrayList<>();
    /** 0-100 总分；全维未评时 null。 */
    private BigDecimal total;
    /** 5 条结构信号命中项（未命中即空数组）。 */
    private List<String> signalFlags = new ArrayList<>();
    private Boolean forcedEbb;
    private String forcedEbbReason;
    /** 与 ScoringModelVO.source 对齐："DB" 或 "BUILTIN"。 */
    private String source;
    /** 原始读数快照：给 tooltip 兜底显示；键=source_key。 */
    private Map<String, BigDecimal> metrics = new LinkedHashMap<>();
    /** 未评原因（"turnover_ratio 未取到：量能子未评"）、跳过的策略、缺表提示等，一条一行。 */
    private List<String> notes = new ArrayList<>();
}
