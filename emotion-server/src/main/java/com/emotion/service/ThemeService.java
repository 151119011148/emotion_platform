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

    public List<Theme> listAll() {
        return themeMapper.selectList(
                new LambdaQueryWrapper<Theme>()
                        .orderByDesc(Theme::getCreatedAt));
    }

    public Theme create(Theme theme) {
        themeMapper.insert(theme);
        return theme;
    }

    public Theme update(Long id, Theme theme) {
        Theme existing = themeMapper.selectById(id);
        if (existing == null) throw new RuntimeException("题材不存在");

        if (theme.getName() != null) existing.setName(theme.getName());
        if (theme.getStatus() != null) existing.setStatus(theme.getStatus());
        if (theme.getStrength() != null) existing.setStrength(theme.getStrength());
        if (theme.getCatalystHardness() != null) existing.setCatalystHardness(theme.getCatalystHardness());
        themeMapper.updateById(existing);
        return existing;
    }

    public List<LeadingStock> listStocks(Long themeId) {
        return leadingStockMapper.selectList(
                new LambdaQueryWrapper<LeadingStock>()
                        .eq(LeadingStock::getThemeId, themeId));
    }

    public LeadingStock addStock(Long themeId, LeadingStock stock) {
        stock.setThemeId(themeId);
        leadingStockMapper.insert(stock);
        return stock;
    }
}
