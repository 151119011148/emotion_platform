package com.wuwei.controller;

import com.wuwei.service.ConceptService;
import com.wuwei.vo.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** P6：当前主线详情（阶段/硬度/龙头分工/生命周期/轮动） */
@RestController
@RequestMapping("/api/concept")
public class ConceptController {

    private final ConceptService service;

    public ConceptController(ConceptService service) {
        this.service = service;
    }

    @GetMapping("/main")
    public ApiResponse<Map<String, Object>> main() {
        return ApiResponse.ok(service.mainLine());
    }
}
