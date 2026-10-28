package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.StockMinute;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
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

    /**
     * 一只票一天的分钟序列，升序。
     *
     * <p>读侧只认库：分时图是「看这一天怎么走的」，不是「取最新价」，
     * 所以这里绝不在缺数时回源——上游只给当天，回源也拿不到这一天，只会把读接口挂在上游的超时上。
     */
    @Select("SELECT id, code, trade_date, minute, price, cum_volume_hand, cum_amount_yuan, created_at "
            + "FROM t_stock_minute WHERE code = #{code} AND trade_date = #{date} ORDER BY minute")
    List<StockMinute> selectDay(@Param("code") String code, @Param("date") LocalDate date);

    /**
     * 那天库里一共存了几只票的分时。零与非零要分开回答：
     * 「这一天全市场都没抓」是永久缺失，「抓了 60 只但没有它」是名单没覆盖，两句不是一回事。
     */
    @Select("SELECT COUNT(DISTINCT code) FROM t_stock_minute WHERE trade_date = #{date}")
    int countCodesOn(@Param("date") LocalDate date);

    /** 库里存过分时的最早一天，用来把「早于本功能上线」说得有凭据。 */
    @Select("SELECT MIN(trade_date) FROM t_stock_minute")
    LocalDate firstStoredDate();
}
