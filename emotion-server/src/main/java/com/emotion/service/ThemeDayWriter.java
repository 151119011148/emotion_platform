package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.LeadingStock;
import com.emotion.entity.Theme;
import com.emotion.mapper.LeadingStockMapper;
import com.emotion.mapper.ThemeMapper;
import com.emotion.util.ReviewDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 题材行落到 {@code t_theme} + {@code t_leading_stock}。
 *
 * <p><b>这两张表没有日粒度</b>：它们记的是"当前这条题材走到哪一段"，按 (user, 题材名) upsert，
 * 最后一次导入赢。想要强度随时间那条曲线，得另建 t_theme_day——这件事留给你定，不在这里替你建。
 *
 * <p>所以这里刻意不做"按日删除重建"（那是 {@code t_position} 那族的规矩）：
 * 题材不是那一天独有的行，删了会把两周前起的题材一起抹掉。
 */
@Service
public class ThemeDayWriter {

    private static final Logger log = LoggerFactory.getLogger(ThemeDayWriter.class);
    /** 复盘 md 里的题材龙头一律登记成总龙头——04 篇里只有这一档是每天必然重判的。 */
    private static final String ROLE_LEADER = "总龙头";

    private final ThemeMapper themeMapper;
    private final LeadingStockMapper leadingStockMapper;

    public ThemeDayWriter(ThemeMapper themeMapper, LeadingStockMapper leadingStockMapper) {
        this.themeMapper = themeMapper;
        this.leadingStockMapper = leadingStockMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public int apply(Long userId, LocalDate date, List<ReviewDoc.ThemeRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int applied = 0;
        for (ReviewDoc.ThemeRow row : rows) {
            Theme theme = themeMapper.selectOne(new LambdaQueryWrapper<Theme>()
                    .eq(Theme::getUserId, userId)
                    .eq(Theme::getName, row.getTheme())
                    .orderByAsc(Theme::getId)
                    .last("LIMIT 1"));
            if (theme == null) {
                theme = new Theme();
                theme.setUserId(userId);
                theme.setName(row.getTheme());
                theme.setStartDate(date);
            }
            theme.setStatus(row.getStatus());
            theme.setStrength(row.getStrength());
            if (theme.getId() == null) {
                themeMapper.insert(theme);
            } else {
                themeMapper.updateById(theme);
            }
            applied++;

            if (row.getLeaderCode() != null) {
                applyLeader(userId, date, theme.getId(), row);
            }
        }
        log.info("{} 题材写入 {} 条（t_theme 无日粒度，最后一次导入为准）", date, applied);
        return applied;
    }

    private void applyLeader(Long userId, LocalDate date, Long themeId, ReviewDoc.ThemeRow row) {
        LeadingStock leader = leadingStockMapper.selectList(new LambdaQueryWrapper<LeadingStock>()
                        .eq(LeadingStock::getUserId, userId)
                        .eq(LeadingStock::getThemeId, themeId)
                        .eq(LeadingStock::getName, row.getLeaderName()))
                .stream().findFirst().orElse(null);
        if (leader == null) {
            leader = new LeadingStock();
            leader.setUserId(userId);
            leader.setThemeId(themeId);
            leader.setName(row.getLeaderName());
            leader.setStartDate(date);
            leader.setRole(ROLE_LEADER);
            leader.setStatus(row.getStatus());
            leadingStockMapper.insert(leader);
        } else {
            // 只补 status 和归属：最高连板由盘面明细说话，不拿复盘里的字盖掉它。
            leader.setThemeId(themeId);
            leader.setStatus(row.getStatus());
            if (isBlank(leader.getRole())) {
                leader.setRole(ROLE_LEADER);
            }
            leadingStockMapper.updateById(leader);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
