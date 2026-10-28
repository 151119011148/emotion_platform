package com.emotion.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.exception.BizException;
import com.emotion.service.StockTapeService;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.StockTapeVO;

/**
 * 个股分时：分钟序列 + 集合竞价快照，全站任何一处点到个股都读这一条。
 *
 * <p>路径不叫 {@code /api/intraday/*}：那个前缀已经是「日内核心·题材表」
 * （{@link IntradayController}），挂上去会让日内这两个完全不同的东西共用一个命名空间。
 *
 * <p>只读库、不回源，所以这条接口不会因上游慢而挂住——分时数据的生命线在写入侧
 * （09:26 与 19:12 两趟排班），读侧要做的只是把「这天到底有没有」讲老实。
 */
@RestController
@RequestMapping("/api/stock-tape")
public class StockTapeController {

    private final StockTapeService stockTapeService;

    public StockTapeController(StockTapeService stockTapeService) {
        this.stockTapeService = stockTapeService;
    }

    /**
     * @param code 6 位代码，必填
     * @param date yyyy-MM-dd；不传按当天。页面自己带它当前的快照日，
     *             否则周末打开天梯会问一个没有行情的今天
     */
    @GetMapping
    public ApiResponse<StockTapeVO> tape(@RequestParam String code,
                                         @RequestParam(required = false) String date) {
        return ApiResponse.ok(stockTapeService.tape(code, parse(date)));
    }

    private static LocalDate parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            // GlobalExceptionHandler 把 message 原样弹到界面上，所以必须是中文
            throw new BizException("日期格式应为 yyyy-MM-dd，收到：" + raw);
        }
    }
}
