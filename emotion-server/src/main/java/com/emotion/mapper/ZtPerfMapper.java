package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.ZtPerf;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ZtPerfMapper extends BaseMapper<ZtPerf> {

    /** 一天几十只（昨涨停全池），同样多行 VALUES 一次写完。 */
    @Insert("<script>"
            + "INSERT INTO t_zt_perf (trade_date, code, name, prev_consecutive, change_pct, source) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.tradeDate},#{r.code},#{r.name},#{r.prevConsecutive},#{r.changePct},#{r.source})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<ZtPerf> rows);
}
