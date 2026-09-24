package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.StockConcept;
import com.emotion.vo.ThemeTagVO;
import com.emotion.vo.TopicHeat;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code t_stock_concept} 的读写口。写侧是"先清空再整盘重建"的建索引，
 * 读侧是当日涨停池按概念聚合出题材热度（D2 题材 Top5）。
 */
public interface StockConceptMapper extends BaseMapper<StockConcept> {

    /**
     * 当日涨停池按「概念」聚合出题材热度：以概念索引 join 涨停池，
     * 每概念算出涨停家数与最高连板，按家数降序、并列按最高连板次排序。
     *
     * <p>一票多概念会重复计入多个题材，口径与 PRD 一致；未建索引时自然出空。
     */
    @Select("<script>"
            + "SELECT c.concept AS name,"
            + "       COUNT(s.code) AS ztCount,"
            + "       COALESCE(MAX(s.consecutive),0) AS maxBoard "
            + "FROM t_stock_concept c "
            + "JOIN t_market_stock s ON s.code=c.code "
            + "WHERE s.trade_date=#{date} AND s.pool='ZT' "
            + "GROUP BY c.concept_code, c.concept "
            + "ORDER BY ztCount DESC, maxBoard DESC "
            + "LIMIT #{topN}"
            + "</script>")
    List<TopicHeat> aggregateZtTopics(@Param("date") LocalDate date, @Param("topN") int topN);

    /** 整块重建索引：一行一条「股票-概念」，多行 VALUES 一次写一批。 */
    @Insert("<script>"
            + "INSERT INTO t_stock_concept (code, concept_code, concept, name) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.code},#{r.conceptCode},#{r.concept},#{r.name})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<StockConcept> rows);

    /**
     * 批量取这些票所属的通达信题材，并带上「当日该题材的涨停家数」。
     *
     * <p>热度用 {@code LEFT JOIN} 而不是内连接：当日该题材只有这一只票涨停时，
     * 家数就是 1；整个题材当日的票都不在涨停池里时是 0——两种都可能是真答案，
     * 内连接会把第二类直接丢掉（表现成「这只票没题材」），所以这里不能内连接。
     *
     * <p>排序在 SQL 里做：同一只票内按涨停家数降序，service 直接顺序下发即可。
     * 返回的是「股票-题材」扁平行（一只票多行），{@link ThemeTagVO#getCode()} 是分组键。
     */
    @Select("<script>"
            + "SELECT c.code AS code,"
            + "       c.concept_code AS name,"
            + "       c.concept AS fullName,"
            + "       COALESCE(b.index_code, '') AS indexCode,"
            + "       COALESCE(h.ztCount, 0) AS ztCount "
            + "FROM t_stock_concept c "
            + "LEFT JOIN t_concept_board b ON b.board_code = c.concept_code "
            + "LEFT JOIN (SELECT c2.concept_code AS cc, COUNT(DISTINCT s.code) AS ztCount "
            + "             FROM t_stock_concept c2 "
            + "             JOIN t_market_stock s ON s.code = c2.code "
            + "            WHERE s.trade_date = #{date} AND s.pool = 'ZT' "
            + "            GROUP BY c2.concept_code) h ON h.cc = c.concept_code "
            + "WHERE c.code IN "
            + "<foreach collection='codes' item='x' open='(' separator=',' close=')'>#{x}</foreach> "
            + "ORDER BY c.code, ztCount DESC, c.concept_code"
            + "</script>")
    List<ThemeTagVO> listThemesByCodes(@Param("codes") List<String> codes,
                                       @Param("date") LocalDate date);
}