package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.IndexClose;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface IndexCloseMapper extends BaseMapper<IndexClose> {

    @Insert("<script>"
            + "INSERT INTO t_index_close (trade_date, index_code, index_name, close_price, change_pct) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.tradeDate},#{r.indexCode},#{r.indexName},#{r.closePrice},#{r.changePct})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<IndexClose> rows);
}
