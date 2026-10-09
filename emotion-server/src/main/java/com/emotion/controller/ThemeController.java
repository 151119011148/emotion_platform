package com.emotion.controller;

import com.emotion.entity.Theme;
import com.emotion.entity.LeadingStock;
import com.emotion.service.ThemeService;
import com.emotion.vo.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 题材台账：全平台共享一份，不再按账号各记一套；谁能改由 {@code SuperAdminWriteInterceptor} 统一挡。
 */
@RestController
@RequestMapping("/api/themes")
public class ThemeController {

    private final ThemeService themeService;

    public ThemeController(ThemeService themeService) {
        this.themeService = themeService;
    }

    @GetMapping
    public ApiResponse<List<Theme>> list() {
        return ApiResponse.ok(themeService.listAll());
    }

    @PostMapping
    public ApiResponse<Theme> create(@RequestBody Theme theme) {
        return ApiResponse.ok(themeService.create(theme));
    }

    @PutMapping("/{id}")
    public ApiResponse<Theme> update(@PathVariable Long id, @RequestBody Theme theme) {
        return ApiResponse.ok(themeService.update(id, theme));
    }

    @GetMapping("/{themeId}/stocks")
    public ApiResponse<List<LeadingStock>> listStocks(@PathVariable Long themeId) {
        return ApiResponse.ok(themeService.listStocks(themeId));
    }

    @PostMapping("/{themeId}/stocks")
    public ApiResponse<LeadingStock> addStock(@PathVariable Long themeId, @RequestBody LeadingStock stock) {
        return ApiResponse.ok(themeService.addStock(themeId, stock));
    }
}
