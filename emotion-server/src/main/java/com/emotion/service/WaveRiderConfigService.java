package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.Strategy;
import com.emotion.entity.StrategyRun;
import com.emotion.entity.StrategyTemplate;
import com.emotion.entity.StrategyVersion;
import com.emotion.mapper.StrategyMapper;
import com.emotion.mapper.StrategyRunMapper;
import com.emotion.mapper.StrategyTemplateMapper;
import com.emotion.mapper.StrategyVersionMapper;
import com.emotion.waverider.WaveRiderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略配置的读写：策略实体、版本快照、模板套用。
 *
 * <p>三条不变式，改这个类之前先确认不会破坏它们：
 * <ol>
 *   <li><strong>版本只增不改</strong>。任何配置变更都写成新版本；「回滚」也不是把指针拨回去，
 *       而是用旧版本的配置内容创建一个新版本。这样版本号序列本身就是一份改配置的审计日志。</li>
 *   <li><strong>内容没变就不产生版本</strong>。保存时先规范化（反序列化再序列化）再算 MD5，
 *       所以键顺序不同、格式不同但语义相同的配置不会把版本号刷成噪音。</li>
 *   <li><strong>策略永远有一个 current 版本</strong>。新建时立刻落 v1，不存在「建了策略还没配置」的中间态。</li>
 * </ol>
 */
@Service
public class WaveRiderConfigService {

    private final StrategyMapper strategyMapper;
    private final StrategyVersionMapper versionMapper;
    private final StrategyTemplateMapper templateMapper;
    private final StrategyRunMapper runMapper;
    private final ObjectMapper objectMapper;

    public WaveRiderConfigService(StrategyMapper strategyMapper,
                                  StrategyVersionMapper versionMapper,
                                  StrategyTemplateMapper templateMapper,
                                  StrategyRunMapper runMapper,
                                  ObjectMapper objectMapper) {
        this.strategyMapper = strategyMapper;
        this.versionMapper = versionMapper;
        this.templateMapper = templateMapper;
        this.runMapper = runMapper;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 策略

    /** 某个账号的全部策略，附带当前版本号与最近一次运行情况（界面上那一列「上次跑成什么样」）。 */
    public List<Map<String, Object>> listStrategies(Long userId) {
        List<Strategy> rows = strategyMapper.selectList(new LambdaQueryWrapper<Strategy>()
                .eq(Strategy::getUserId, userId)
                .orderByDesc(Strategy::getUpdatedAt));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Strategy s : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getName());
            m.put("enabled", s.getEnabled());
            m.put("description", s.getDescription());
            m.put("currentVersionId", s.getCurrentVersionId());
            m.put("currentVersionNo", versionNo(s.getCurrentVersionId()));
            m.put("updatedAt", s.getUpdatedAt());
            StrategyRun last = lastRun(s.getId());
            if (last != null) {
                m.put("lastRunDate", last.getTradeDate());
                m.put("lastRunStatus", last.getStatus());
                m.put("lastRunCandidates", last.getCandidateCount());
                m.put("lastRunWarning", last.getWarning());
            }
            out.add(m);
        }
        return out;
    }

    /** 新建策略。默认参数取「震荡市」模板，避免新策略一上来就是空配置。 */
    @Transactional
    public Strategy create(Long userId, String name, String description, String templateCode) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("策略名称不能为空");
        }
        Long dup = strategyMapper.selectCount(new LambdaQueryWrapper<Strategy>()
                .eq(Strategy::getUserId, userId).eq(Strategy::getName, trimmed));
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("已经有同名策略：" + trimmed);
        }

        Strategy s = new Strategy();
        s.setUserId(userId);
        s.setName(trimmed);
        s.setEnabled(1);
        s.setDescription(description);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        strategyMapper.insert(s);

        WaveRiderConfig cfg = configOfTemplate(templateCode);
        StrategyVersion v = insertVersion(s.getId(), cfg, "初始版本", userId);
        s.setCurrentVersionId(v.getId());
        strategyMapper.updateById(s);
        return s;
    }

    public Strategy requireStrategy(Long id) {
        Strategy s = strategyMapper.selectById(id);
        if (s == null) {
            throw new IllegalArgumentException("没有这个策略：" + id);
        }
        return s;
    }

    /** 改启用状态。停用只影响定时任务，手工跑不受影响（界面上的「立即运行」仍可用）。 */
    public Strategy setEnabled(Long id, boolean enabled) {
        Strategy s = requireStrategy(id);
        s.setEnabled(enabled ? 1 : 0);
        s.setUpdatedAt(LocalDateTime.now());
        strategyMapper.updateById(s);
        return s;
    }

    // ------------------------------------------------------------------ 版本

    public WaveRiderConfig currentConfig(Long strategyId) {
        Strategy s = requireStrategy(strategyId);
        return configOfVersion(s.getCurrentVersionId());
    }

    public WaveRiderConfig configOfVersion(Long versionId) {
        if (versionId == null) {
            throw new IllegalArgumentException("该策略还没有配置版本");
        }
        StrategyVersion v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new IllegalArgumentException("没有这个版本：" + versionId);
        }
        return parse(v.getConfigJson());
    }

    /**
     * 保存为新版本。内容与当前版本相同则直接返回当前版本，不新增行。
     *
     * @param configJson 前端传来的配置 JSON（键名 snake_case）
     * @param changeNote 这次改了什么，必填——没有说明的版本最后会变成没人敢动的黑盒
     */
    @Transactional
    public StrategyVersion saveVersion(Long strategyId, String configJson, String changeNote, Long userId) {
        Strategy s = requireStrategy(strategyId);
        WaveRiderConfig cfg = parse(configJson);
        List<String> errs = cfg.validate();
        if (!errs.isEmpty()) {
            throw new IllegalArgumentException("配置不合法：" + String.join("；", errs));
        }

        String canonical = serialize(cfg);
        String hash = md5(canonical);

        StrategyVersion current = s.getCurrentVersionId() == null ? null : versionMapper.selectById(s.getCurrentVersionId());
        if (current != null && hash.equals(current.getConfigHash())) {
            return current;
        }

        StrategyVersion v = insertVersion(strategyId, canonical, changeNote, userId);
        s.setCurrentVersionId(v.getId());
        s.setUpdatedAt(LocalDateTime.now());
        strategyMapper.updateById(s);
        return v;
    }

    public List<StrategyVersion> listVersions(Long strategyId) {
        return versionMapper.selectList(new LambdaQueryWrapper<StrategyVersion>()
                .eq(StrategyVersion::getStrategyId, strategyId)
                .orderByDesc(StrategyVersion::getVersionNo));
    }

    /**
     * 回滚 = 用目标版本的配置内容创建一个新版本，并把 current 指过去。
     *
     * <p>不是把指针拨回旧版本：那样会让「v3 到 v5 之间到底发生过什么」变得不可解释。
     * 版本号只增不减，历史永远读得出来。
     */
    @Transactional
    public StrategyVersion rollback(Long strategyId, Long versionId, Long userId) {
        Strategy s = requireStrategy(strategyId);
        StrategyVersion target = versionMapper.selectById(versionId);
        if (target == null || !target.getStrategyId().equals(strategyId)) {
            throw new IllegalArgumentException("版本 " + versionId + " 不属于策略 " + strategyId);
        }
        StrategyVersion v = insertVersion(strategyId, target.getConfigJson(),
                "回滚到 v" + target.getVersionNo(), userId);
        s.setCurrentVersionId(v.getId());
        s.setUpdatedAt(LocalDateTime.now());
        strategyMapper.updateById(s);
        return v;
    }

    /** 两个版本的 key 级差异，结果只含「变了什么」，不含未动的键。 */
    public Map<String, Object> diff(Long v1, Long v2) {
        StrategyVersion a = versionMapper.selectById(v1);
        StrategyVersion b = versionMapper.selectById(v2);
        if (a == null || b == null) {
            throw new IllegalArgumentException("版本不存在：" + v1 + " / " + v2);
        }
        Map<String, Object> flatA = flatten(a.getConfigJson());
        Map<String, Object> flatB = flatten(b.getConfigJson());

        List<Map<String, Object>> changes = new ArrayList<>();
        for (String key : unionKeys(flatA, flatB)) {
            Object x = flatA.get(key);
            Object y = flatB.get(key);
            if (x == null ? y != null : !x.equals(y)) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("key", key);
                c.put("from", x);
                c.put("to", y);
                changes.add(c);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v1", a.getVersionNo());
        out.put("v2", b.getVersionNo());
        out.put("changes", changes);
        out.put("same", changes.isEmpty());
        return out;
    }

    // ------------------------------------------------------------------ 模板

    public List<StrategyTemplate> listTemplates() {
        return templateMapper.selectList(new LambdaQueryWrapper<StrategyTemplate>()
                .orderByAsc(StrategyTemplate::getSortNo));
    }

    /** 套用模板 = 把模板参数写成一个新版本，不产生新策略实体。 */
    @Transactional
    public StrategyVersion applyTemplate(Long strategyId, String templateCode, Long userId) {
        requireStrategy(strategyId);
        return saveVersion(strategyId, serialize(configOfTemplate(templateCode)),
                "套用模板 " + templateCode, userId);
    }

    private WaveRiderConfig configOfTemplate(String templateCode) {
        String code = templateCode == null || templateCode.trim().isEmpty()
                ? StrategyTemplate.CODE_RANGE : templateCode.trim();
        StrategyTemplate t = templateMapper.selectOne(new LambdaQueryWrapper<StrategyTemplate>()
                .eq(StrategyTemplate::getTemplateCode, code).last("LIMIT 1"));
        if (t == null) {
            // 模板没种上（旧库）时不要抛异常拦住建策略：退回到代码里的默认值。
            return new WaveRiderConfig();
        }
        return parse(t.getConfigJson());
    }

    // ------------------------------------------------------------------ 内部

    private StrategyVersion insertVersion(Long strategyId, WaveRiderConfig cfg, String note, Long userId) {
        return insertVersion(strategyId, serialize(cfg), note, userId);
    }

    private StrategyVersion insertVersion(Long strategyId, String canonicalJson, String note, Long userId) {
        Integer maxNo = maxVersionNo(strategyId);
        StrategyVersion v = new StrategyVersion();
        v.setStrategyId(strategyId);
        v.setVersionNo(maxNo + 1);
        v.setConfigJson(canonicalJson);
        v.setConfigHash(md5(canonicalJson));
        v.setChangeNote(note);
        v.setCreatedBy(userId);
        v.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(v);
        return v;
    }

    private Integer maxVersionNo(Long strategyId) {
        StrategyVersion top = versionMapper.selectOne(new LambdaQueryWrapper<StrategyVersion>()
                .eq(StrategyVersion::getStrategyId, strategyId)
                .orderByDesc(StrategyVersion::getVersionNo)
                .last("LIMIT 1"));
        return top == null || top.getVersionNo() == null ? 0 : top.getVersionNo();
    }

    private StrategyRun lastRun(Long strategyId) {
        return runMapper.selectOne(new LambdaQueryWrapper<StrategyRun>()
                .eq(StrategyRun::getStrategyId, strategyId)
                .orderByDesc(StrategyRun::getStartedAt)
                .last("LIMIT 1"));
    }

    private Integer versionNo(Long versionId) {
        if (versionId == null) {
            return null;
        }
        StrategyVersion v = versionMapper.selectById(versionId);
        return v == null ? null : v.getVersionNo();
    }

    public WaveRiderConfig parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new WaveRiderConfig();
        }
        try {
            return objectMapper.readValue(json, WaveRiderConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("配置 JSON 解析失败：" + e.getMessage());
        }
    }

    public String serialize(WaveRiderConfig cfg) {
        try {
            return objectMapper.writeValueAsString(cfg);
        } catch (Exception e) {
            throw new IllegalStateException("配置序列化失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把嵌套配置拍平成 {@code a.b=值} 的点号路径，diff 用它做逐项比对。
     *
     * <p>用拍平而不是逐字段反射，是因为配置里既有标量也有 Map（node_type_weights / score_weights），
     * 拍平后两者走同一套比较逻辑，新增配置项也不用改这里。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> flatten(String json) {
        Map<String, Object> flat = new LinkedHashMap<>();
        try {
            Map<String, Object> raw = objectMapper.readValue(json, LinkedHashMap.class);
            flattenInto("", raw, flat);
        } catch (Exception e) {
            throw new IllegalArgumentException("配置 JSON 解析失败：" + e.getMessage());
        }
        return flat;
    }

    @SuppressWarnings("unchecked")
    private void flattenInto(String prefix, Map<String, Object> src, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map) {
                flattenInto(key, (Map<String, Object>) v, out);
            } else {
                out.put(key, v);
            }
        }
    }

    private List<String> unionKeys(Map<String, Object> a, Map<String, Object> b) {
        LinkedHashMap<String, Boolean> keys = new LinkedHashMap<>();
        for (String k : a.keySet()) {
            keys.put(k, true);
        }
        for (String k : b.keySet()) {
            keys.put(k, true);
        }
        Iterator<String> it = keys.keySet().iterator();
        List<String> out = new ArrayList<>();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }

    private String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
