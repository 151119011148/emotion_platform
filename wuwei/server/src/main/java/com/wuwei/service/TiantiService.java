package com.wuwei.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wuwei.engine.SentimentEngine;
import com.wuwei.entity.ConceptBase;
import com.wuwei.entity.LianbanDaily;
import com.wuwei.entity.LimitUpDaily;
import com.wuwei.entity.StockConceptRel;
import com.wuwei.mapper.ConceptBaseMapper;
import com.wuwei.mapper.LianbanDailyMapper;
import com.wuwei.mapper.LimitUpDailyMapper;
import com.wuwei.mapper.StockConceptRelMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** P2 连板天梯（四层分组+龙头标签）与 P3 首板池 */
@Service
public class TiantiService {

    private final LianbanDailyMapper lianbanMapper;
    private final LimitUpDailyMapper limitUpMapper;
    private final ConceptBaseMapper conceptMapper;
    private final StockConceptRelMapper relMapper;

    public TiantiService(LianbanDailyMapper lianbanMapper, LimitUpDailyMapper limitUpMapper,
                         ConceptBaseMapper conceptMapper, StockConceptRelMapper relMapper) {
        this.lianbanMapper = lianbanMapper;
        this.limitUpMapper = limitUpMapper;
        this.conceptMapper = conceptMapper;
        this.relMapper = relMapper;
    }

    /** 连板天梯：按四层（极高位/中高位/中位/低位）分组，行内带龙头标签 */
    public Map<String, Object> tianti(java.time.LocalDate date) {
        List<LianbanDaily> rows = lianbanMapper.selectList(
                new LambdaQueryWrapper<LianbanDaily>().eq(LianbanDaily::getTradeDate, date));

        int maxBoard = 0;
        for (LianbanDaily row : rows) {
            if (row.getNZones() != null && row.getNZones() > maxBoard) maxBoard = row.getNZones();
        }

        // 龙头分工标签
        Map<String, String> dragonRole = new HashMap<String, String>();
        List<StockConceptRel> rels = relMapper.selectList(
                new LambdaQueryWrapper<StockConceptRel>()
                        .eq(StockConceptRel::getTradeDate, date)
                        .isNotNull(StockConceptRel::getDragonRole));
        for (StockConceptRel rel : rels) dragonRole.put(rel.getTsCode(), rel.getDragonRole());

        Map<String, List<Map<String, Object>>> tiers = new LinkedHashMap<String, List<Map<String, Object>>>();
        tiers.put("HIGH", new ArrayList<Map<String, Object>>());
        tiers.put("MIDHIGH", new ArrayList<Map<String, Object>>());
        tiers.put("MID", new ArrayList<Map<String, Object>>());
        tiers.put("LOW", new ArrayList<Map<String, Object>>());

        for (LianbanDaily row : rows) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("tsCode", row.getTsCode());
            m.put("name", row.getName());
            m.put("nZones", row.getNZones());
            m.put("prevNZones", row.getPrevNZones());
            m.put("isPromote", row.getIsPromote());
            m.put("concept", conceptName(row.getConceptMain()));
            m.put("firstLuTime", row.getFirstLuTime() == null ? null : row.getFirstLuTime().toString());
            m.put("openTimes", row.getOpenTimes());
            m.put("fdAmount", row.getFdAmount());
            m.put("turnoverRate", row.getTurnoverRate());
            m.put("closeChg", row.getCloseChg());
            m.put("isBack", row.getIsBack());
            m.put("monitorStatus", row.getMonitorStatus());
            m.put("leaderAction", row.getLeaderAction());
            String label = null;
            if (row.getIsSpaceLeader() != null && row.getIsSpaceLeader()) label = "ZONG_LONG";
            else if (row.getIsSectorLeader() != null && row.getIsSectorLeader()) label = "ZHONG_JUN";
            if (label == null) label = dragonRole.get(row.getTsCode());
            m.put("dragonRole", label);
            String tier = SentimentEngine.tierOf(row.getNZones() == null ? 0 : row.getNZones(), maxBoard);
            m.put("tier", tier);
            tiers.get(tier).add(m);
        }
        // 各层内按板数降序、涨停时间升序
        for (List<Map<String, Object>> list : tiers.values()) {
            list.sort((a, b) -> {
                int za = (Integer) a.get("nZones"), zb = (Integer) b.get("nZones");
                if (za != zb) return zb - za;
                Object ta = a.get("firstLuTime"), tb = b.get("firstLuTime");
                if (ta == null) return 1;
                if (tb == null) return -1;
                return String.valueOf(ta).compareTo(String.valueOf(tb));
            });
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("tradeDate", date.toString());
        out.put("maxBoard", maxBoard);
        out.put("count", rows.size());
        out.put("tiers", tiers);
        return out;
    }

    /** 首板池：首板封住 + 炸板，附次日溢价 */
    public Map<String, Object> shouban(java.time.LocalDate date) {
        List<LimitUpDaily> rows = limitUpMapper.selectList(
                new LambdaQueryWrapper<LimitUpDaily>()
                        .eq(LimitUpDaily::getTradeDate, date)
                        .and(w -> w.eq(LimitUpDaily::getStatus, "ZT_FIRST")
                                .or().eq(LimitUpDaily::getStatus, "BOMB")
                                .or().nested(x -> x.eq(LimitUpDaily::getStatus, "ZT")
                                        .eq(LimitUpDaily::getIsFirstBoard, true))));
        List<Map<String, Object>> sealed = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> bombed = new ArrayList<Map<String, Object>>();
        for (LimitUpDaily row : rows) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("tsCode", row.getTsCode());
            m.put("name", row.getName());
            m.put("status", row.getStatus());
            m.put("concept", conceptName(row.getConceptMain()));
            m.put("firstLuTime", row.getFirstLuTime() == null ? null : row.getFirstLuTime().toString());
            m.put("openTimes", row.getOpenTimes());
            m.put("fdAmount", row.getFdAmount());
            m.put("amount", row.getAmount());
            m.put("turnoverRate", row.getTurnoverRate());
            m.put("closeChg", row.getCloseChg());
            m.put("nextOpenChg", row.getNextOpenChg());
            m.put("nextCloseChg", row.getNextCloseChg());
            m.put("monitorStatus", row.getMonitorStatus());
            if ("BOMB".equals(row.getStatus())) bombed.add(m);
            else sealed.add(m);
        }
        sealed.sort((a, b) -> String.valueOf(a.get("firstLuTime"))
                .compareTo(String.valueOf(b.get("firstLuTime"))));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("tradeDate", date.toString());
        out.put("sealed", sealed);
        out.put("bombed", bombed);
        out.put("sealedCount", sealed.size());
        out.put("bombedCount", bombed.size());
        return out;
    }

    /** 天梯最新日期（供「最新」按钮） */
    public java.time.LocalDate latestDate() {
        LianbanDaily row = lianbanMapper.selectOne(
                new QueryWrapper<LianbanDaily>().orderByDesc("trade_date").last("limit 1"));
        return row == null ? null : row.getTradeDate();
    }

    private String conceptName(String id) {
        if (id == null) return "—";
        ConceptBase c = conceptMapper.selectById(id);
        return c == null ? id : c.getName();
    }
}
