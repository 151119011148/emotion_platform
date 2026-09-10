package com.wuwei.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.entity.NodeDaily;
import com.wuwei.mapper.NodeDailyMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** P5 节点演变时间线 + 轮动信号查询 */
@Service
public class NodeQueryService {

    private final NodeDailyMapper nodeMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NodeQueryService(NodeDailyMapper nodeMapper) {
        this.nodeMapper = nodeMapper;
    }

    /** 某日轮动信号（计算时存于 watch_points JSON） */
    public Map<String, Object> rotation(LocalDate date) {
        NodeDaily row = nodeMapper.selectById(date);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("tradeDate", date.toString());
        List<Map<String, Object>> signals = new ArrayList<Map<String, Object>>();
        if (row != null && row.getWatchPoints() != null) {
            try {
                Map<String, Object> wp = objectMapper.readValue(
                        row.getWatchPoints(), new TypeReference<Map<String, Object>>() {});
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
        out.put("signals", signals);
        return out;
    }

    public Map<String, Object> history() {
        List<NodeDaily> rows = nodeMapper.selectList(
                new QueryWrapper<NodeDaily>().orderByAsc("trade_date"));
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (NodeDaily row : rows) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("tradeDate", row.getTradeDate().toString());
            m.put("node", row.getNode());
            m.put("prevNode", row.getPrevNode());
            m.put("transition", row.getTransition());
            m.put("triggerReason", row.getTriggerReason());
            m.put("forecast", row.getForecast());
            m.put("mainConcept", row.getMainConcept());
            m.put("mainStage", row.getMainStage());
            if (row.getWatchPoints() != null) {
                try {
                    Map<String, Object> wp = objectMapper.readValue(
                            row.getWatchPoints(), new TypeReference<Map<String, Object>>() {});
                    m.put("watchPoints", wp.get("watchPoints"));
                    m.put("rotationSignals", wp.get("rotationSignals"));
                } catch (Exception ignored) {
                    m.put("watchPoints", new ArrayList<String>());
                }
            }
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("rows", list);
        out.put("count", list.size());
        return out;
    }
}
