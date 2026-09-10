package com.wuwei.controller;

import com.wuwei.service.NodeQueryService;
import com.wuwei.vo.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** 轮动信号（老主线退潮/新题材种子/高低切） */
@RestController
@RequestMapping("/api/rotation")
public class RotationController {

    private final NodeQueryService service;

    public RotationController(NodeQueryService service) {
        this.service = service;
    }

    @GetMapping("/{date}")
    public ApiResponse<Map<String, Object>> rotation(@PathVariable String date) {
        return ApiResponse.ok(service.rotation(LocalDate.parse(date)));
    }
}
