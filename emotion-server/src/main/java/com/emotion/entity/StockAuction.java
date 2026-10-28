package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 集合竞价定格快照：09:26 那一刻的盘口，一票一天一行。
 *
 * <p>为什么要专门在早上抓一趟，而不是从收盘数据里推：09:30 之前只有集合竞价成交，
 * 所以那一刻的累计成交量就是<strong>竞价成交量</strong>、现价就是开盘价、五档上是
 * <strong>还没被撮合掉的竞买/竞卖量</strong>。这三样过了 09:30 就永久变形——连续竞价的量
 * 会盖掉累计量，未匹配量会被新单冲散，收盘后再请求同一个接口只能拿到当天的最终值。
 *
 * <p>「竞价即涨停」（一字板的成因）判的是 {@code auctionPrice == limitUpPrice}；
 * 再配上 {@code bid1Hand}（未匹配买量）和 {@code ask1Hand}（未匹配卖量），
 * 就能把「封死」和「勉强开在涨停」分开——现有日级数据里只有 first_seal_time 一个时刻，
 * 分不出这两者。
 *
 * <p>{@code snapshotRaw} 不是冗余：这一批字段位是拿收盘时的响应核对过的，
 * 而 09:26 那一刻各段的含义还没实测过（要等排班自己跑一趟）。真解释错了，
 * 有原文就能重算，不必再等一个早上才能发现。
 */
@Data
@TableName("t_stock_auction")
public class StockAuction {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private LocalDate tradeDate;
    /** 竞价成交价 = 09:26 盘口的现价，也就是当天开盘价。 */
    private BigDecimal auctionPrice;
    private BigDecimal prevClose;
    /** 竞价成交量(手)。 */
    private Long volumeHand;
    /** 竞价成交额(万元)，源原值的单位。 */
    private BigDecimal amountWan;
    private BigDecimal bid1Price;
    /** 未匹配买量(手)。 */
    private Long bid1Hand;
    private BigDecimal ask1Price;
    /** 未匹配卖量(手)。 */
    private Long ask1Hand;
    /** 当日涨停价：与 auctionPrice 相等即「竞价封死」。 */
    private BigDecimal limitUpPrice;
    /** 实际发出请求的时刻，用来事后确认这一行到底是几点几分抓的。 */
    private LocalDateTime capturedAt;
    private String snapshotRaw;
    private LocalDateTime createdAt;
}
