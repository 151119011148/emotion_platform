package com.emotion.service;

import com.emotion.mapper.StockConceptMapper;
import com.emotion.vo.TopicHeat;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * D2 题材热度：把当日涨停池按「概念索引」现算聚合，输出题材热度列表（供展示前 N）。
 * 纯读口，依赖已建成的 {@link ConceptIndexService} 索引，未建索引时自然出空。
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
}