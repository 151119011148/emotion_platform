package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.StockMinute;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockMinuteMapper extends BaseMapper<StockMinute> {

    /**
     * 一票一整天一次写回。
     *
     * <p>必须是批：一趟要写的是当日涨停＋炸板池几十只票，每只约 240 根，逐条 insert
     * 在慢网络下会把「19:12 这一趟」拖到下一小时，而分钟序列恰恰只在那天有效。
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} 而不是先删后插：同一票重跑（手工补当天）应当
     * 用新的累计值覆盖旧的，而不是留下两批——唯一键 {@code (code, trade_date, minute)}
     * 已经保证了幂等。
     */
    @Insert("<script>"
            + "INSERT INTO t_stock_minute (code, trade_date, minute, price, cum_volume_hand, cum_amount_yuan) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.code},#{r.tradeDate},#{r.minute},#{r.price},#{r.cumVolumeHand},#{r.cumAmountYuan})"
            + "</foreach>"
            + " ON DUPLICATE KEY UPDATE price=VALUES(price), cum_volume_hand=VALUES(cum_volume_hand),"
            + " cum_amount_yuan=VALUES(cum_amount_yuan)"
            + "</script>")
    int upsertBatch(@Param("rows") List<StockMinute> rows);
}
