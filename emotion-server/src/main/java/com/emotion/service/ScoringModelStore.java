package com.emotion.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.ScoringDim;
import com.emotion.entity.ScoringModelConfig;
import com.emotion.entity.ScoringRule;
import com.emotion.entity.ScoringSub;
import com.emotion.mapper.ScoringDimMapper;
import com.emotion.mapper.ScoringModelConfigMapper;
import com.emotion.mapper.ScoringRuleMapper;
import com.emotion.mapper.ScoringSubMapper;
import com.emotion.util.BandRule;
import com.emotion.util.DimNode;
import com.emotion.util.DimWeight;
import com.emotion.util.ScoringModel;
import com.emotion.util.ScoringTree;
import com.emotion.util.SubNode;
import com.emotion.util.TemperatureCalculator;
import com.emotion.vo.ScoringModelVO;

/**
 * 生效打分模型的读取与缓存：把 {@code t_scoring_model}/{@code t_scoring_dim} 装配成
 * 引擎吃的 {@link ScoringModel} 值对象，并给前端/管理页提供 {@link ScoringModelVO} 视图。
 *
 * <p>缓存用两个 volatile 字段（{@code cached} 快照 + {@code initialized} 是否已装载），读走无锁快路径、
 * 装载走双检锁。写侧（{@code ScoringModelService}）在事务提交后调 {@link #evict()} 作废，
 * 下次读再重装配。单用户工具，不做精细失效广播。
 *
 * <p>装不到生效模型、或装载抛异常，一律回退 {@code null}（引擎用内置默认权重）而不是抛：
 * 一次配置读失败退化成"管理员这次改动没生效"，不该让整页复盘打不开。异常时<b>不置 initialized</b>，
 * 下次读重试；正常查完（哪怕没有 active 行）才缓存结果。
 */
@Service
public class ScoringModelStore {

    private static final Logger log = LoggerFactory.getLogger(ScoringModelStore.class);

    private final ScoringModelConfigMapper modelMapper;
    private final ScoringDimMapper dimMapper;
    private final ScoringRuleMapper ruleMapper;
    private final ScoringSubMapper subMapper;

    private volatile ScoringModel cached;
    private volatile boolean initialized;

    /** 五维双层配置树快照（dim→sub→layer→ladder 全树），供 BoardScoreCalculator 消费；与旧 9 维 cached 并行读。 */
    private volatile ScoringTree cachedTree;
    private volatile boolean treeInitialized;

    public ScoringModelStore(ScoringModelConfigMapper modelMapper,
                             ScoringDimMapper dimMapper,
                             ScoringRuleMapper ruleMapper,
                             ScoringSubMapper subMapper) {
        this.modelMapper = modelMapper;
        this.dimMapper = dimMapper;
        this.ruleMapper = ruleMapper;
        this.subMapper = subMapper;
    }

    /**
     * 引擎注入点：返回生效模型的不可变快照，null 表示"库里没有生效模型"（引擎退回内置默认）。
     */
    public ScoringModel activeOrNull() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        cached = loadFromDb();
                        initialized = true;
                    } catch (RuntimeException e) {
                        log.warn("加载生效打分模型失败，本次回退内置默认权重（不缓存，下次重试）：{}", e.toString());
                        return null;
                    }
                }
            }
        }
        return cached;
    }

    /** 写事务提交后调用：一并作废 9 维快照与五维配置树，下次读重新装配。 */
    public void evict() {
        synchronized (this) {
            cached = null;
            initialized = false;
            cachedTree = null;
            treeInitialized = false;
        }
    }

    private ScoringModel loadFromDb() {
        ScoringModelConfig config = selectActive();
        if (config == null) {
            log.info("t_scoring_model 无生效行，引擎使用内置默认权重");
            return null;
        }
        List<ScoringDim> dimRows = listDims(config.getId());
        if (dimRows.isEmpty()) {
            log.warn("生效模型 {} 维度表为空，引擎回退内置默认权重", config.getModelKey());
            return null;
        }
        List<DimWeight> dims = new ArrayList<>(dimRows.size());
        for (ScoringDim row : dimRows) {
            double weight = row.getWeight() == null ? 0d : row.getWeight().doubleValue();
            int dimNo = row.getDimNo() == null ? 0 : row.getDimNo();
            dims.add(new DimWeight(row.getDimKey(), row.getLabel(), weight, dimNo));
        }
        ScoringModel model = new ScoringModel();
        model.setModelKey(config.getModelKey());
        model.setName(config.getName());
        model.setDims(Collections.unmodifiableList(dims));
        model.setMaxScore(config.getMaxScore() == null ? null : config.getMaxScore().doubleValue());
        return model;
    }

    /** 当前生效模型的完整视图（含维度与规则），供前端卡片与管理页读。装不到则 source=BUILTIN、列表为空。 */
    public ScoringModelVO effective() {
        ScoringModelVO vo = new ScoringModelVO();
        try {
            ScoringModelConfig config = selectActive();
            if (config == null) {
                vo.setSource("BUILTIN");
                return vo;
            }
            List<ScoringDim> dims = listDims(config.getId());
            List<ScoringRule> rules = listRules(config.getId());
            List<ScoringSub> subs = listSubs(config.getId());
            vo.setSource("DB");
            vo.setModel(config);
            vo.setDims(dims);
            vo.setSubs(subs);
            vo.setRules(rules);
            BigDecimal weightSum = sumWeight(dims);
            vo.setWeightSum(weightSum);
            vo.setEffectiveMaxScore(effectiveMaxScore(config.getMaxScore(), weightSum));
        } catch (RuntimeException e) {
            log.warn("组装生效打分模型视图失败，前端回退本地常量：{}", e.toString());
            vo.setSource("BUILTIN");
        }
        return vo;
    }

    /** 全平台只应有一行 active=1；多行时取 id 最小那行并打 warn，不靠 DB 随机选。 */
    ScoringModelConfig selectActive() {
        List<ScoringModelConfig> rows = modelMapper.selectList(new LambdaQueryWrapper<ScoringModelConfig>()
                .eq(ScoringModelConfig::getActive, true)
                .orderByAsc(ScoringModelConfig::getId));
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() > 1) {
            log.warn("t_scoring_model 有 {} 行 active=1，按 id 最小取 {}（应只有 1 行生效）",
                    rows.size(), rows.get(0).getModelKey());
        }
        return rows.get(0);
    }

    List<ScoringDim> listDims(Long modelId) {
        return dimMapper.selectList(new LambdaQueryWrapper<ScoringDim>()
                .eq(ScoringDim::getModelId, modelId)
                .orderByAsc(ScoringDim::getDimNo)
                .orderByAsc(ScoringDim::getId));
    }

    /**
     * 五维配置树快照：{@code t_scoring_model} + {@code t_scoring_dim} + {@code t_scoring_sub} +
     * {@code t_scoring_rule} 装配成 {@link ScoringTree}，供 {@code BoardScoreCalculator} 消费。
     *
     * <p>null=没有生效模型 / 维表空 / 装载抛异常——引擎退回 {@code builtinTree()}，与 9 维 {@link #activeOrNull()} 同一降级哲学：
     * 一次配置读失败只是"管理员这次改动没生效"，不该让整页复盘打不开。异常时不置 {@code treeInitialized}，下次读重试。
     */
    public ScoringTree activeTreeOrNull() {
        if (!treeInitialized) {
            synchronized (this) {
                if (!treeInitialized) {
                    try {
                        cachedTree = loadTreeFromDb();
                        treeInitialized = true;
                    } catch (RuntimeException e) {
                        log.warn("加载五维打分配置树失败，本次回退内置默认树（不缓存，下次重试）：{}", e.toString());
                        return null;
                    }
                }
            }
        }
        return cachedTree;
    }

    /** 装配：读 dim→五维权重行 + 全量 sub 行按 parent 组树 + BAND_LADDER sub 挂 rule ladder。 */
    private ScoringTree loadTreeFromDb() {
        ScoringModelConfig config = selectActive();
        if (config == null) {
            log.info("t_scoring_model 无生效行，五维引擎使用内置默认树");
            return null;
        }
        List<ScoringDim> dimRows = listDims(config.getId());
        if (dimRows.isEmpty()) {
            log.warn("生效模型 {} 维表为空，五维引擎回退内置默认树", config.getModelKey());
            return null;
        }
        List<ScoringSub> subRows = listSubs(config.getId());
        if (subRows.isEmpty()) {
            log.warn("生效模型 {} 子指标表为空，五维引擎回退内置默认树", config.getModelKey());
            return null;
        }
        // rule 阶梯按 (dimKey, subKey) 分组；只有 BAND_LADDER sub 会用到，其他 sub 挂了也不读。
        Map<String, List<BandRule>> ladderByKey = new HashMap<String, List<BandRule>>();
        for (ScoringRule row : listRules(config.getId())) {
            if (row.getScore() == null || row.getOperator() == null) {
                continue; // GUARD/AGG/COMPOUND 结构行不出分，引擎不读。
            }
            String key = row.getDimKey() + "::" + row.getSubKey();
            List<BandRule> list = ladderByKey.get(key);
            if (list == null) {
                list = new ArrayList<BandRule>();
                ladderByKey.put(key, list);
            }
            list.add(new BandRule(row.getOperator(), row.getThresholdLow(), row.getThresholdHigh(),
                    BigDecimal.valueOf(row.getScore())));
        }

        // sub 节点索引：key = dimKey::subKey。先建节点、再挂 children、最后组装到 dim。
        Map<String, SubNode> subByKey = new HashMap<String, SubNode>();
        for (ScoringSub s : subRows) {
            SubNode n = new SubNode();
            n.setSubKey(s.getSubKey());
            n.setLabel(s.getLabel());
            n.setWeight(s.getWeight() == null ? 0d : s.getWeight().doubleValue());
            n.setScoringKind(s.getScoringKind());
            n.setSourceKey(s.getSourceKey());
            n.setChildren(new ArrayList<SubNode>());
            n.setLadder(ladderByKey.get(s.getDimKey() + "::" + s.getSubKey()));
            subByKey.put(s.getDimKey() + "::" + s.getSubKey(), n);
        }
        Map<String, List<SubNode>> topByDim = new HashMap<String, List<SubNode>>();
        for (ScoringSub s : subRows) {
            SubNode n = subByKey.get(s.getDimKey() + "::" + s.getSubKey());
            String parent = s.getParentSubKey();
            if (parent == null || "-".equals(parent)) {
                List<SubNode> list = topByDim.get(s.getDimKey());
                if (list == null) {
                    list = new ArrayList<SubNode>();
                    topByDim.put(s.getDimKey(), list);
                }
                list.add(n);
            } else {
                SubNode parent_ = subByKey.get(s.getDimKey() + "::" + parent);
                if (parent_ == null) {
                    log.warn("子指标 {}/{} 声明父 {} 但父行缺失，本条 sub 已丢弃",
                            s.getDimKey(), s.getSubKey(), parent);
                    continue;
                }
                if (parent_.getChildren() == null) {
                    parent_.setChildren(new ArrayList<SubNode>());
                }
                parent_.getChildren().add(n);
            }
        }
        // 排序：ladderByKey 已按 rule_no 装入；children/top 靠 SQL 的 ORDER BY 保序（sort_no→id）。
        List<DimNode> dims = new ArrayList<DimNode>(dimRows.size());
        for (ScoringDim d : dimRows) {
            List<SubNode> subs = topByDim.get(d.getDimKey());
            if (subs == null) {
                subs = Collections.emptyList();
            }
            dims.add(new DimNode(d.getDimKey(), d.getLabel(),
                    d.getWeight() == null ? 0d : d.getWeight().doubleValue(),
                    d.getDimNo() == null ? 0 : d.getDimNo(),
                    d.getRecordColumn(), subs));
        }
        ScoringTree tree = new ScoringTree();
        tree.setModelKey(config.getModelKey());
        tree.setName(config.getName());
        tree.setMaxScore(config.getMaxScore() == null ? 100.0 : config.getMaxScore().doubleValue());
        tree.setDims(Collections.unmodifiableList(dims));
        return tree;
    }

    /** 全量 sub：按 dim_key + parent_sub_key + sort_no + id 排序，让 ladder 顺序与 children 展示顺序稳定。 */
    List<ScoringSub> listSubs(Long modelId) {
        return subMapper.selectList(new LambdaQueryWrapper<ScoringSub>()
                .eq(ScoringSub::getModelId, modelId)
                .orderByAsc(ScoringSub::getDimKey)
                .orderByAsc(ScoringSub::getParentSubKey)
                .orderByAsc(ScoringSub::getSortNo)
                .orderByAsc(ScoringSub::getId));
    }

    List<ScoringRule> listRules(Long modelId) {
        return ruleMapper.selectList(new LambdaQueryWrapper<ScoringRule>()
                .eq(ScoringRule::getModelId, modelId)
                .orderByAsc(ScoringRule::getDimKey)
                .orderByAsc(ScoringRule::getSubKey)
                .orderByAsc(ScoringRule::getRuleNo));
    }

    static BigDecimal sumWeight(List<ScoringDim> dims) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ScoringDim d : dims) {
            if (d.getWeight() != null) {
                sum = sum.add(d.getWeight());
            }
        }
        return sum;
    }

    /** 温度分母：模型写死且 >0 用它，否则=权重和×每维满分(3)；兜到引擎 MAX_POSSIBLE 防 0。 */
    static BigDecimal effectiveMaxScore(BigDecimal writtenMax, BigDecimal weightSum) {
        if (writtenMax != null && writtenMax.signum() > 0) {
            return writtenMax;
        }
        BigDecimal derived = weightSum.multiply(BigDecimal.valueOf(TemperatureCalculator.DIM_MAX));
        if (derived.signum() <= 0) {
            return BigDecimal.valueOf(TemperatureCalculator.MAX_POSSIBLE);
        }
        return derived;
    }
}
