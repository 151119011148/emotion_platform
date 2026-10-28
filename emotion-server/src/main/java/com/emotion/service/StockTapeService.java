package com.emotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;

import com.emotion.exception.BizException;
import com.emotion.mapper.DailyBarMapper;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.StockAuctionMapper;
import com.emotion.mapper.StockMinuteMapper;
import com.emotion.entity.DailyBar;
import com.emotion.entity.MarketStock;
import com.emotion.entity.StockAuction;
import com.emotion.entity.StockMinute;
import com.emotion.market.TencentClient;
import com.emotion.vo.StockTapeVO;

/**
 * 读一只票一天的分时：分钟序列 + 竞价快照，涨幅与逐分钟量在<strong>读时</strong>算。
 *
 * <p><strong>这条接口只读库，缺数绝不回源。</strong>腾讯的分钟接口只给当天
 * （{@code V46} 迁移头部记了实测），所以「回源补一下」对历史日是不存在的路——
 * 与其让读接口挂在上游超时上返回一张空图，不如把「这一天为什么没有」分成三句话说清楚：
 * 早于上线日（永久缺失）、当天没抓成、抓了但这只不在名单里。前端拿到 {@code available=false}
 * 就直接印这句话。
 *
 * <p>与 {@code t_daily_bar}「pct 不落库」同一条纪律：库里存的是累计量与额的原值，
 * 差分、VWAP、涨幅都在这里按当前口径算出来，将来改口径不用回补历史行。
 *
 * <p>昨收（涨幅的分母）有三条来路，精度递减，见 {@link StockTapeVO#PREV_AUCTION} 等常量；
 * 三条都没有就把 {@code pct} 留空，价格线照画——缺分母是「这一维不给看」，
 * 不该变成整张图不给看，更不该拿相邻日、开盘价凑一个分母出来。
 */
@Service
public class StockTapeService {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    /** 19:12 是 stock_minute_pull_task 的排班时刻，早于它问「今天」的分时就是还没到点。 */
    private static final int MINUTE_PULL_HOUR = 19;
    private static final int MINUTE_PULL_MINUTE = 12;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final StockMinuteMapper minuteMapper;
    private final StockAuctionMapper auctionMapper;
    private final MarketStockMapper marketStockMapper;
    private final DailyBarMapper dailyBarMapper;

    public StockTapeService(StockMinuteMapper minuteMapper,
                            StockAuctionMapper auctionMapper,
                            MarketStockMapper marketStockMapper,
                            DailyBarMapper dailyBarMapper) {
        this.minuteMapper = minuteMapper;
        this.auctionMapper = auctionMapper;
        this.marketStockMapper = marketStockMapper;
        this.dailyBarMapper = dailyBarMapper;
    }

    public StockTapeVO tape(String rawCode, LocalDate date) {
        String code = requireCode(rawCode);
        LocalDate day = date != null ? date : LocalDate.now(CN);

        StockTapeVO vo = new StockTapeVO();
        vo.setCode(code);
        vo.setTradeDate(day);

        StockAuction auction = auctionMapper.selectForDate(code, day);
        List<StockMinute> bars = minuteMapper.selectDay(code, day);
        // 当日明细行一次查、三处用：名称兜底、涨幅分母的第二条来路、以及它本身就是「这只那天在池子里」的证据。
        MarketStock detailRow = marketStockMapper.rowOfCodeOn(code, day);

        vo.setName(auction != null && notBlank(auction.getName())
                ? auction.getName() : (detailRow == null ? null : detailRow.getName()));
        vo.setAuction(auctionBlock(auction, code, day));

        PrevClose prev = prevClose(code, day, auction, detailRow);
        vo.setPrevClose(prev.value);
        vo.setPrevCloseSource(prev.source);

        if (bars.isEmpty()) {
            vo.setAvailable(false);
            vo.setEmptyReason(minuteReason(day, code, minuteMapper.countCodesOn(day)));
            return vo;
        }

        vo.setAvailable(true);
        vo.setPoints(points(bars, prev.value));
        return vo;
    }

    /** 逐分钟量取累计差分；第一根没有上一根可减，差值就是它自己的累计值——那里面含竞价撮合的量，本来就该在。 */
    private List<StockTapeVO.Point> points(List<StockMinute> bars, BigDecimal prev) {
        List<StockTapeVO.Point> out = new java.util.ArrayList<>(bars.size());
        Long prevHand = null;
        BigDecimal prevAmount = null;
        for (StockMinute bar : bars) {
            StockTapeVO.Point p = new StockTapeVO.Point();
            p.setMinute(bar.getMinute());
            p.setTime(hhmm(bar.getMinute()));
            p.setPrice(bar.getPrice());
            p.setPct(percentOf(bar.getPrice(), prev));
            p.setVwap(vwap(bar.getCumAmountYuan(), bar.getCumVolumeHand()));
            if (bar.getCumVolumeHand() != null) {
                p.setVolumeHand(prevHand == null
                        ? bar.getCumVolumeHand() : bar.getCumVolumeHand() - prevHand);
            }
            if (bar.getCumAmountYuan() != null && prevAmount != null) {
                p.setAmountWan(wan(bar.getCumAmountYuan().subtract(prevAmount)));
            } else if (bar.getCumAmountYuan() != null) {
                p.setAmountWan(wan(bar.getCumAmountYuan()));
            }
            prevHand = bar.getCumVolumeHand();
            prevAmount = bar.getCumAmountYuan();
            out.add(p);
        }
        return out;
    }

    private StockTapeVO.Auction auctionBlock(StockAuction a, String code, LocalDate day) {
        StockTapeVO.Auction out = new StockTapeVO.Auction();
        if (a == null) {
            out.setAvailable(false);
            int capturedThatDay = auctionMapper.countCodesOn(day);
            out.setEmptyReason(capturedThatDay == 0
                    ? "这一天没有竞价快照：竞价只能当天 09:25–09:30 那五分钟里抓，过了就永久没有。"
                    : "这一天抓了 " + capturedThatDay + " 只票的竞价，这只不在名单里。");
            return out;
        }
        out.setAvailable(true);
        out.setAuctionPrice(a.getAuctionPrice());
        out.setPct(percentOf(a.getAuctionPrice(), prevOf(a)));
        out.setVolumeHand(a.getVolumeHand());
        out.setAmountWan(a.getAmountWan());
        out.setBid1Price(a.getBid1Price());
        out.setBid1Hand(a.getBid1Hand());
        out.setAsk1Price(a.getAsk1Price());
        out.setAsk1Hand(a.getAsk1Hand());
        out.setLimitUpPrice(a.getLimitUpPrice());
        out.setSealedAtOpen(sealedAtOpen(a));
        out.setCapturedAt(a.getCapturedAt());
        return out;
    }

    /**
     * 一字板成因：竞价成交价等于当日涨停价。
     *
     * <p>两个值齐了才敢答，缺任一个是 {@code null}（不知道），不是 {@code false}（不是封死）——
     * 这一维将来要进判据，把「不知道」写成「否」就是造假。
     */
    static Boolean sealedAtOpen(StockAuction a) {
        if (a == null || a.getAuctionPrice() == null || a.getLimitUpPrice() == null) {
            return null;
        }
        return a.getAuctionPrice().compareTo(a.getLimitUpPrice()) == 0;
    }

    /**
     * 涨幅分母。三条来路按精度排：
     *
     * <ol>
     *   <li>09:26 快照里的 {@code prev_close}：源给的不复权原值，最准；</li>
     *   <li>当日盘面明细的收盘与涨幅反推：{@code 收盘 ÷ (1+涨幅/100)}。涨幅只有两位小数，
     *       所以这是个近似值，但它是<b>同日</b>的原始口径，比下一条的复权序列可靠；</li>
     *   <li>库里日 K 的上一根收盘：<b>qfq 前复权</b>序列，只有它严格等于前一交易日才敢用——
     *       中间除过权会整体偏低，画出来的涨幅就虚高。</li>
     * </ol>
     */
    private PrevClose prevClose(String code, LocalDate day, StockAuction auction, MarketStock row) {
        if (auction != null && positive(auction.getPrevClose())) {
            return new PrevClose(auction.getPrevClose(), StockTapeVO.PREV_AUCTION);
        }

        if (row != null) {
            BigDecimal factor = row.getChangePct().divide(HUNDRED, 6, RoundingMode.HALF_UP).add(BigDecimal.ONE);
            if (factor.signum() > 0) {
                return new PrevClose(row.getClosePrice().divide(factor, 3, RoundingMode.HALF_UP),
                        StockTapeVO.PREV_MARKET_STOCK);
            }
        }

        String symbol = TencentClient.symbolOf(code);
        DailyBar bar = symbol == null ? null : dailyBarMapper.lastBefore(symbol, day);
        if (bar != null && bar.getTradeDate() != null && positive(bar.getClosePrice())
                && bar.getTradeDate().equals(marketStockMapper.prevDetailDate(day))) {
            return new PrevClose(bar.getClosePrice(), StockTapeVO.PREV_DAILY_BAR);
        }
        return new PrevClose(null, StockTapeVO.PREV_NONE);
    }

    /**
     * 分钟序列为什么没有。这句话是给界面印的，所以必须说中是哪一种。
     *
     * <p>{@code firstStoredDate} 是库里的最早一天而不是写死的 2026-10-28：迁移上线那天一旦顺延，
     * 硬写的日期就成了假话。
     */
    private String minuteReason(LocalDate day, String code, int capturedThatDay) {
        if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return day + " 是周末，没有行情。";
        }
        if (capturedThatDay > 0) {
            return "这一天存了 " + capturedThatDay + " 只票的分时，" + code + " 不在名单里——"
                    + "分钟序列只抓当日涨停池与炸板池。";
        }
        LocalDate first = minuteMapper.firstStoredDate();
        if (first != null && day.isBefore(first)) {
            return "分时从 " + first + " 起才开始存。上游只给当天那一天的分钟线，"
                    + "所以这一天一天也补不回来。";
        }
        LocalDateTime now = LocalDateTime.now(CN);
        if (day.isEqual(now.toLocalDate())
                && (now.getHour() < MINUTE_PULL_HOUR
                || (now.getHour() == MINUTE_PULL_HOUR && now.getMinute() < MINUTE_PULL_MINUTE))) {
            return "当天的分钟序列是 19:12 那一趟抓的，现在还没到点。";
        }
        return "这一天库里没有存过任何票的分时——19:12 那一趟没跑成，或那天没有行情。";
    }

    private static BigDecimal prevOf(StockAuction a) {
        return positive(a.getPrevClose()) ? a.getPrevClose() : null;
    }

    /** 涨幅 %，两位小数；分母缺失或不正就返回 null，让调用方自己决定不给看这一维。 */
    private static BigDecimal percentOf(BigDecimal price, BigDecimal prevClose) {
        if (price == null || !positive(prevClose)) {
            return null;
        }
        return price.subtract(prevClose)
                .divide(prevClose, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 均价 = 累计成交额 ÷ (累计成交量(手) × 100)，即日内 VWAP，三位小数才够贴价格线。 */
    private static BigDecimal vwap(BigDecimal cumAmountYuan, Long cumVolumeHand) {
        if (cumAmountYuan == null || cumVolumeHand == null || cumVolumeHand <= 0) {
            return null;
        }
        return cumAmountYuan.divide(BigDecimal.valueOf(cumVolumeHand * 100L), 3, RoundingMode.HALF_UP);
    }

    private static BigDecimal wan(BigDecimal yuan) {
        return yuan.divide(HUNDRED.multiply(HUNDRED), 2, RoundingMode.HALF_UP);
    }

    /** HHMM → "09:30"；库里给什么就印什么，不猜补零之外的格式。 */
    static String hhmm(Integer minute) {
        if (minute == null || minute < 0 || minute > 2359) {
            return null;
        }
        return String.format("%02d:%02d", minute / 100, minute % 100);
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String requireCode(String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches("\\d{6}")) {
            throw new BizException("股票代码得是 6 位数字，收到：" + rawCode);
        }
        return code;
    }

    private static final class PrevClose {
        private final BigDecimal value;
        private final String source;

        private PrevClose(BigDecimal value, String source) {
            this.value = value;
            this.source = source;
        }
    }
}
