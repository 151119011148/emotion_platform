package com.wuwei.controller;

import com.wuwei.service.TiantiService;
import com.wuwei.vo.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** P2 连板天梯（四层+龙头标签）与 P3 首板池 */
@RestController
@RequestMapping("/api/tianti")
public class TiantiController {

    private final TiantiService service;

    public TiantiController(TiantiService service) {
        this.service = service;
    }

    @GetMapping("/{date}")
    public ApiResponse<Map<String, Object>> tianti(@PathVariable String date) {
        return ApiResponse.ok(service.tianti(LocalDate.parse(date)));
    }

    @GetMapping("/latest")
    public ApiResponse<Map<String, Object>> latest() {
        LocalDate d = service.latestDate();
        if (d == null) throw new RuntimeException("暂无连板数据");
        return ApiResponse.ok(service.tianti(d));
    }

    @GetMapping("/{date}/shouban")
    public ApiResponse<Map<String, Object>> shouban(@PathVariable String date) {
        return ApiResponse.ok(service.shouban(resolveDate(date)));
    }

    @GetMapping("/latest/shouban")
    public ApiResponse<Map<String, Object>> shoubanLatest() {
        return shouban(service.latestDate().toString());
    }

    /** 兼容 latest / 具体日期两种写法 */
    private LocalDate resolveDate(String date) {
        if ("latest".equalsIgnoreCase(date)) {
            LocalDate d = service.latestDate();
            if (d == null) throw new RuntimeException("暂无连板数据");
            return d;
        }
        return LocalDate.parse(date);
    }
}
