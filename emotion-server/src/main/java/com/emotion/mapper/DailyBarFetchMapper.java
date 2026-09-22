package com.emotion.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.DailyBarFetch;

public interface DailyBarFetchMapper extends BaseMapper<DailyBarFetch> {

    /** 记一次拉取；同一区间重复拉只刷新时间和根数。 */
    @Insert("INSERT INTO t_daily_bar_fetch (symbol, start_date, end_date, bar_count, fq_version, fetched_at) VALUES "
            + "(#{symbol},#{startDate},#{endDate},#{barCount},#{fqVersion},#{fetchedAt}) "
            + "ON DUPLICATE KEY UPDATE bar_count = VALUES(bar_count), "
            + "fq_version = VALUES(fq_version), fetched_at = VALUES(fetched_at)")
    int upsert(DailyBarFetch row);

    /**
     * 找一条能盖住 [from,to] 且还在保鲜期内的拉取记录。
     * 取时间最新的一条：同一区间可能被不同保鲜状态的记录覆盖过，看最新那次最准。
     */
    @Select("SELECT id, symbol, start_date, end_date, bar_count, fq_version, fetched_at FROM t_daily_bar_fetch "
            + "WHERE symbol = #{symbol} AND start_date <= #{from} AND end_date >= #{to} "
            + "AND fetched_at >= #{notBefore} ORDER BY fetched_at DESC LIMIT 1")
    DailyBarFetch findCovering(@Param("symbol") String symbol,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to,
                               @Param("notBefore") LocalDateTime notBefore);

    /** 某只票的全部拉取记录，按时间倒序（排查用：看它被拉过哪些区间）。 */
    @Select("SELECT id, symbol, start_date, end_date, bar_count, fq_version, fetched_at FROM t_daily_bar_fetch "
            + "WHERE symbol = #{symbol} ORDER BY fetched_at DESC")
    List<DailyBarFetch> listBySymbol(@Param("symbol") String symbol);

    /** 清掉一只票的覆盖记录（强制重拉用）；日 K 行留着，下次回源会整体覆盖写。 */
    @org.apache.ibatis.annotations.Delete("DELETE FROM t_daily_bar_fetch WHERE symbol = #{symbol}")
    int deleteBySymbol(@Param("symbol") String symbol);
}
