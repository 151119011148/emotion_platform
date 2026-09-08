package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.PremiumTier;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PremiumTierMapper extends BaseMapper<PremiumTier> {

    /** 一天最多 7 行，但仍按多行 VALUES 一次写完：拉一次行情要顺带落三张表，逐条往返会拖慢按钮。 */
    @Insert("<script>"
            + "INSERT INTO t_premium_tier (trade_date, board, group_key, stock_count, matched, "
            + "avg_pct, max_pct, min_pct) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.tradeDate},#{r.board},#{r.groupKey},#{r.stockCount},#{r.matched},"
            + "#{r.avgPct},#{r.maxPct},#{r.minPct})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<PremiumTier> rows);
}
