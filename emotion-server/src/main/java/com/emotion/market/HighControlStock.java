package com.emotion.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketStock;
import com.emotion.mapper.MarketStockMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「高控庄票」风险识别：缩量独走连板 + 首板日小市值 + 一字锁死。
 *
 * <p>四个条件全部满足才打标（AND）：
 * <ol>
 *   <li>连板 ≥ 2</li>
 *   <li>换手率 &lt; 5%</li>
 *   <li>首板日流通市值 ≤ 35 亿（用 {@code float_mv} 回溯到连板首板日）</li>
 *   <li>一字锁死：首封 ≤ 93030 且全天 0 炸板</li>
 * </ol>
 *
 * <p>首板日市值的还原方式：在 {@code t_market_stock} 里找该 code 最近一条 {@code consecutive=1}
 * 且 {@code float_mv IS NOT NULL} 的行（45 天回溯），取它的 {@code float_mv}。
 * 找不到就 fail-open（不打标）——判不出来就说判不出来。
 */
public final class HighControlStock {

    private HighControlStock() {}

    public static final int MIN_BOARD = 2;
    public static final BigDecimal FLOAT_MV_CAP = new BigDecimal("3500000000");
    public static final BigDecimal TURNOVER_MAX = new BigDecimal("5");
    public static final int FLASH_LINE_MAX = 93030;
    public static final int LOOKBACK_DAYS = 45;

    /** 一字锁死：首封 ≤ 93030 且全天 0 炸板。与 TiantiService.isLocked 同口径。 */
    public static boolean isLocked(MarketStock r) {
        Integer fbt = r == null ? null : r.getFirstSealTime();
        if (fbt == null) {
            return false;
        }
        int brk = r.getBreakCount() == null ? 0 : r.getBreakCount();
        return fbt <= FLASH_LINE_MAX && brk == 0;
    }

    /**
     * 纯判据：给定当日行情与首板日流通市值，判是否高控庄票。
     *
     * <p>{@code firstBoardFloatMv} 为 null 时返回 false（fail-open：查不到首板日市值就不打标）。
     */
    public static boolean isHighControl(MarketStock row, BigDecimal firstBoardFloatMv) {
        if (row == null) {
            return false;
        }
        int board = row.getConsecutive() == null ? 0 : row.getConsecutive();
        if (board < MIN_BOARD) {
            return false;
        }
        BigDecimal hs = row.getTurnoverRate();
        if (hs == null || hs.compareTo(TURNOVER_MAX) >= 0) {
            return false;
        }
        if (!isLocked(row)) {
            return false;
        }
        if (firstBoardFloatMv == null || firstBoardFloatMv.compareTo(FLOAT_MV_CAP) > 0) {
            return false;
        }
        return true;
    }

    /**
     * 批量查一批 code 的首板日流通市值。
     *
     * <p>在 {@code t_market_stock} 里找每条 code 最近一条 {@code consecutive=1} 且
     * {@code float_mv IS NOT NULL} 的行（{@value #LOOKBACK_DAYS} 天回溯），取它的 {@code float_mv}。
     * 找不到首板日记录的 code 不出现在返回 map 里（fail-open）。
     */
    public static Map<String, BigDecimal> firstBoardFloatMv(MarketStockMapper mapper,
                                                             Collection<String> codes,
                                                             LocalDate date) {
        Map<String, BigDecimal> result = new HashMap<>();
        if (codes == null || codes.isEmpty() || date == null) {
            return result;
        }
        LocalDate from = date.minusDays(LOOKBACK_DAYS);
        List<MarketStock> rows = mapper.selectList(new LambdaQueryWrapper<MarketStock>()
                .in(MarketStock::getCode, codes)
                .eq(MarketStock::getConsecutive, 1)
                .isNotNull(MarketStock::getFloatMv)
                .ge(MarketStock::getTradeDate, from)
                .le(MarketStock::getTradeDate, date));
        Map<String, LocalDate> bestDate = new HashMap<>();
        for (MarketStock r : rows) {
            LocalDate prev = bestDate.get(r.getCode());
            if (prev == null || r.getTradeDate().isAfter(prev)) {
                result.put(r.getCode(), r.getFloatMv());
                bestDate.put(r.getCode(), r.getTradeDate());
            }
        }
        return result;
    }
}
