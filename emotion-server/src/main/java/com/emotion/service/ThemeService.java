package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.Theme;
import com.emotion.entity.LeadingStock;
import com.emotion.mapper.ThemeMapper;
import com.emotion.mapper.LeadingStockMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ThemeService {

    private final ThemeMapper themeMapper;
    private final LeadingStockMapper leadingStockMapper;

    public ThemeService(ThemeMapper themeMapper, LeadingStockMapper leadingStockMapper) {
        this.themeMapper = themeMapper;
        this.leadingStockMapper = leadingStockMapper;
    }

    public List<Theme> listByUser(Long userId) {
        return themeMapper.selectList(
                new LambdaQueryWrapper<Theme>()
                        .eq(Theme::getUserId, userId)
                        .orderByDesc(Theme::getCreatedAt));
    }

    public Theme create(Long userId, Theme theme) {
        theme.setUserId(userId);
        themeMapper.insert(theme);
        return theme;
    }

    public Theme update(Long userId, Long id, Theme theme) {
        Theme existing = themeMapper.selectOne(
                new LambdaQueryWrapper<Theme>()
                        .eq(Theme::getId, id)
                        .eq(Theme::getUserId, userId));
        if (existing == null) throw new RuntimeException("题材不存在");

        if (theme.getName() != null) existing.setName(theme.getName());
        if (theme.getStatus() != null) existing.setStatus(theme.getStatus());
        if (theme.getStrength() != null) existing.setStrength(theme.getStrength());
        if (theme.getCatalystHardness() != null) existing.setCatalystHardness(theme.getCatalystHardness());
        themeMapper.updateById(existing);
        return existing;
    }

    public List<LeadingStock> listStocks(Long userId, Long themeId) {
        return leadingStockMapper.selectList(
                new LambdaQueryWrapper<LeadingStock>()
                        .eq(LeadingStock::getUserId, userId)
                        .eq(LeadingStock::getThemeId, themeId));
    }

    public LeadingStock addStock(Long userId, Long themeId, LeadingStock stock) {
        stock.setUserId(userId);
        stock.setThemeId(themeId);
        leadingStockMapper.insert(stock);
        return stock;
    }
}
