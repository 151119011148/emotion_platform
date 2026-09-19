package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.IndustryStock;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.IndustryStockMapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把个股归属到「通达信二级行业」的判定器（板块排名/D2 雷达切源）。
 *
 * <p>{@code t_market_stock.industry} 现在仍是东财 hybk；要按通达信二级行业做板块排名/主线判定，
 * 但不能去改 fetch 层。这里在聚合读侧统一重映射：把加载出来的 {@link MarketStock} 的 industry
 * 字段改成 {@code t_industry_stock} 里的二级行业名。映射变化慢，整表一次入内存缓存，不改任务就不重查。
 *
 * <p>注意：变更分类口径会让按东财行业名手工登记的题材/主线（t_theme.name / t_mainline_mark）无法
 * 再精确匹配二级行业名——这是切换口径的固有代价，登记需按新行业名重做。
 */
@Service
public class IndustryClassifyService {

    private final IndustryStockMapper mapper;
    private volatile Map<String, String> code2Industry;

    public IndustryClassifyService(IndustryStockMapper mapper) {
        this.mapper = mapper;
    }

    private Map<String, String> dict() {
        Map<String, String> m = this.code2Industry;
        if (m == null) {
            synchronized (this) {
                m = this.code2Industry;
                if (m == null) {
                    Map<String, String> tmp = new HashMap<>();
                    for (IndustryStock r : mapper.selectList(new LambdaQueryWrapper<>())) {
                        if (r.getCode() != null && r.getIndustryName() != null
                                && !r.getIndustryName().isEmpty()) {
                            tmp.put(r.getCode(), r.getIndustryName());
                        }
                    }
                    this.code2Industry = Collections.unmodifiableMap(tmp);
                    m = this.code2Industry;
                }
            }
        }
        return m;
    }

    /**
     * 把每只股票的 industry 重映射成通达信二级行业名。无映射的股票保留原值
     * （多为未分类/B股，不强行抹掉东财字段，避免丢行）。
     */
    public void apply(List<MarketStock> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<String, String> d = dict();
        for (MarketStock r : rows) {
            if (r.getCode() == null) {
                continue;
            }
            String ind = d.get(r.getCode());
            if (ind != null) {
                r.setIndustry(ind);
            }
        }
    }

    /** 个股 → 二级行业名（无映射=null）。 */
    public String of(String code) {
        return code == null ? null : dict().get(code);
    }
}