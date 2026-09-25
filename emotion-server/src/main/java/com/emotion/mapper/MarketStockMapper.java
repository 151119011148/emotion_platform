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
            + "consecutive, break_count, change_pct, close_price, limit_price, pullback_pct, big_loss, "
            + "seal_amount, amount, first_seal_time, last_seal_time, float_mv, turnover_rate) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.tradeDate},#{r.code},#{r.name},#{r.pool},#{r.market},#{r.industry},"
            + "#{r.consecutive},#{r.breakCount},#{r.changePct},#{r.closePrice},#{r.limitPrice},"
            + "#{r.pullbackPct},#{r.bigLoss},#{r.sealAmount},#{r.amount},#{r.firstSealTime},#{r.lastSealTime},#{r.floatMv},#{r.turnoverRate})"
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

    /** Latest trade_date strictly before date that has pool detail; serves as yesterday limit-up pool for day-over-day ladder matching. */
    @Select("SELECT DISTINCT trade_date FROM t_market_stock WHERE trade_date < #{date} ORDER BY trade_date DESC LIMIT 1")
    LocalDate prevDetailDate(@Param("date") LocalDate date);

    /** [from,to] 区间内有盘面明细的交易日，升序；题材持续天数回看用它定窗口内每日是否有数据。 */
    @Select("SELECT DISTINCT trade_date FROM t_market_stock "
            + "WHERE trade_date <= #{to} AND trade_date >= #{from} ORDER BY trade_date")
    List<LocalDate> listDetailDatesBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 日期区间内每天的最高连板高度及打到这个高度的个股（涨停池）。供连板高度曲线用，一条 SQL 出 N 天数据，
     * 避免逐日调 snapshot 的 N 次往返。
     *
     * <p>一天<b>可能多行</b>：并列最高板是常态（最高板仅 2 板的那天能并列几十只），
     * 按日收敛成曲线上的一个点在 {@link com.emotion.service.TiantiService#heightRange} 做。
     */
    @Select("SELECT t.trade_date AS tradeDate, t.consecutive AS maxHeight, t.code AS code, t.name AS name "
            + "FROM t_market_stock t "
            + "INNER JOIN (SELECT trade_date, MAX(consecutive) AS max_c "
            + "  FROM t_market_stock WHERE pool='ZT' AND trade_date BETWEEN #{from} AND #{to} "
            + "  GROUP BY trade_date) m ON t.trade_date = m.trade_date AND t.consecutive = m.max_c "
            + "WHERE t.pool='ZT' AND t.trade_date BETWEEN #{from} AND #{to} "
            + "ORDER BY t.trade_date")
    List<MaxBoardRow> listMaxBoardRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 区间内<b>每一只</b>涨停个股的逐日连板数，用于反推「启动日」判伴生。
     *
     * <p>破壁要求新龙 {@code 启动日 > 旧龙断板日}，只看每日最高板的那份名单判不出来：
     * 断板次日接棒创新高的往往是上一周期里就在场的伴生票（反包尾巴），不算破壁。
     *
     * <p>一天三个池 100~200 只、这里只取 ZT 池，90 日窗口约万行，调用方别传开区间。
     */
    @Select("SELECT trade_date AS tradeDate, code AS code, name AS name, consecutive AS consecutive "
            + "FROM t_market_stock WHERE pool='ZT' AND trade_date BETWEEN #{from} AND #{to} "
            + "ORDER BY trade_date, code")
    List<BoardRow> listBoardSeries(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 逐票连板轨迹行：日期 / 代码 / 名称 / 当日连板数。 */
    @lombok.Data
    class BoardRow {
        LocalDate tradeDate;
        String code;
        String name;
        Integer consecutive;
    }

    /** 每日最高板行：日期 / 板高 / 代码 / 名称。 */
    @lombok.Data
    class MaxBoardRow {
        LocalDate tradeDate;
        Integer maxHeight;
        String code;
        String name;
    }
}
