package com.emotion.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.entity.Stock;
import com.emotion.service.StockListService;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.StockRefreshVO;

/**
 * A股代码名称总表。刷新是幂等的 upsert，可以随时多点几次。
 */
@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockListService stockListService;

    public StockController(StockListService stockListService) {
        this.stockListService = stockListService;
    }

    @PostMapping("/refresh")
    public ApiResponse<StockRefreshVO> refresh() {
        return ApiResponse.ok(stockListService.refresh());
    }

    @GetMapping("/search")
    public ApiResponse<List<Stock>> search(@RequestParam(required = false) String q) {
        return ApiResponse.ok(stockListService.search(q));
    }
}
