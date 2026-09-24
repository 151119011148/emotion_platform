package com.emotion.service;

import com.emotion.entity.CandidateStock;
import com.emotion.mapper.StockConceptMapper;
import com.emotion.vo.ThemeTagVO;
import com.emotion.vo.TopicHeat;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D2 题材热度：把当日涨停池按「概念索引」现算聚合，输出题材热度列表（供展示前 N）。
 * 纯读口，依赖已建成的 {@link ConceptIndexService} 索引，未建索引时自然出空。
 *
 * <p>另有 {@link #tagTdxThemes}：读同一张表，但回答的是另一个问题——
 * 「这只票属于哪些题材」，而不是「今天哪些题材最热」。
 */
@Service
public class TopicHeatService {

    private final StockConceptMapper mapper;

    public TopicHeatService(StockConceptMapper mapper) {
        this.mapper = mapper;
    }

    /** 当日涨停池按概念聚合的题材热度，按涨停家数降序取前 topN。 */
    public List<TopicHeat> topByZt(LocalDate date, int topN) {
        if (topN <= 0) {
            return java.util.Collections.emptyList();
        }
        return mapper.aggregateZtTopics(date, topN);
    }

    /**
     * 给候选行打「来自哪个通达信题材」的标（<strong>瞬态</strong>，不落库）。
     *
     * <p>为什么按热度排序：一只涨停票实测带 1~11 个通达信题材，全铺出来是一堵墙。
     * 按「当日该题材的涨停家数」降序，最热的排第一，界面只铺前几个，其余折叠。
     *
     * <p>为什么读侧算而不是落库：题材成分会变、当日热度天天不同。落库等于
     * 把「今天谁最热」冻在候选行上，明天翻这天的清单会看到昨天的热度。
     *
     * <p>取不到题材时给空列表而不是 null：界面据此回退到行业，见
     * {@code WaveRiderView} 的题材列——宁可显示一个口径不同的标签，也不要空着
     * 让人以为「这只票没题材」。
     */
    public void tagTdxThemes(List<CandidateStock> rows, LocalDate date) {
        if (rows == null || rows.isEmpty() || date == null) {
            return;
        }
        List<String> codes = new ArrayList<String>();
        for (CandidateStock c : rows) {
            if (c != null && c.getCode() != null && !codes.contains(c.getCode())) {
                codes.add(c.getCode());
            }
        }
        if (codes.isEmpty()) {
            return;
        }

        List<ThemeTagVO> flat = mapper.listThemesByCodes(codes, date);
        Map<String, List<ThemeTagVO>> byCode = new LinkedHashMap<String, List<ThemeTagVO>>();
        if (flat != null) {
            for (ThemeTagVO t : flat) {
                if (t == null || t.getCode() == null) {
                    continue;
                }
                List<ThemeTagVO> bucket = byCode.get(t.getCode());
                if (bucket == null) {
                    bucket = new ArrayList<ThemeTagVO>();
                    byCode.put(t.getCode(), bucket);
                }
                bucket.add(t);
            }
        }
        // 顺序不打乱：SQL 已按 (code, 涨停家数 DESC) 排好，同票的顺序就是热度顺序
        for (CandidateStock c : rows) {
            if (c == null) {
                continue;
            }
            List<ThemeTagVO> mine = byCode.get(c.getCode());
            c.setTdxThemes(mine == null ? new ArrayList<ThemeTagVO>() : mine);
        }
    }
}