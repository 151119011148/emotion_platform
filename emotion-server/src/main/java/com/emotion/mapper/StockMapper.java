package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.Stock;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockMapper extends BaseMapper<Stock> {

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
