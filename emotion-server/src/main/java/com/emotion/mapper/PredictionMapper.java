package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.Prediction;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PredictionMapper extends BaseMapper<Prediction> {

    @Insert("<script>"
            + "INSERT INTO t_prediction (user_id, trade_date, kind, name, prob, condition_text, "
            + "result, result_note) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.userId},#{r.tradeDate},#{r.kind},#{r.name},#{r.prob},"
            + "#{r.conditionText},#{r.result},#{r.resultNote})"
            + "</foreach></script>")
    int insertBatch(@Param("rows") List<Prediction> rows);
}
