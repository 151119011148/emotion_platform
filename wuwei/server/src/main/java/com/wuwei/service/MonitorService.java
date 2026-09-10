package com.wuwei.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wuwei.entity.MonitorPool;
import com.wuwei.mapper.MonitorPoolMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** P4 异动监管池 */
@Service
public class MonitorService {

    private static final Map<String, String> STATUS_LABELS = new LinkedHashMap<String, String>();

    static {
        STATUS_LABELS.put("ORDINARY", "普通异动");
        STATUS_LABELS.put("SERIOUS", "严重异动");
        STATUS_LABELS.put("KEY_MONITOR", "重点监控");
        STATUS_LABELS.put("SUSPEND", "停牌核查");
        STATUS_LABELS.put("RESUME", "复牌");
    }

    private final MonitorPoolMapper mapper;

    public MonitorService(MonitorPoolMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> pool() {
        List<MonitorPool> rows = mapper.selectList(
                new QueryWrapper<MonitorPool>().orderByDesc("enter_date"));
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        int active = 0, serious = 0;
        LocalDate today = LocalDate.now();
        for (MonitorPool row : rows) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("tsCode", row.getTsCode());
            m.put("name", row.getName());
            m.put("status", row.getStatus());
            m.put("statusLabel", STATUS_LABELS.getOrDefault(row.getStatus(), row.getStatus()));
            m.put("enterDate", row.getEnterDate() == null ? null : row.getEnterDate().toString());
            m.put("exitDate", row.getExitDate() == null ? null : row.getExitDate().toString());
            m.put("relatedConcept", row.getRelatedConcept());
            m.put("isHighPosition", row.getIsHighPosition());
            boolean isActive = row.getExitDate() == null || row.getExitDate().isAfter(today);
            m.put("active", isActive);
            if (isActive) active++;
            if ("SERIOUS".equals(row.getStatus()) || "KEY_MONITOR".equals(row.getStatus())
                    || "SUSPEND".equals(row.getStatus())) serious++;
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("rows", list);
        out.put("activeCount", active);
        out.put("seriousCount", serious);
        return out;
    }
}
