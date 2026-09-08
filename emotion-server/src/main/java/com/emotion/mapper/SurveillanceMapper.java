package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.Surveillance;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SurveillanceMapper extends BaseMapper<Surveillance> {

    /**
     * 按 (stock_code, art_code) 幂等 upsert。
     *
     * 公告会被反复查到（每次刷新窗口都重叠），upsert 让"重查"成为无副作用动作。
     * 这里不用先删后插：明细表重写是为了清掉上游已经撤掉的行，而监管事实一旦成立就不该被撤销，
     * 上游漏一页就把人家的监管期删掉，第 9 维会凭空少一只票。
     */
    @Insert("<script>"
            + "INSERT INTO t_surveillance (stock_code, stock_name, ann_date, kind, title, column_code, art_code) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.stockCode},#{r.stockName},#{r.annDate},#{r.kind},#{r.title},#{r.columnCode},#{r.artCode})"
            + "</foreach>"
            + " ON DUPLICATE KEY UPDATE stock_name=VALUES(stock_name), kind=VALUES(kind),"
            + " title=VALUES(title), column_code=VALUES(column_code)"
            + "</script>")
    int upsertBatch(@Param("rows") List<Surveillance> rows);
}
