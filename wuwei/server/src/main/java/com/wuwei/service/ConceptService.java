package com.wuwei.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.entity.ConceptBase;
import com.wuwei.entity.LianbanDaily;
import com.wuwei.entity.LimitUpDaily;
import com.wuwei.entity.NodeDaily;
import com.wuwei.entity.StockConceptRel;
import com.wuwei.mapper.ConceptBaseMapper;
import com.wuwei.mapper.LianbanDailyMapper;
import com.wuwei.mapper.LimitUpDailyMapper;
import com.wuwei.mapper.NodeDailyMapper;
import com.wuwei.mapper.StockConceptRelMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** P6 主线详情 + 龙头分工列表 */
@Service
public class ConceptService {

    private static final List<String> LIFECYCLE =
            Arrays.asList("萌芽", "确认", "扩散", "亢奋", "退潮");
    private static final Map<String, String> ROLE_LABELS = new HashMap<String, String>();

    static {
        ROLE_LABELS.put("ZONG_LONG", "总龙头");
        ROLE_LABELS.put("ZHONG_JUN", "中军");
        ROLE_LABELS.put("GEN_FENG", "跟风");
        ROLE_LABELS.put("KA_WEI", "卡位");
        ROLE_LABELS.put("FAN_BAO", "反包");
    }

    private final ConceptBaseMapper conceptMapper;
    private final NodeDailyMapper nodeMapper;
    private final LianbanDailyMapper lianbanMapper;
    private final LimitUpDailyMapper limitUpMapper;
    private final StockConceptRelMapper relMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConceptService(ConceptBaseMapper conceptMapper, NodeDailyMapper nodeMapper,
                          LianbanDailyMapper lianbanMapper, LimitUpDailyMapper limitUpMapper,
                          StockConceptRelMapper relMapper) {
        this.conceptMapper = conceptMapper;
        this.nodeMapper = nodeMapper;
        this.lianbanMapper = lianbanMapper;
        this.limitUpMapper = limitUpMapper;
        this.relMapper = relMapper;
    }

    /** 当前主线详情：阶段/硬度/持续性/生命周期/龙头分工/轮动 */
    public Map<String, Object> mainLine() {
        NodeDaily latest = nodeMapper.selectOne(
                new QueryWrapper<NodeDaily>().orderByDesc("trade_date").last("limit 1"));
        if (latest == null) throw new RuntimeException("暂无节点数据，请先执行计算");

        ConceptBase concept = null;
        if (latest.getMainConcept() != null && !"—".equals(latest.getMainConcept())) {
            concept = conceptMapper.selectOne(new QueryWrapper<ConceptBase>()
                    .eq("name", latest.getMainConcept()).last("limit 1"));
        }
        if (concept == null) {
            concept = conceptMapper.selectOne(
                    new QueryWrapper<ConceptBase>().eq("is_main_line", true).last("limit 1"));
        }
        if (concept == null) throw new RuntimeException("未找到当前主线");

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("conceptId", concept.getConceptId());
        out.put("name", concept.getName());
        out.put("stage", concept.getStage());
        out.put("catalystHardness", concept.getCatalystHardness());
        out.put("continuousDays", concept.getContinuousDays());
        out.put("activeSince", concept.getActiveSince() == null ? null : concept.getActiveSince().toString());
        out.put("lifecycle", LIFECYCLE);
        out.put("lifecycleIndex", LIFECYCLE.indexOf(concept.getStage()));
        out.put("asOfDate", latest.getTradeDate().toString());
        out.put("node", latest.getNode());

        // 龙头分工（按最新交易日）
        out.put("dragons", dragons(latest.getTradeDate()));

        // 轮动信号（最新日）
        List<Map<String, Object>> signals = new ArrayList<Map<String, Object>>();
        if (latest.getWatchPoints() != null) {
            try {
                Map<String, Object> wp = objectMapper.readValue(
                        latest.getWatchPoints(), new TypeReference<Map<String, Object>>() {});
                Object s = wp.get("rotationSignals");
                if (s instanceof List) {
                    for (Object o : (List<?>) s) {
                        if (o instanceof Map) {
                            Map<String, Object> sig = new LinkedHashMap<String, Object>();
                            sig.put("type", ((Map<?, ?>) o).get("type"));
                            sig.put("message", ((Map<?, ?>) o).get("message"));
                            signals.add(sig);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        out.put("rotationSignals", signals);
        return out;
    }

    /** 某交易日龙头分工列表：总龙头/中军/跟风/卡位/反包，缺席角色补占位 */
    public List<Map<String, Object>> dragons(java.time.LocalDate date) {
        List<LianbanDaily> lb = lianbanMapper.selectList(
                new LambdaQueryWrapper<LianbanDaily>().eq(LianbanDaily::getTradeDate, date));
        List<LimitUpDaily> lu = limitUpMapper.selectList(
                new LambdaQueryWrapper<LimitUpDaily>().eq(LimitUpDaily::getTradeDate, date));
        Map<String, String> dragonRole = new HashMap<String, String>();
        List<StockConceptRel> rels = relMapper.selectList(
                new LambdaQueryWrapper<StockConceptRel>()
                        .eq(StockConceptRel::getTradeDate, date)
                        .isNotNull(StockConceptRel::getDragonRole));
        for (StockConceptRel rel : rels) dragonRole.put(rel.getTsCode(), rel.getDragonRole());

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (LianbanDaily row : lb) {
            String role = null;
            if (row.getIsSpaceLeader() != null && row.getIsSpaceLeader()) role = "ZONG_LONG";
            else if (row.getIsSectorLeader() != null && row.getIsSectorLeader()) role = "ZHONG_JUN";
            if (role == null) role = dragonRole.get(row.getTsCode());
            if (role == null) continue;
            rows.add(rowToDragon(row.getName(), row.getTsCode(), role,
                    row.getNZones(), row.getCloseChg(),
                    "PROMOTE".equals(row.getLeaderAction()) ? "晋级" : "守板"));
        }
        // 触板池里的分工（断板龙头/卡位/反包炸板等不在连板表的情况）
        for (LimitUpDaily row : lu) {
            String role = dragonRole.get(row.getTsCode());
            if (role == null) continue;
            boolean already = false;
            for (Map<String, Object> r : rows) {
                if (r.get("tsCode").equals(row.getTsCode())) {
                    already = true;
                    break;
                }
            }
            if (already) continue;
            String action;
            if ("BOMB".equals(row.getStatus())) action = "炸板";
            else if ("BROKEN".equals(row.getStatus())) action = "断板";
            else if (row.getIsNuke() != null && row.getIsNuke()) action = "核按钮";
            else action = "在板";
            rows.add(rowToDragon(row.getName(), row.getTsCode(), role, row.getNZones(),
                    row.getCloseChg(), action));
        }

        // 按角色聚合：同角色取最强，缺席角色给占位
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String role : new String[]{"ZONG_LONG", "ZHONG_JUN", "GEN_FENG", "KA_WEI", "FAN_BAO"}) {
            Map<String, Object> best = null;
            for (Map<String, Object> r : rows) {
                if (role.equals(r.get("role"))) {
                    if (best == null || zi(r) > zi(best)) best = r;
                }
            }
            if (best != null) {
                out.add(best);
            } else {
                Map<String, Object> empty = new LinkedHashMap<String, Object>();
                empty.put("role", role);
                empty.put("roleLabel", ROLE_LABELS.get(role));
                empty.put("name", null);
                out.add(empty);
            }
        }
        return out;
    }

    private int zi(Map<String, Object> r) {
        Object nz = r.get("nZones");
        return nz instanceof Number ? ((Number) nz).intValue() : 0;
    }

    private Map<String, Object> rowToDragon(String name, String code, String role,
                                            Integer nZones, java.math.BigDecimal closeChg,
                                            String action) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("role", role);
        m.put("roleLabel", ROLE_LABELS.get(role));
        m.put("name", name);
        m.put("tsCode", code);
        m.put("nZones", nZones);
        m.put("closeChg", closeChg);
        m.put("action", action);
        return m;
    }
}
