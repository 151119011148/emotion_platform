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
     */
    @Select("SELECT id, code, name, market, board FROM t_stock "
            + "WHERE code LIKE CONCAT(#{kw}, '%') OR name LIKE CONCAT('%', #{kw}, '%') "
            + "ORDER BY (code = #{kw}) DESC, LENGTH(code) ASC LIMIT 12")
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
