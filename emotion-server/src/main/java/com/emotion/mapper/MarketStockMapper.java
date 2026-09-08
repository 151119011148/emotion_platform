package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.MarketStock;
import com.emotion.market.PoolCounts;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface MarketStockMapper extends BaseMapper<MarketStock> {

    /**
     * 多行 VALUES 一次写一天，而不是逐只 insert：一天三个池 100~200 只，
     * 逐条往返在慢网络下会让"拉取行情"从几秒变成几十秒。
     */
    @Insert("<script>"
            + "INSERT INTO t_market_stock (trade_date, code, name, pool, market, industry, "
            + "consecutive, break_count, change_pct, close_price, limit_price, pullback_pct, big_loss) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.tradeDate},#{r.code},#{r.name},#{r.pool},#{r.market},#{r.industry},"
            + "#{r.consecutive},#{r.breakCount},#{r.changePct},#{r.closePrice},#{r.limitPrice},"
            + "#{r.pullbackPct},#{r.bigLoss})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<MarketStock> rows);

    /**
     * 一天三个池的家数聚合，供第 4 维的家数封板率与回封率使用。
     *
     * <p>IFNULL 不是装饰：那天一行明细都没有时 SUM 返回 NULL，直接映射进 int 会得到 0 还是抛异常
     * 取决于驱动，而"没有明细"必须是 {@link com.emotion.market.PoolCounts#isEmpty()} 说出来的话。
     */
    @Select("SELECT IFNULL(SUM(pool='ZT'),0) AS zt_count, IFNULL(SUM(pool='ZB'),0) AS zb_count, "
            + "IFNULL(SUM(pool='ZT' AND IFNULL(break_count,0)>0),0) AS reseal_count "
            + "FROM t_market_stock WHERE trade_date=#{date}")
    PoolCounts countPools(@Param("date") LocalDate date);

    /**
     * 明细表里严格晚于 {@code date} 的第一个交易日，节点复算用它定 T+1。
     *
     * <p>不打指数的日 K：一来这是纯本地一条 SQL，二来"那天没有明细"和"那天判不了晋级"本来就是
     * 同一件事——拿一个没有明细的日子当 T+1，只会把结论报成"晋级 0 只"这种看着像数、其实是缺数的话。
     */
    @Select("SELECT DISTINCT trade_date FROM t_market_stock WHERE trade_date > #{date} "
            + "ORDER BY trade_date LIMIT 1")
    LocalDate nextDetailDate(@Param("date") LocalDate date);
}
