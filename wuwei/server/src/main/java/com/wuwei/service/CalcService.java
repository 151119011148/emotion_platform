package com.wuwei.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.engine.NodeEngine;
import com.wuwei.engine.NodeResult;
import com.wuwei.engine.RotationEngine;
import com.wuwei.engine.SentimentEngine;
import com.wuwei.engine.SentimentResult;
import com.wuwei.entity.ConceptBase;
import com.wuwei.entity.NodeDaily;
import com.wuwei.entity.SentimentScore;
import com.wuwei.mapper.ConceptBaseMapper;
import com.wuwei.mapper.NodeDailyMapper;
import com.wuwei.mapper.SentimentScoreMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计算编排：对某交易日跑 5维评分 → 节点状态机 → 轮动信号 → 题材生命周期推进，
 * 并落库 t_sentiment_score / t_node_daily（PRD §7 的 calc_sentiment 环节）。
 */
@Service
public class CalcService {

    private final SentimentEngine sentimentEngine;
    private final NodeEngine nodeEngine;
    private final RotationEngine rotationEngine;
    private final SentimentScoreMapper scoreMapper;
    private final NodeDailyMapper nodeMapper;
    private final ConceptBaseMapper conceptMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CalcService(SentimentEngine sentimentEngine, NodeEngine nodeEngine,
                       RotationEngine rotationEngine, SentimentScoreMapper scoreMapper,
                       NodeDailyMapper nodeMapper, ConceptBaseMapper conceptMapper) {
        this.sentimentEngine = sentimentEngine;
        this.nodeEngine = nodeEngine;
        this.rotationEngine = rotationEngine;
        this.scoreMapper = scoreMapper;
        this.nodeMapper = nodeMapper;
        this.conceptMapper = conceptMapper;
    }

    /** 单日计算并落库；返回节点结果供调用方使用 */
    @Transactional
    public NodeResult calcAndStore(LocalDate date) {
        SentimentResult r = sentimentEngine.calcDaily(date);

        // 前一日总分（供节点引擎判断回落幅度）
        SentimentScore prevScore = scoreMapper.selectOne(
                new QueryWrapper<SentimentScore>().lt("trade_date", date)
                        .orderByDesc("trade_date").last("limit 1"));
        r.setPrevTotal(prevScore == null || prevScore.getTotalScore() == null ? 0
                : prevScore.getTotalScore().doubleValue());

        NodeDaily prevNodeRow = nodeMapper.selectOne(
                new QueryWrapper<NodeDaily>().lt("trade_date", date)
                        .orderByDesc("trade_date").last("limit 1"));

        NodeResult nr = nodeEngine.determine(r, prevNodeRow);
        List<Map<String, Object>> signals = rotationEngine.detectSignals(r, prevNodeRow);

        // 题材生命周期推进（判定完成后推进主线阶段）
        advanceConcept(r, nr, prevNodeRow);

        // ---- 落库 sentiment_score ----
        scoreMapper.delete(new LambdaQueryWrapper<SentimentScore>()
                .eq(SentimentScore::getTradeDate, date));
        SentimentScore ss = new SentimentScore();
        ss.setTradeDate(date);
        ss.setScoreMarket(bd(r.getMarket()));
        ss.setScoreConcept(bd(r.getConcept()));
        ss.setScoreLianban(bd(r.getLianban()));
        ss.setScoreShouban(bd(r.getShouban()));
        ss.setScoreZhenyan(bd(r.getZhenyan()));
        ss.setTotalScore(bd(r.getTotal()));
        ss.setForceExit(r.isForceExit());
        ss.setForceReason(r.getForceReason());
        try {
            ss.setDetailsJson(objectMapper.writeValueAsString(r.getDetails()));
        } catch (Exception e) {
            ss.setDetailsJson("{}");
        }
        scoreMapper.insert(ss);

        // ---- 落库 node_daily（观察点 + 轮动信号合并进 watch_points JSON） ----
        nodeMapper.delete(new LambdaQueryWrapper<NodeDaily>()
                .eq(NodeDaily::getTradeDate, date));
        NodeDaily nd = new NodeDaily();
        nd.setTradeDate(date);
        nd.setNode(nr.getNode());
        nd.setPrevNode(nr.getPrevNode());
        nd.setTransition(nr.getTransition());
        nd.setTriggerReason(nr.getTriggerReason());
        nd.setForecast(nr.getForecast());
        nd.setMainConcept(nr.getMainConcept());
        nd.setMainStage(nr.getMainStage());
        Map<String, Object> wp = new LinkedHashMap<String, Object>();
        wp.put("watchPoints", nr.getWatchPoints());
        wp.put("rotationSignals", signals);
        try {
            nd.setWatchPoints(objectMapper.writeValueAsString(wp));
        } catch (Exception e) {
            nd.setWatchPoints("{}");
        }
        nodeMapper.insert(nd);
        return nr;
    }

    /** 区间重算：按触板池里出现的交易日升序逐日跑 */
    @Transactional
    public Map<String, Object> calcRange(LocalDate from, LocalDate to) {
        List<NodeDaily> dates = nodeMapper.selectList(
                new QueryWrapper<NodeDaily>().between("trade_date", from, to)
                        .orderByAsc("trade_date"));
        List<LocalDate> list = new ArrayList<LocalDate>();
        for (NodeDaily d : dates) list.add(d.getTradeDate());
        return calcDates(list);
    }

    /** 对给定日期序列依序重算 */
    @Transactional
    public Map<String, Object> calcDates(List<LocalDate> dates) {
        Map<String, Object> out = new HashMap<String, Object>();
        List<String> nodes = new ArrayList<String>();
        for (LocalDate d : dates) {
            NodeResult nr = calcAndStore(d);
            nodes.add(d + " → " + nr.getNode());
        }
        out.put("count", dates.size());
        out.put("nodes", nodes);
        return out;
    }

    /**
     * 题材生命周期推进（PRD §1.3：萌芽→确认→扩散→亢奋→退潮）。
     * 依据：涨停聚集度、连续活跃天数、当日节点、大面家数。
     * 同时同步 is_main_line 旗标（当前主线=1）。
     */
    private void advanceConcept(SentimentResult r, NodeResult nr, NodeDaily prevNodeRow) {
        if (r.getMainConceptId() == null) return;
        ConceptBase c = conceptMapper.selectById(r.getMainConceptId());
        if (c == null) return;

        // 主线切换时，老主线直接降级为退潮（轮动引擎据此给出 OLD_MAIN_DECLINE）
        if (prevNodeRow != null && prevNodeRow.getMainConcept() != null
                && !"—".equals(prevNodeRow.getMainConcept())
                && !prevNodeRow.getMainConcept().equals(c.getName())) {
            ConceptBase oldMain = conceptMapper.selectOne(
                    new LambdaQueryWrapper<ConceptBase>()
                            .eq(ConceptBase::getName, prevNodeRow.getMainConcept())
                            .last("limit 1"));
            if (oldMain != null && !"退潮".equals(oldMain.getStage())) {
                oldMain.setStage("退潮");
                conceptMapper.updateById(oldMain);
            }
        }

        int cont = c.getContinuousDays() == null ? 0 : c.getContinuousDays();
        c.setContinuousDays(cont + 1);
        if (c.getActiveSince() == null) c.setActiveSince(r.getTradeDate());

        String stage = c.getStage() == null ? "萌芽" : c.getStage();
        String node = nr.getNode();
        if ("退潮".equals(stage)) {
            // 退潮后若情绪重新启动且聚集度回升，可重新确认
            if ("启动".equals(node) && r.getZtRatio() >= 0.30) stage = "确认";
        } else if ("萌芽".equals(stage)) {
            if (r.getZtRatio() >= 0.25) stage = "确认";
        } else if ("确认".equals(stage)) {
            if (r.getZtRatio() >= 0.30 && cont + 1 >= 3) stage = "扩散";
        } else if ("扩散".equals(stage)) {
            if ("高潮".equals(node)) stage = "亢奋";
            else if (r.getBigNoodleCount() >= 2 && ("分歧".equals(node) || "退潮".equals(node))) stage = "退潮";
        } else if ("亢奋".equals(stage)) {
            if ("退潮".equals(node) || ("分歧".equals(node) && r.getBigNoodleCount() >= 2)) stage = "退潮";
        }
        c.setStage(stage);
        conceptMapper.updateById(c);
        r.setMainStage(stage);
        nr.setMainStage(stage);

        // 同步 is_main_line 旗标
        ConceptBase flag = new ConceptBase();
        flag.setIsMainLine(false);
        conceptMapper.update(flag, new LambdaQueryWrapper<ConceptBase>()
                .eq(ConceptBase::getIsMainLine, true));
        ConceptBase mk = new ConceptBase();
        mk.setConceptId(c.getConceptId());
        mk.setIsMainLine(true);
        conceptMapper.updateById(mk);
    }

    private java.math.BigDecimal bd(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
