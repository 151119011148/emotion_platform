package com.wuwei.controller;

import com.wuwei.service.MonitorService;
import com.wuwei.vo.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** P4 异动监管池 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorService service;

    public MonitorController(MonitorService service) {
        this.service = service;
    }

    @GetMapping("/pool")
    public ApiResponse<Map<String, Object>> pool() {
        return ApiResponse.ok(service.pool());
    }
}
