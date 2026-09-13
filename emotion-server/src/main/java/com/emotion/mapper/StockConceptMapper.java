package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.StockConcept;
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
}