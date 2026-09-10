package com.wuwei.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wuwei.entity.NodeDaily;
import com.wuwei.entity.SentimentScore;
import com.wuwei.mapper.NodeDailyMapper;
import com.wuwei.mapper.SentimentScoreMapper;
import com.wuwei.service.CalcService;
import com.wuwei.vo.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 手动触发计算（PRD §5：POST /api/calc/run） */
@RestController
@RequestMapping("/api/calc")
public class CalcController {

    private final CalcService calcService;
    private final NodeDailyMapper nodeMapper;
    private final SentimentScoreMapper scoreMapper;

    public CalcController(CalcService calcService, NodeDailyMapper nodeMapper,
                          SentimentScoreMapper scoreMapper) {
        this.calcService = calcService;
        this.nodeMapper = nodeMapper;
        this.scoreMapper = scoreMapper;
    }

    public static class CalcRequest {
        private String date;
        private String from;
        private String to;

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
    }

    /** body 可空：默认重算最新一天；给 date 重算单日；给 from/to 重算区间 */
    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) CalcRequest req) {
        Map<String, Object> result;
        if (req != null && req.getDate() != null && !req.getDate().isEmpty()) {
            result = calcService.calcDates(
                    java.util.Collections.singletonList(LocalDate.parse(req.getDate())));
        } else if (req != null && req.getFrom() != null && req.getTo() != null) {
            result = calcService.calcRange(LocalDate.parse(req.getFrom()), LocalDate.parse(req.getTo()));
        } else {
            NodeDaily latest = nodeMapper.selectOne(
                    new QueryWrapper<NodeDaily>().orderByDesc("trade_date").last("limit 1"));
            if (latest == null) {
                // 全空库：按评分表已有日期全量重算
                List<SentimentScore> scores = scoreMapper.selectList(
                        new QueryWrapper<SentimentScore>().orderByAsc("trade_date"));
                List<LocalDate> dates = new ArrayList<LocalDate>();
                for (SentimentScore s : scores) dates.add(s.getTradeDate());
                result = calcService.calcDates(dates);
            } else {
                result = calcService.calcDates(
                        java.util.Collections.singletonList(latest.getTradeDate()));
            }
        }
        return ApiResponse.ok(result);
    }
}
