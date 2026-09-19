package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.IndustrySnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code t_industry_daily_snapshot} 的读写口。读侧是那个现做的聚合 SELECT（T5 的地基），
 * 写侧走"先删后插"的整日重建——和 {@code t_market_stock} / {@code t_index_close} 同一族约定。
 */
public interface IndustrySnapshotMapper extends BaseMapper<IndustrySnapshot> {

    /**
     * 当日涨停池按「通达信二级行业」聚合出板块快照。
     *
     * <p>行业不再用东财 hybk（t_market_stock.industry），改经 {@code t_industry_stock}（个股→通达信
     * 二级行业映射，来源海王星 tdxhy.cfg + incon.dat #TDXNHY）关联到二级行业名称后分组。涨停池里
     * 没映射到的个股归入「未分类」而不丢行。大面来自炸板池（big_loss 只在炸板池置位），而 zt 相关
     * 来自涨停池，所以仍是两张子查询 LEFT JOIN——涨停池里的行 big_loss 恒为 0，直接 SUM 捞不出板块真的大面数。
     */
    @Select("<script>"
            + "SELECT i.industry AS industry,"
            + "       i.zt_count   AS ztCount,"
            + "       i.max_board  AS maxBoard,"
            + "       i.seal_sum   AS sealSum,"
            + "       i.yizi_cnt   AS yiziCnt,"
            + "       i.tier_levels AS tierLevels,"
            + "       COALESCE(b.big_loss_cnt,0) AS bigLossCnt "
            + "FROM ("
            + "  SELECT COALESCE(tis.industry_name,'未分类') AS industry,"
            + "         COUNT(*) AS zt_count,"
            + "         MAX(ms.consecutive) AS max_board,"
            + "         COALESCE(SUM(ms.seal_amount),0) AS seal_sum,"
            + "         SUM(CASE WHEN ms.first_seal_time&lt;=93000 AND COALESCE(ms.break_count,0)=0 THEN 1 ELSE 0 END) AS yizi_cnt,"
            + "         GROUP_CONCAT(DISTINCT ms.consecutive ORDER BY ms.consecutive DESC) AS tier_levels "
            + "  FROM t_market_stock ms "
            + "  LEFT JOIN t_industry_stock tis ON tis.code = ms.code "
            + "  WHERE ms.trade_date=#{date} AND ms.pool='ZT' "
            + "  GROUP BY COALESCE(tis.industry_name,'未分类')"
            + ") i "
            + "LEFT JOIN ("
            + "  SELECT COALESCE(tis.industry_name,'未分类') AS industry, COUNT(*) AS big_loss_cnt "
            + "  FROM t_market_stock ms "
            + "  LEFT JOIN t_industry_stock tis ON tis.code = ms.code "
            + "  WHERE ms.trade_date=#{date} AND ms.pool='ZB' AND ms.big_loss=1 "
            + "  GROUP BY COALESCE(tis.industry_name,'未分类')"
            + ") b ON b.industry=i.industry "
            + "ORDER BY i.zt_count DESC, i.max_board DESC"
            + "</script>")
    List<IndustrySnapshot> aggregate(LocalDate date);

    /** 一行一板块，多行 VALUES 一次写一天，避免几百个 industry 逐条往返。 */
    @Insert("<script>"
            + "INSERT INTO t_industry_daily_snapshot "
            + "(trade_date, industry, zt_count, max_board, seal_sum, yizi_cnt, big_loss_cnt, tier_levels) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.tradeDate},#{r.industry},#{r.ztCount},#{r.maxBoard},#{r.sealSum},#{r.yiziCnt},#{r.bigLossCnt},#{r.tierLevels})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<IndustrySnapshot> rows);
}