package com.emotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一条异动监管事件：某只票在某天因某类原因被点名。
 *
 * 存事件而不是存状态——"现在还在不在监管期"是查的时候按该票自己的交易日序列数出来的，
 * 所以调窗口长度（{@link com.emotion.market.SurveillanceKind#days()}）不需要回补任何数据。
 * 公开数据，不绑 user_id。
 */
@Data
@TableName("t_surveillance")
public class Surveillance {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String stockCode;
    private String stockName;
    /** 公告日 D0。 */
    private LocalDate annDate;
    /** ZD / SEVERE / EXCH，见 {@link com.emotion.market.SurveillanceKind}。 */
    private String kind;
    private String title;
    /** 上游类目码，判据留档：换判据时能查回当初为什么算它入表。 */
    private String columnCode;
    /** 上游公告 ID，(stock_code, art_code) 是幂等键。 */
    private String artCode;
    private LocalDateTime createdAt;
}
