package com.emotion.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.DailyBar;

public interface DailyBarMapper extends BaseMapper<DailyBar> {

    /**
     * 整段写回。用 {@code ON DUPLICATE KEY UPDATE} 而不是先删后插：
     * 同一个窗口被不同调用方重叠拉取是常态（阵眼跨度和监管期经常压在同一段上），
     * 先删后插会让一次拉取把别人刚写进去的行删掉，读的人在这一瞬间看到空窗。
     */
    @Insert("<script>"
            + "INSERT INTO t_daily_bar (symbol, trade_date, open_price, close_price, high_price, low_price, fq_version) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.symbol},#{r.tradeDate},#{r.openPrice},#{r.closePrice},#{r.highPrice},#{r.lowPrice},#{r.fqVersion})"
            + "</foreach> "
            + "ON DUPLICATE KEY UPDATE "
            + "open_price = VALUES(open_price), close_price = VALUES(close_price), "
            + "high_price = VALUES(high_price), low_price = VALUES(low_price), "
            + "fq_version = VALUES(fq_version)"
            + "</script>")
    int upsertBatch(@Param("rows") List<DailyBar> rows);

    /** 一段日 K，按日期升序；含 lead 段，供调用方重算首日涨幅。 */
    @org.apache.ibatis.annotations.Select(
            "SELECT id, symbol, trade_date, open_price, close_price, high_price, low_price, fq_version, updated_at "
            + "FROM t_daily_bar WHERE symbol = #{symbol} AND trade_date BETWEEN #{from} AND #{to} "
            + "ORDER BY trade_date")
    List<DailyBar> selectRange(@Param("symbol") String symbol,
                               @Param("from") java.time.LocalDate from,
                               @Param("to") java.time.LocalDate to);

    /** 这只票的日 K 全清：除权导致旧价失效时用，下次查询自然回源重写。 */
    @org.apache.ibatis.annotations.Delete("DELETE FROM t_daily_bar WHERE symbol = #{symbol}")
    int deleteBySymbol(@Param("symbol") String symbol);
}
