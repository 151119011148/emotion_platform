package com.emotion.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.emotion.entity.ScoringDim;
import com.emotion.entity.ScoringModelConfig;
import com.emotion.entity.ScoringRule;
import com.emotion.entity.ScoringSub;
import com.emotion.mapper.ScoringDimMapper;
import com.emotion.mapper.ScoringModelConfigMapper;
import com.emotion.mapper.ScoringRuleMapper;
import com.emotion.mapper.ScoringSubMapper;
import com.emotion.util.BoardScoreCalculator;
import com.emotion.util.DimNode;
import com.emotion.util.DimWeight;
import com.emotion.util.TemperatureCalculator;
import com.emotion.vo.ScoringModelVO;

/**
 * 打分配置（模型 / 维度 / 规则）的增删改查与字段校验。
 *
 * <p>写侧唯一职责是"把配置存住并校验"，不碰行情、不打网络。维集合与权重一旦改动会影响打分，
 * 因此每次提交后 {@link ScoringModelStore#evict()} 作废缓存（注册为事务 afterCommit，避免提交前
 * 被并发读重载成旧值）；规则表虽不被引擎读取，但出现在 {@code /effective} 视图里，同样 evict。
 *
 * <p>三张表全平台共享、不绑用户，故无 {@code user_id} 过滤。已知缺口：任何登录用户都能改全局配置
 * （单用户工具，暂不建角色系统，仅在控制器记一条审计日志）。
 */
@Service
public class ScoringModelService {

    private static final Logger log = LoggerFactory.getLogger(ScoringModelService.class);

    /**
     * 允许登记的 dim_key：9 维 (height/premium/…) ∪ 五维 (market/theme_main/board/first/anchor)。
     * 迁移期两套种子并存（旧 ultra_short 行仍在但 active=0），管理页要能读旧行、也要能改新行，
     * 因此这里取并集而不是替换。真源=builtinModel()+BoardScoreCalculator.builtinTree()。
     */
    private static final Set<String> VALID_DIM_KEYS = validDimKeys();

    private static Set<String> validDimKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (DimWeight d : TemperatureCalculator.builtinModel().getDims()) {
            keys.add(d.getDimKey());
        }
        for (DimNode d : BoardScoreCalculator.builtinTree().getDims()) {
            keys.add(d.getDimKey());
        }
        return Collections.unmodifiableSet(keys);
    }

    /** scoring_kind 允许集合（对齐 t_scoring_sub.scoring_kind 列注释）。 */
    private static final Set<String> VALID_SCORING_KINDS = unmodifiableSetOf(
            "WEIGHTED_SUM", "BAND_LADDER", "LAYER_WEIGHTED_BAND", "STRATEGY", "MANUAL");

    private static Set<String> unmodifiableSetOf(String... vs) {
        Set<String> s = new LinkedHashSet<>();
        for (String v : vs) { s.add(v); }
        return Collections.unmodifiableSet(s);
    }

    private static final List<String> RULE_ENGINES = Collections.unmodifiableList(Arrays.asList(
            "THRESHOLD_BAND", "WEIGHTED_SUB_BANDS", "SUBITEM_AVERAGE", "WORST_OF_MANY", "MANUAL_PASSTHROUGH",
            "WEIGHTED_SUM"));

    private final ScoringModelConfigMapper modelMapper;
    private final ScoringDimMapper dimMapper;
    private final ScoringRuleMapper ruleMapper;
    private final ScoringSubMapper subMapper;
    private final ScoringModelStore store;

    public ScoringModelService(ScoringModelConfigMapper modelMapper,
                               ScoringDimMapper dimMapper,
                               ScoringRuleMapper ruleMapper,
                               ScoringSubMapper subMapper,
                               ScoringModelStore store) {
        this.modelMapper = modelMapper;
        this.dimMapper = dimMapper;
        this.ruleMapper = ruleMapper;
        this.subMapper = subMapper;
        this.store = store;
    }

    // ---- 读 ----

    public List<ScoringModelConfig> listModels() {
        return modelMapper.selectList(new LambdaQueryWrapper<ScoringModelConfig>()
                .orderByAsc(ScoringModelConfig::getId));
    }

    /** 单个模型的完整视图（模型 + 维度 + 规则 + 派生量），管理页选中模型后拉这一份。 */
    public ScoringModelVO modelDetail(Long id) {
        ScoringModelConfig model = requireModel(id);
        List<ScoringDim> dims = store.listDims(id);
        List<ScoringRule> rules = store.listRules(id);
        List<ScoringSub> subs = store.listSubs(id);
        ScoringModelVO vo = new ScoringModelVO();
        vo.setSource("DB");
        vo.setModel(model);
        vo.setDims(dims);
        vo.setSubs(subs);
        vo.setRules(rules);
        BigDecimal weightSum = ScoringModelStore.sumWeight(dims);
        vo.setWeightSum(weightSum);
        vo.setEffectiveMaxScore(ScoringModelStore.effectiveMaxScore(model.getMaxScore(), weightSum));
        return vo;
    }

    // ---- 模型 ----

    @Transactional(rollbackFor = Exception.class)
    public ScoringModelConfig createModel(ScoringModelConfig model) {
        model.setId(null);
        validateModel(model);
        modelMapper.insert(model);
        evictAfterCommit();
        return model;
    }

    @Transactional(rollbackFor = Exception.class)
    public ScoringModelConfig updateModel(Long id, ScoringModelConfig patch) {
        ScoringModelConfig target = requireModel(id);
        patch.setId(target.getId());
        patch.setCreatedAt(target.getCreatedAt());
        patch.setUpdatedAt(target.getUpdatedAt());
        // 编辑表单不碰 active（激活走 /activate 端点）；不带上就会 null，validateModel 会把 null 兜成 false，
        // 于是每次 PUT 都把当前生效模型悄悄停用。这里显式沿用库里那份，除非请求方真的传了 active。
        if (patch.getActive() == null) {
            patch.setActive(target.getActive());
        }
        validateModel(patch);
        modelMapper.updateById(patch);
        evictAfterCommit();
        return patch;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(Long id) {
        requireModel(id);
        dimMapper.delete(new LambdaQueryWrapper<ScoringDim>().eq(ScoringDim::getModelId, id));
        subMapper.delete(new LambdaQueryWrapper<ScoringSub>().eq(ScoringSub::getModelId, id));
        ruleMapper.delete(new LambdaQueryWrapper<ScoringRule>().eq(ScoringRule::getModelId, id));
        modelMapper.deleteById(id);
        evictAfterCommit();
    }

    /** 设为唯一生效模型：本行 active=1，其余全部 active=0。 */
    @Transactional(rollbackFor = Exception.class)
    public ScoringModelConfig activate(Long id) {
        ScoringModelConfig target = requireModel(id);
        // 用列级 set 而不是整实体 update：max_score/note 是 ALWAYS 列，实体方式会把它们写成 NULL。
        modelMapper.update(null, new LambdaUpdateWrapper<ScoringModelConfig>()
                .set(ScoringModelConfig::getActive, false)
                .eq(ScoringModelConfig::getActive, true));
        target.setActive(true);
        modelMapper.updateById(target);
        evictAfterCommit();
        return target;
    }

    // ---- 维度 ----

    @Transactional(rollbackFor = Exception.class)
    public ScoringDim createDim(ScoringDim dim) {
        dim.setId(null);
        validateDim(dim, true);
        dimMapper.insert(dim);
        evictAfterCommit();
        return dim;
    }

    @Transactional(rollbackFor = Exception.class)
    public ScoringDim updateDim(Long id, ScoringDim patch) {
        ScoringDim target = requireDim(id);
        patch.setId(target.getId());
        patch.setModelId(target.getModelId());
        patch.setCreatedAt(target.getCreatedAt());
        patch.setUpdatedAt(target.getUpdatedAt());
        validateDim(patch, false);
        dimMapper.updateById(patch);
        evictAfterCommit();
        return patch;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDim(Long id) {
        ScoringDim target = requireDim(id);
        dimMapper.deleteById(id);
        // 维度删掉后其规则成孤儿（按 dim_key 挂），一并清掉避免管理页显示幽灵规则。
        ruleMapper.delete(new LambdaQueryWrapper<ScoringRule>()
                .eq(ScoringRule::getModelId, target.getModelId())
                .eq(ScoringRule::getDimKey, target.getDimKey()));
        evictAfterCommit();
    }

    // ---- 规则 ----

    @Transactional(rollbackFor = Exception.class)
    public ScoringRule createRule(ScoringRule rule) {
        rule.setId(null);
        validateRule(rule, true);
        ruleMapper.insert(rule);
        evictAfterCommit();
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    public ScoringRule updateRule(Long id, ScoringRule patch) {
        ScoringRule target = requireRule(id);
        patch.setId(target.getId());
        patch.setModelId(target.getModelId());
        patch.setCreatedAt(target.getCreatedAt());
        patch.setUpdatedAt(target.getUpdatedAt());
        validateRule(patch, false);
        ruleMapper.updateById(patch);
        evictAfterCommit();
        return patch;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteRule(Long id) {
        requireRule(id);
        ruleMapper.deleteById(id);
        evictAfterCommit();
    }

    // ---- 子指标 / 四层（t_scoring_sub，五维双层模型）----

    @Transactional(rollbackFor = Exception.class)
    public ScoringSub createSub(ScoringSub sub) {
        sub.setId(null);
        validateSub(sub, true);
        subMapper.insert(sub);
        evictAfterCommit();
        return sub;
    }

    @Transactional(rollbackFor = Exception.class)
    public ScoringSub updateSub(Long id, ScoringSub patch) {
        ScoringSub target = requireSub(id);
        patch.setId(target.getId());
        patch.setModelId(target.getModelId());
        patch.setDimKey(target.getDimKey());
        patch.setCreatedAt(target.getCreatedAt());
        patch.setUpdatedAt(target.getUpdatedAt());
        validateSub(patch, false);
        subMapper.updateById(patch);
        evictAfterCommit();
        return patch;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteSub(Long id) {
        ScoringSub target = requireSub(id);
        subMapper.deleteById(id);
        // 级联：挂在它下面的孙层/叶 + 它的 ladder 规则行都清掉，不留孤儿。
        subMapper.delete(new LambdaQueryWrapper<ScoringSub>()
                .eq(ScoringSub::getModelId, target.getModelId())
                .eq(ScoringSub::getDimKey, target.getDimKey())
                .eq(ScoringSub::getParentSubKey, target.getSubKey()));
        ruleMapper.delete(new LambdaQueryWrapper<ScoringRule>()
                .eq(ScoringRule::getModelId, target.getModelId())
                .eq(ScoringRule::getDimKey, target.getDimKey())
                .eq(ScoringRule::getSubKey, target.getSubKey()));
        evictAfterCommit();
    }

    // ---- 校验 ----

    private void validateModel(ScoringModelConfig m) {
        if (isBlank(m.getModelKey())) {
            throw new IllegalArgumentException("模型标识 model_key 必填（幂等种子与代码按它认行，如 ultra_short）");
        }
        m.setModelKey(m.getModelKey().trim());
        if (isBlank(m.getName())) {
            throw new IllegalArgumentException("模型名称必填");
        }
        if (m.getMaxScore() != null && m.getMaxScore().signum() <= 0) {
            throw new IllegalArgumentException("温度分母 max_score 要么留空(按权重和现推)，要么为正数，收到：" + m.getMaxScore());
        }
        if (m.getActive() == null) {
            m.setActive(false);
        }
        Long dup = modelMapper.selectCount(new LambdaQueryWrapper<ScoringModelConfig>()
                .eq(ScoringModelConfig::getModelKey, m.getModelKey())
                .ne(m.getId() != null, ScoringModelConfig::getId, m.getId()));
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("模型标识已存在：" + m.getModelKey());
        }
    }

    private void validateDim(ScoringDim d, boolean creating) {
        if (d.getModelId() == null || modelMapper.selectById(d.getModelId()) == null) {
            throw new IllegalArgumentException("维度必须挂在一个已存在的模型上（model_id 无效）");
        }
        if (isBlank(d.getDimKey())) {
            throw new IllegalArgumentException("维度键 dim_key 必填");
        }
        d.setDimKey(d.getDimKey().trim());
        if (!VALID_DIM_KEYS.contains(d.getDimKey())) {
            throw new IllegalArgumentException("未知的维度键：" + d.getDimKey() + "，只能是 " + VALID_DIM_KEYS);
        }
        if (d.getDimNo() == null || d.getDimNo() < 1) {
            throw new IllegalArgumentException("引擎维序 dim_no 必须是 >=1 的整数（五维 1..5、旧九维 1..9）");
        }
        if (isBlank(d.getLabel())) {
            throw new IllegalArgumentException("卡面名称 label 必填");
        }
        if (d.getWeight() == null || d.getWeight().signum() < 0) {
            throw new IllegalArgumentException("权重 weight 必填且不能为负");
        }
        if (isBlank(d.getRuleEngine())) {
            d.setRuleEngine("THRESHOLD_BAND");
        }
        if (!RULE_ENGINES.contains(d.getRuleEngine())) {
            throw new IllegalArgumentException("未知的合成方式 rule_engine：" + d.getRuleEngine() + "，只能是 " + RULE_ENGINES);
        }
        Long dup = dimMapper.selectCount(new LambdaQueryWrapper<ScoringDim>()
                .eq(ScoringDim::getModelId, d.getModelId())
                .eq(ScoringDim::getDimKey, d.getDimKey())
                .ne(!creating, ScoringDim::getId, d.getId()));
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("该模型下维度键重复：" + d.getDimKey());
        }
    }

    private void validateRule(ScoringRule r, boolean creating) {
        if (r.getModelId() == null || modelMapper.selectById(r.getModelId()) == null) {
            throw new IllegalArgumentException("规则必须挂在一个已存在的模型上（model_id 无效）");
        }
        if (isBlank(r.getDimKey()) || !VALID_DIM_KEYS.contains(r.getDimKey().trim())) {
            throw new IllegalArgumentException("规则所属维度键无效：" + r.getDimKey() + "，只能是 " + VALID_DIM_KEYS);
        }
        r.setDimKey(r.getDimKey().trim());
        if (isBlank(r.getSubKey())) {
            r.setSubKey("-");
        }
        if (r.getRuleNo() == null || r.getRuleNo() < 1) {
            throw new IllegalArgumentException("命中顺序 rule_no 必须是 >=1 的整数");
        }
        if (isBlank(r.getOperator())) {
            throw new IllegalArgumentException("算子 operator 必填");
        }
        // 迁移期两种口径并存：9 维阶梯 -3~3，五维阶梯 0~100。放宽到 -3~100 覆盖两派。
        if (r.getScore() != null && (r.getScore() < -3 || r.getScore() > 100)) {
            throw new IllegalArgumentException("命中给分 score 必须在 -3~100（旧 9 维 -3~3、五维 0~100），收到：" + r.getScore());
        }
        if (r.getThresholdLow() != null && r.getThresholdHigh() != null
                && r.getThresholdLow().compareTo(r.getThresholdHigh()) > 0) {
            throw new IllegalArgumentException("阈值下界不能大于上界：" + r.getThresholdLow() + " > " + r.getThresholdHigh());
        }
        Long dup = ruleMapper.selectCount(new LambdaQueryWrapper<ScoringRule>()
                .eq(ScoringRule::getModelId, r.getModelId())
                .eq(ScoringRule::getDimKey, r.getDimKey())
                .eq(ScoringRule::getSubKey, r.getSubKey())
                .eq(ScoringRule::getRuleNo, r.getRuleNo())
                .ne(!creating, ScoringRule::getId, r.getId()));
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("同维同子档下 rule_no 重复：" + r.getDimKey() + "/" + r.getSubKey() + "#" + r.getRuleNo());
        }
    }

    // ---- 取回 / 工具 ----

    private ScoringModelConfig requireModel(Long id) {
        ScoringModelConfig row = modelMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("打分模型不存在：" + id);
        }
        return row;
    }

    private ScoringDim requireDim(Long id) {
        ScoringDim row = dimMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("打分维度不存在：" + id);
        }
        return row;
    }

    private ScoringRule requireRule(Long id) {
        ScoringRule row = ruleMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("打分规则不存在：" + id);
        }
        return row;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void validateSub(ScoringSub sub, boolean creating) {
        if (sub.getModelId() == null || modelMapper.selectById(sub.getModelId()) == null) {
            throw new IllegalArgumentException("子指标必须挂在已存在的模型上（model_id 无效）");
        }
        if (isBlank(sub.getDimKey()) || !VALID_DIM_KEYS.contains(sub.getDimKey().trim())) {
            throw new IllegalArgumentException("子指标所属 dim_key 无效：" + sub.getDimKey());
        }
        sub.setDimKey(sub.getDimKey().trim());
        if (isBlank(sub.getSubKey())) {
            throw new IllegalArgumentException("子指标键 sub_key 必填");
        }
        sub.setSubKey(sub.getSubKey().trim());
        if (sub.getParentSubKey() == null) {
            sub.setParentSubKey("-");
        }
        if (isBlank(sub.getLabel())) {
            throw new IllegalArgumentException("卡面名称 label 必填");
        }
        if (sub.getWeight() == null || sub.getWeight().signum() < 0) {
            throw new IllegalArgumentException("权重 weight 必填且不能为负（0-1 小数）");
        }
        if (isBlank(sub.getScoringKind()) || !VALID_SCORING_KINDS.contains(sub.getScoringKind())) {
            throw new IllegalArgumentException("未知的 scoring_kind：" + sub.getScoringKind() + "，只能是 " + VALID_SCORING_KINDS);
        }
        // 复合类（WEIGHTED_SUM/LAYER_WEIGHTED_BAND）不读 source_key；叶子类必须有。
        String kind = sub.getScoringKind();
        boolean leaf = "BAND_LADDER".equals(kind) || "STRATEGY".equals(kind) || "MANUAL".equals(kind);
        if (leaf && isBlank(sub.getSourceKey())) {
            throw new IllegalArgumentException(kind + " 必须写 source_key（引擎按它去 metrics 取读数或调策略）");
        }
        Long dup = subMapper.selectCount(new LambdaQueryWrapper<ScoringSub>()
                .eq(ScoringSub::getModelId, sub.getModelId())
                .eq(ScoringSub::getDimKey, sub.getDimKey())
                .eq(ScoringSub::getSubKey, sub.getSubKey())
                .ne(!creating, ScoringSub::getId, sub.getId()));
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("同模型同维下 sub_key 重复：" + sub.getDimKey() + "/" + sub.getSubKey());
        }
    }

    private ScoringSub requireSub(Long id) {
        ScoringSub row = subMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("打分子指标不存在：" + id);
        }
        return row;
    }

    /** 事务提交后才 evict：提交前 evict 会被并发读用旧数据重新填满缓存。 */
    private void evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    store.evict();
                }
            });
        } else {
            store.evict();
        }
    }
}
