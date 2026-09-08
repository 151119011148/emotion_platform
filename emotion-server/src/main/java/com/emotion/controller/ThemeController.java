package com.emotion.controller;

import com.emotion.entity.Theme;
import com.emotion.entity.LeadingStock;
import com.emotion.service.ThemeService;
import com.emotion.vo.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/themes")
public class ThemeController {

    private final ThemeService themeService;

    public ThemeController(ThemeService themeService) {
        this.themeService = themeService;
    }

    @GetMapping
    public ApiResponse<List<Theme>> list(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(themeService.listByUser(userId));
    }

    @PostMapping
    public ApiResponse<Theme> create(Authentication auth, @RequestBody Theme theme) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(themeService.create(userId, theme));
    }

    @PutMapping("/{id}")
    public ApiResponse<Theme> update(Authentication auth, @PathVariable Long id,
                                     @RequestBody Theme theme) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(themeService.update(userId, id, theme));
    }

    @GetMapping("/{themeId}/stocks")
    public ApiResponse<List<LeadingStock>> listStocks(Authentication auth,
                                                      @PathVariable Long themeId) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(themeService.listStocks(userId, themeId));
    }

    @PostMapping("/{themeId}/stocks")
    public ApiResponse<LeadingStock> addStock(Authentication auth, @PathVariable Long themeId,
                                              @RequestBody LeadingStock stock) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(themeService.addStock(userId, themeId, stock));
    }
}
