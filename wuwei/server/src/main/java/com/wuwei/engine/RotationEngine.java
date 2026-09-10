package com.wuwei.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuwei.entity.ConceptBase;
import com.wuwei.entity.LianbanDaily;
import com.wuwei.entity.LimitUpDaily;
import com.wuwei.entity.NodeDaily;
import com.wuwei.mapper.ConceptBaseMapper;
import com.wuwei.mapper.LianbanDailyMapper;
import com.wuwei.mapper.LimitUpDailyMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轮动监测引擎（PRD §4.3）：
 * OLD_MAIN_DECLINE 老主线退潮 / NEW_THEME_SEED 新题材种子 / HIGH_TO_LOW_SWITCH 高低切。
 */
@Service
public class RotationEngine {

    private final LimitUpDailyMapper limitUpMapper;
    private final LianbanDailyMapper lianbanMapper;
    private final ConceptBaseMapper conceptMapper;

    public RotationEngine(LimitUpDailyMapper limitUpMapper, LianbanDailyMapper lianbanMapper,
                          ConceptBaseMapper conceptMapper) {
        this.limitUpMapper = limitUpMapper;
        this.lianbanMapper = lianbanMapper;
        this.conceptMapper = conceptMapper;
    }

    /** 返回信号列表，每项 {type, message} */
    public List<Map<String, Object>> detectSignals(SentimentResult r, NodeDaily prevNodeRow) {
        List<Map<String, Object>> signals = new ArrayList<Map<String, Object>>();

        // 1. 老主线退潮
        if (prevNodeRow != null && "退潮".equals(prevNodeRow.getMainStage())
                && r.getMainConceptName() != null
                && !r.getMainConceptName().equals(prevNodeRow.getMainConcept())) {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("type", "OLD_MAIN_DECLINE");
            s.put("message", "老主线「" + prevNodeRow.getMainConcept() + "」已退潮");
            signals.add(s);
        }

        // 2. 新题材种子：当日某非主线概念首板≥3家
        List<LimitUpDaily> today = limitUpMapper.selectList(
                new LambdaQueryWrapper<LimitUpDaily>().eq(LimitUpDaily::getTradeDate, r.getTradeDate()));
        Map<String, Integer> firstByConcept = new LinkedHashMap<String, Integer>();
        for (LimitUpDaily row : today) {
            if ("ZT_FIRST".equals(row.getStatus()) && row.getConceptMain() != null
                    && !row.getConceptMain().equals(r.getMainConceptId())) {
                Integer n = firstByConcept.get(row.getConceptMain());
                firstByConcept.put(row.getConceptMain(), n == null ? 1 : n + 1);
            }
        }
        String seedNames = null;
        for (Map.Entry<String, Integer> e : firstByConcept.entrySet()) {
            if (e.getValue() >= 3) {
                if (seedNames != null) seedNames += "、";
                ConceptBase c = conceptMapper.selectById(e.getKey());
                String name = c == null ? e.getKey() : c.getName();
                seedNames = (seedNames == null ? "" : seedNames) + name + "(首板" + e.getValue() + "家)";
            }
        }
        if (seedNames != null) {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("type", "NEW_THEME_SEED");
            s.put("message", "新题材种子：" + seedNames);
            signals.add(s);
        }

        // 3. 高低切：昨日空间板今日放量下跌（<-3%）+ 存在新题材首板
        if (prevNodeRow != null && seedNames != null) {
            LocalDate prevDate = r.getTradeDate().minusDays(1);
            List<LianbanDaily> prevLb = lianbanMapper.selectList(
                    new LambdaQueryWrapper<LianbanDaily>()
                            .eq(LianbanDaily::getTradeDate, prevDate)
                            .eq(LianbanDaily::getIsSpaceLeader, true));
            for (LianbanDaily leader : prevLb) {
                LimitUpDaily perf = null;
                for (LimitUpDaily row : today) {
                    if (row.getTsCode().equals(leader.getTsCode())) {
                        perf = row;
                        break;
                    }
                }
                if (perf != null && perf.getCloseChg() != null
                        && perf.getCloseChg().doubleValue() < -3
                        && perf.getAmount() != null && perf.getAmount().doubleValue() > 0) {
                    Map<String, Object> s = new LinkedHashMap<String, Object>();
                    s.put("type", "HIGH_TO_LOW_SWITCH");
                    s.put("message", "高低切信号：老龙头 " + leader.getName()
                            + " 放量下跌 " + perf.getCloseChg() + "%，低位新题材承接");
                    signals.add(s);
                    break;
                }
            }
        }
        return signals;
    }
}
