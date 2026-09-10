package com.wuwei.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.entity.ConceptBase;
import com.wuwei.entity.NodeDaily;
import com.wuwei.entity.SentimentScore;
import com.wuwei.mapper.ConceptBaseMapper;
import com.wuwei.mapper.NodeDailyMapper;
import com.wuwei.mapper.SentimentScoreMapper;
import com.wuwei.vo.CurveVO;
import com.wuwei.vo.SentimentTodayVO;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 评分/节点查询与操作指令生成（P1、P7 数据源） */
@Service
public class SentimentQueryService {

    private final SentimentScoreMapper scoreMapper;
    private final NodeDailyMapper nodeMapper;
    private final ConceptBaseMapper conceptMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SentimentQueryService(SentimentScoreMapper scoreMapper, NodeDailyMapper nodeMapper,
                                 ConceptBaseMapper conceptMapper) {
        this.scoreMapper = scoreMapper;
        this.nodeMapper = nodeMapper;
        this.conceptMapper = conceptMapper;
    }

    /** 当日 = 库里最新一个有评分的交易日 */
    public SentimentTodayVO today() {
        SentimentScore latest = scoreMapper.selectOne(
                new QueryWrapper<SentimentScore>().orderByDesc("trade_date").last("limit 1"));
        if (latest == null) throw new RuntimeException("暂无评分数据，请先执行计算");
        return byDate(latest.getTradeDate());
    }

    public SentimentTodayVO byDate(LocalDate date) {
        SentimentScore ss = scoreMapper.selectById(date);
        if (ss == null) throw new RuntimeException("该日期无评分数据: " + date);
        NodeDaily nd = nodeMapper.selectById(date);
        SentimentScore prev = scoreMapper.selectOne(
                new QueryWrapper<SentimentScore>().lt("trade_date", date)
                        .orderByDesc("trade_date").last("limit 1"));

        SentimentTodayVO vo = new SentimentTodayVO();
        vo.setTradeDate(date.toString());
        vo.setTotalScore(d(ss.getTotalScore()));
        vo.setPrevTotal(prev == null ? null : d(prev.getTotalScore()));
        vo.setScoreMarket(d(ss.getScoreMarket()));
        vo.setScoreConcept(d(ss.getScoreConcept()));
        vo.setScoreLianban(d(ss.getScoreLianban()));
        vo.setScoreShouban(d(ss.getScoreShouban()));
        vo.setScoreZhenyan(d(ss.getScoreZhenyan()));
        vo.setForceExit(ss.getForceExit() != null && ss.getForceExit());
        vo.setForceReason(ss.getForceReason());

        // 明细快照
        if (ss.getDetailsJson() != null) {
            try {
                Map<String, Object> details = objectMapper.readValue(
                        ss.getDetailsJson(), new TypeReference<Map<String, Object>>() {});
                vo.setDetails(details);
            } catch (Exception ignored) {
            }
        }
        // 龙头信息从明细里取
        Object zy = vo.getDetails().get("zhenyan");
        if (zy instanceof Map) {
            Map<?, ?> zyMap = (Map<?, ?>) zy;
            Object name = zyMap.get("leaderName");
            if (name != null) vo.setLeaderName(String.valueOf(name));
            Object board = zyMap.get("leaderBoard");
            if (board instanceof Number) vo.setLeaderBoard(((Number) board).intValue());
            Object action = zyMap.get("leaderAction");
            if (action != null) vo.setLeaderAction(String.valueOf(action));
        }

        // 节点
        if (nd != null) {
            vo.setNode(nd.getNode());
            vo.setPrevNode(nd.getPrevNode());
            vo.setTransition(nd.getTransition());
            vo.setTriggerReason(nd.getTriggerReason());
            vo.setForecast(nd.getForecast());
            vo.setMainConcept(nd.getMainConcept());
            vo.setMainStage(nd.getMainStage());
            if (nd.getWatchPoints() != null) {
                try {
                    Map<String, Object> wp = objectMapper.readValue(
                            nd.getWatchPoints(), new TypeReference<Map<String, Object>>() {});
                    Object w = wp.get("watchPoints");
                    if (w instanceof List) {
                        for (Object o : (List<?>) w) vo.getWatchPoints().add(String.valueOf(o));
                    }
                    Object s = wp.get("rotationSignals");
                    if (s instanceof List) {
                        for (Object o : (List<?>) s) {
                            if (o instanceof Map) {
                                Map<String, Object> sig = new LinkedHashMap<String, Object>();
                                Map<?, ?> m = (Map<?, ?>) o;
                                sig.put("type", m.get("type"));
                                sig.put("message", m.get("message"));
                                vo.getRotationSignals().add(sig);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // 主线硬度
        if (vo.getMainConcept() != null && !"—".equals(vo.getMainConcept())) {
            ConceptBase c = conceptMapper.selectOne(
                    new QueryWrapper<ConceptBase>().eq("name", vo.getMainConcept()).last("limit 1"));
            if (c != null) {
                vo.setCatalystHardness(c.getCatalystHardness());
                if (vo.getMainStage() == null) vo.setMainStage(c.getStage());
            }
        }

        vo.setInstruction(buildInstruction(vo));
        return vo;
    }

    public CurveVO curve(int days) {
        List<SentimentScore> scores = scoreMapper.selectList(
                new QueryWrapper<SentimentScore>().orderByDesc("trade_date").last("limit " + days));
        java.util.Collections.reverse(scores);
        CurveVO vo = new CurveVO();
        for (SentimentScore ss : scores) {
            vo.getDates().add(ss.getTradeDate().toString());
            vo.getTotals().add(d(ss.getTotalScore()));
            NodeDaily nd = nodeMapper.selectById(ss.getTradeDate());
            vo.getNodes().add(nd == null ? "" : nd.getNode());
            vo.getMainConcepts().add(nd == null ? "" : nd.getMainConcept());
            vo.getScores().computeIfAbsent("market", k -> new ArrayList<Double>()).add(d(ss.getScoreMarket()));
            vo.getScores().computeIfAbsent("concept", k -> new ArrayList<Double>()).add(d(ss.getScoreConcept()));
            vo.getScores().computeIfAbsent("lianban", k -> new ArrayList<Double>()).add(d(ss.getScoreLianban()));
            vo.getScores().computeIfAbsent("shouban", k -> new ArrayList<Double>()).add(d(ss.getScoreShouban()));
            vo.getScores().computeIfAbsent("zhenyan", k -> new ArrayList<Double>()).add(d(ss.getScoreZhenyan()));
        }
        return vo;
    }

    /**
     * 操作指令（PRD §1.2：仓位/方向/禁区/明日锚点），由节点状态 + 龙头状态生成。
     */
    private Map<String, Object> buildInstruction(SentimentTodayVO vo) {
        String node = vo.getNode() == null ? "" : vo.getNode();
        String position, direction, forbidden, anchor;
        String leader = vo.getLeaderName();
        Integer board = vo.getLeaderBoard();
        String nextBoard = (leader != null && board != null) ? leader + " 能否晋级 " + (board + 1) + " 板" : null;

        switch (node) {
            case "冰点":
                position = "0~1 成";
                direction = "空仓观望；尾盘可轻仓试错低位首板";
                forbidden = "任何中高位与连板接力";
                anchor = "明日首板家数能否回到 30 家以上";
                break;
            case "启动":
                position = "2~3 成";
                direction = "试错主线首板与低位 2 板，预判主线方向";
                forbidden = "高位板与反包追涨";
                anchor = nextBoard != null ? nextBoard + "（发酵发令枪）" : "主线能否确立（涨停聚集度≥25%）";
                break;
            case "发酵":
                position = "5~7 成";
                direction = "主线中军低吸 + 总龙头打板，持股为主";
                forbidden = "杂毛跟风与蹭概念股";
                anchor = nextBoard != null ? nextBoard : "中位晋级率能否维持 ≥30%";
                break;
            case "高潮":
                position = "≤5 成，逢冲高逐步兑现";
                direction = "只做总龙头，冲高兑现不恋战";
                forbidden = "新开仓跟风 / 卡位 / 反包";
                anchor = (leader != null ? leader + " " : "") + "断板与大面家数（≥2 家即撤）";
                break;
            case "分歧":
                position = "3~5 成";
                direction = "低吸中军，或等分歧转一致再上车";
                forbidden = "追涨打板与半路接力";
                anchor = nextBoard != null ? nextBoard.replace("晋级", "反包/晋级") + "；中军承接力度" : "中军承接力度与分歧转一致";
                break;
            default: // 退潮
                position = Boolean.TRUE.equals(vo.getForceExit()) ? "0 成" : "0~1 成";
                direction = Boolean.TRUE.equals(vo.getForceExit()) ? "全面空仓，规避监管/亏钱效应风险" : "空仓休息；仅轻仓试错新题材首板";
                forbidden = Boolean.TRUE.equals(vo.getForceExit()) ? "一切股票" : "一切高位接力与超跌低吸";
                anchor = "新题材首板能否批量出现（≥3 家）";
                break;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("position", position);
        m.put("direction", direction);
        m.put("forbidden", forbidden);
        m.put("anchor", anchor);
        return m;
    }

    private Double d(java.math.BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }
}
