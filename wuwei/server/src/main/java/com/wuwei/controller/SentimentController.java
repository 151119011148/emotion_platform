package com.wuwei.controller;

import com.wuwei.service.SentimentQueryService;
import com.wuwei.vo.ApiResponse;
import com.wuwei.vo.CurveVO;
import com.wuwei.vo.SentimentTodayVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** P1/P7：当日总分+节点+操作指令；指定日评分；温度曲线 */
@RestController
@RequestMapping("/api/sentiment")
public class SentimentController {

    private final SentimentQueryService service;

    public SentimentController(SentimentQueryService service) {
        this.service = service;
    }

    @GetMapping("/today")
    public ApiResponse<SentimentTodayVO> today() {
        return ApiResponse.ok(service.today());
    }

    @GetMapping("/{date}")
    public ApiResponse<SentimentTodayVO> byDate(@PathVariable String date) {
        return ApiResponse.ok(service.byDate(LocalDate.parse(date)));
    }

    @GetMapping("/curve")
    public ApiResponse<CurveVO> curve(@RequestParam(defaultValue = "20") int days) {
        return ApiResponse.ok(service.curve(days));
    }
}
