package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.Stock;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface StockMapper extends BaseMapper<Stock> {

    /**
     * 模糊搜索股票：代码前缀命中或名称含 kw（拼音/汉字都可）。按代码是否精确、代码长度升序，
     * 保证输入 6 位完整代码时精确命中排最前。
     *
     * <p>字典 t_stock 之外再兜一层 t_market_stock：字典靠 {@code /api/market/stock-dict/sync} 灌进去，
     * 新建的库在第一次同步之前它是空的，只查字典会让选股下拉永远 0 条（看着像搜索功能坏了）。
     * 行情明细表里已落过的代码名称（近期拉过的三池）按 code 去重后一起参与匹配，
     * 字典优先（MIN(id) 只在字典有行时才非 NULL），两边都命中只出一条。
     */
    @Select("SELECT MIN(u.id) AS id, u.code AS code, MIN(u.name) AS name, "
            + "MIN(u.market) AS market, MIN(u.board) AS board FROM ( "
            + "  SELECT s.id AS id, s.code AS code, s.name AS name, s.market AS market, s.board AS board "
            + "  FROM t_stock s "
            + "  WHERE s.code LIKE CONCAT(#{kw}, '%') OR s.name LIKE CONCAT('%', #{kw}, '%') "
            + "  UNION ALL "
            + "  SELECT NULL AS id, m.code AS code, m.name AS name, m.market AS market, NULL AS board "
            + "  FROM (SELECT code, MAX(name) AS name, MAX(market) AS market FROM t_market_stock GROUP BY code) m "
            + "  WHERE m.code LIKE CONCAT(#{kw}, '%') OR m.name LIKE CONCAT('%', #{kw}, '%') "
            + ") u GROUP BY u.code "
            + "ORDER BY (u.code = #{kw}) DESC, LENGTH(u.code) ASC, u.code ASC LIMIT 12")
    List<Stock> search(@Param("kw") String kw);

    /**
     * 按 code 唯一键 upsert。
     *
     * 只做"有则改、无则加"，绝不整表清空重灌：改名（ST 前缀摘掉那种）需要被覆盖，
     * 而清空重灌会在拉取中途失败时把一张好表换成半张。
     */
    @Insert("<script>"
            + "INSERT INTO t_stock (code, name, market, board) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.code},#{r.name},#{r.market},#{r.board})"
            + "</foreach>"
            + " ON DUPLICATE KEY UPDATE name=VALUES(name), market=VALUES(market), board=VALUES(board)"
            + "</script>")
    int upsertBatch(@Param("rows") List<Stock> rows);
}
