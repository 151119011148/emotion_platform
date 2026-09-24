package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.NodeDetect;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/** 节点识别结果。 */
public interface NodeDetectMapper extends BaseMapper<NodeDetect> {

    /**
     * 某个交易日的有效节点：人工取消过的（confirmed = -1）不算。
     *
     * <p>「取消」做成标记而不是物理删除，是因为节点判断本身是可争论的——
     * 留着被否掉的那条，过一阵回头看「当时为什么判它不算」，比一片空白有用。
     */
    @Select("SELECT * FROM t_node_detect WHERE trade_date = #{date} AND IFNULL(confirmed,0) >= 0 "
            + "ORDER BY node_type")
    List<NodeDetect> listEffectiveOn(@Param("date") LocalDate date);

    /**
     * 区间内各节点类型的历史触发次数。
     *
     * <p>这是 PRD 反复强调的「近 20 日触发频次」面板的数据源。理由是 v1.0 的教训：
     * 阈值写死之后规则会悄悄变成死分支，而界面上完全看不出来——只有把命中次数摆出来，
     * 「这条规则今年一次都没触发」才会被人看见。
     */
    @Select("SELECT node_type, COUNT(*) AS cnt FROM t_node_detect "
            + "WHERE trade_date >= #{from} AND trade_date <= #{to} AND IFNULL(confirmed,0) >= 0 "
            + "GROUP BY node_type")
    List<java.util.Map<String, Object>> countByType(@Param("from") LocalDate from,
                                                    @Param("to") LocalDate to);
}
