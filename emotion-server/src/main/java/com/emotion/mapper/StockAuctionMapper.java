package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.StockAuction;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockAuctionMapper extends BaseMapper<StockAuction> {

    /**
     * 一天一批竞价快照，一次写回。
     *
     * <p>重跑同一天是允许的（手工补一趟），唯一键 {@code (code, trade_date)} 让后写的覆盖先写的；
     * 覆盖是对的——只要还在 09:30 之前，越晚抓的那一趟越接近竞价最终定格。
     * 而一旦过了 09:30，抓到的就不再是竞价量，这时不该覆盖，所以 {@code AuctionSnapshotTask}
     * 自己在过点之后就 skip，而不是靠这条 SQL 去分辨。
     */
    @Insert("<script>"
            + "INSERT INTO t_stock_auction (code, name, trade_date, auction_price, prev_close, "
            + "volume_hand, amount_wan, bid1_price, bid1_hand, ask1_price, ask1_hand, "
            + "limit_up_price, captured_at, snapshot_raw) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.code},#{r.name},#{r.tradeDate},#{r.auctionPrice},#{r.prevClose},#{r.volumeHand},"
            + "#{r.amountWan},#{r.bid1Price},#{r.bid1Hand},#{r.ask1Price},#{r.ask1Hand},"
            + "#{r.limitUpPrice},#{r.capturedAt},#{r.snapshotRaw})"
            + "</foreach>"
            + " ON DUPLICATE KEY UPDATE name=VALUES(name), auction_price=VALUES(auction_price), "
            + "prev_close=VALUES(prev_close), volume_hand=VALUES(volume_hand), amount_wan=VALUES(amount_wan), "
            + "bid1_price=VALUES(bid1_price), bid1_hand=VALUES(bid1_hand), ask1_price=VALUES(ask1_price), "
            + "ask1_hand=VALUES(ask1_hand), limit_up_price=VALUES(limit_up_price), "
            + "captured_at=VALUES(captured_at), snapshot_raw=VALUES(snapshot_raw)"
            + "</script>")
    int upsertBatch(@Param("rows") List<StockAuction> rows);
}
