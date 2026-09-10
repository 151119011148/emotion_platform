package com.wuwei.controller;

import com.wuwei.service.ConceptService;
import com.wuwei.vo.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 龙头分工列表（总龙头/中军/跟风/卡位/反包） */
@RestController
@RequestMapping("/api/dragon")
public class DragonController {

    private final ConceptService service;

    public DragonController(ConceptService service) {
        this.service = service;
    }

    @GetMapping("/{date}")
    public ApiResponse<List<Map<String, Object>>> dragons(@PathVariable String date) {
        return ApiResponse.ok(service.dragons(LocalDate.parse(date)));
    }
}
