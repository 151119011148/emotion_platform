package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.emotion.entity.DailyBar;
import com.emotion.entity.DailyBarFetch;
import com.emotion.mapper.DailyBarFetchMapper;
import com.emotion.mapper.DailyBarMapper;
import com.emotion.market.TencentClient;
import com.emotion.market.TencentClient.DayBar;

/**
 * 日 K 落库缓存的取数路径：命中/回源/易变/停牌四种情形。
 *
 * <p>不连库不联网：mapper 与行情客户端全部 mock。重点钉的是「命中判定只看拉取留痕、不看行数」——
 * 停牌股天然缺行，用行数判命中的话它会被永远判成未缓存，缓存等于白做。
 */
class DailyBarServiceTest {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final LocalDate D1 = LocalDate.of(2026, 1, 5);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 9);

    private static DayBar bar(LocalDate date, String close) {
        DayBar b = new DayBar();
        b.setDate(date);
        b.setClose(new BigDecimal(close));
        b.setLow(new BigDecimal(close));
        return b;
    }

    private static DailyBar row(LocalDate date, String close) {
        DailyBar r = new DailyBar();
        r.setSymbol("sz000001");
        r.setTradeDate(date);
        r.setClosePrice(new BigDecimal(close));
        r.setLowPrice(new BigDecimal(close));
        return r;
    }

    private static DailyBarFetch cover(LocalDate from, LocalDate to) {
        DailyBarFetch f = new DailyBarFetch();
        f.setSymbol("sz000001");
        f.setStartDate(from);
        f.setEndDate(to);
        f.setBarCount(3);
        f.setFetchedAt(java.time.LocalDateTime.now(CN));
        return f;
    }

    private static DailyBarService svc(TencentClient tencent, DailyBarMapper barMapper,
                                       DailyBarFetchMapper fetchMapper) {
        return new DailyBarService(tencent, barMapper, fetchMapper, true, 20, 3, 30, 0);
    }

    /** 历史段命中留痕：一次上游都不打，涨跌幅按库里前一根重算。 */
    @Test
    void historyHitReadsDbOnly() {
        TencentClient tencent = mock(TencentClient.class);
        DailyBarMapper barMapper = mock(DailyBarMapper.class);
        DailyBarFetchMapper fetchMapper = mock(DailyBarFetchMapper.class);
        when(fetchMapper.findCovering(anyString(), any(), any(), any()))
                .thenReturn(cover(D1.minusDays(20), D2));
        // 含 lead 段的一根（1/2 收盘 10.00），让窗口首日 1/5 也有前收可比
        when(barMapper.selectRange(eq("sz000001"), eq(D1.minusDays(20)), eq(D2)))
                .thenReturn(Arrays.asList(row(LocalDate.of(2026, 1, 2), "10.00"),
                        row(D1, "11.00"), row(D2, "9.90")));

        List<DayBar> out = svc(tencent, barMapper, fetchMapper).bars("sz000001", D1, D2);

        verify(tencent, never()).dailyBars(anyString(), any(), any());
        assertEquals(2, out.size(), "只返回窗口内的两根，lead 段不漏给调用方");
        assertEquals(0, new BigDecimal("10.00").compareTo(out.get(0).getPct()),
                "11.00 对前一根 10.00 = +10.00%");
        assertEquals(0, new BigDecimal("-10.00").compareTo(out.get(1).getPct()),
                "9.90 对前一根 11.00 = -10.00%");
    }

    /** 停牌股缺行也算命中：这是「不看行数、只看留痕」要钉死的那一条。 */
    @Test
    void suspendedGapStillHits() {
        TencentClient tencent = mock(TencentClient.class);
        DailyBarMapper barMapper = mock(DailyBarMapper.class);
        DailyBarFetchMapper fetchMapper = mock(DailyBarFetchMapper.class);
        when(fetchMapper.findCovering(anyString(), any(), any(), any()))
                .thenReturn(cover(D1.minusDays(20), D2));
        // 1/6、1/7、1/8 停牌，库里就是没有行——不能因为"不够交易日数"就每次回源
        when(barMapper.selectRange(anyString(), any(), any()))
                .thenReturn(Arrays.asList(row(D1, "11.00"), row(D2, "11.50")));

        List<DayBar> out = svc(tencent, barMapper, fetchMapper).bars("sz000001", D1, D2);

        verify(tencent, never()).dailyBars(anyString(), any(), any());
        assertEquals(2, out.size());
    }

    /** 无留痕 → 回源，并且整段落库、写下覆盖留痕。 */
    @Test
    void missGoesUpstreamAndStores() {
        TencentClient tencent = mock(TencentClient.class);
        DailyBarMapper barMapper = mock(DailyBarMapper.class);
        DailyBarFetchMapper fetchMapper = mock(DailyBarFetchMapper.class);
        when(fetchMapper.findCovering(anyString(), any(), any(), any())).thenReturn(null);
        when(tencent.dailyBars(eq("sz000001"), eq(D1.minusDays(20)), eq(D2)))
                .thenReturn(new ArrayList<>(Arrays.asList(
                        bar(LocalDate.of(2026, 1, 2), "10.00"),
                        bar(D1, "11.00"), bar(D2, "11.50"))));

        List<DayBar> out = svc(tencent, barMapper, fetchMapper).bars("sz000001", D1, D2);

        assertEquals(2, out.size());
        verify(barMapper).upsertBatch(any());
        verify(fetchMapper).upsert(any(DailyBarFetch.class));
    }

    /** 易变段（近 3 个自然日）即便有留痕也回源，而且不留痕——免得盘中拉一次就钉死当天最终值。 */
    @Test
    void volatileWindowAlwaysRefetchesAndLeavesNoMark() {
        TencentClient tencent = mock(TencentClient.class);
        DailyBarMapper barMapper = mock(DailyBarMapper.class);
        DailyBarFetchMapper fetchMapper = mock(DailyBarFetchMapper.class);
        LocalDate today = LocalDate.now(CN);
        when(fetchMapper.findCovering(anyString(), any(), any(), any()))
                .thenReturn(cover(today.minusDays(30), today));
        when(tencent.dailyBars(anyString(), any(), any()))
                .thenReturn(new ArrayList<>(Arrays.asList(bar(today.minusDays(1), "11.00"), bar(today, "11.50"))));

        List<DayBar> out = svc(tencent, barMapper, fetchMapper).bars("sz000001", today.minusDays(1), today);

        verify(tencent).dailyBars(anyString(), any(), any());
        verify(fetchMapper, never()).upsert(any(DailyBarFetch.class));
        assertEquals(2, out.size());
    }

    /** 留痕在但库里读不到行（上次写库失败）：必须回源，否则这只票在这个区间永久返回空。 */
    @Test
    void emptyRowsForceUpstream() {
        TencentClient tencent = mock(TencentClient.class);
        DailyBarMapper barMapper = mock(DailyBarMapper.class);
        DailyBarFetchMapper fetchMapper = mock(DailyBarFetchMapper.class);
        when(fetchMapper.findCovering(anyString(), any(), any(), any()))
                .thenReturn(cover(D1.minusDays(20), D2));
        when(barMapper.selectRange(anyString(), any(), any())).thenReturn(new ArrayList<DailyBar>());
        when(tencent.dailyBars(anyString(), any(), any()))
                .thenReturn(new ArrayList<>(Arrays.asList(bar(D1, "11.00"))));

        List<DayBar> out = svc(tencent, barMapper, fetchMapper).bars("sz000001", D1, D2);

        verify(tencent).dailyBars(anyString(), any(), any());
        assertEquals(1, out.size());
    }

    /** 库里第一段没有前一根时涨幅就是 null——拿隔壁交易日的涨幅顶上是更糟的错。 */
    @Test
    void firstBarWithoutPrevCloseHasNoPct() {
        TencentClient tencent = mock(TencentClient.class);
        DailyBarMapper barMapper = mock(DailyBarMapper.class);
        DailyBarFetchMapper fetchMapper = mock(DailyBarFetchMapper.class);
        when(fetchMapper.findCovering(anyString(), any(), any(), any()))
                .thenReturn(cover(D1.minusDays(20), D2));
        when(barMapper.selectRange(anyString(), any(), any()))
                .thenReturn(Arrays.asList(row(D1, "11.00")));

        List<DayBar> out = svc(tencent, barMapper, fetchMapper).bars("sz000001", D1, D2);

        assertEquals(1, out.size());
        assertNull(out.get(0).getPct());
    }

    /** 关掉开关就直接打上游，不碰库：排查"是不是缓存给了旧值"时的退路。 */
    @Test
    void disabledBypassesDb() {
        TencentClient tencent = mock(TencentClient.class);
        DailyBarMapper barMapper = mock(DailyBarMapper.class);
        DailyBarFetchMapper fetchMapper = mock(DailyBarFetchMapper.class);
        when(tencent.dailyBars(anyString(), any(), any()))
                .thenReturn(new ArrayList<>(Arrays.asList(bar(D1, "11.00"))));

        DailyBarService off = new DailyBarService(tencent, barMapper, fetchMapper, false, 20, 3, 30, 0);
        List<DayBar> out = off.bars("sz000001", D1, D2);

        verify(tencent).dailyBars(eq("sz000001"), eq(D1), eq(D2));
        verify(fetchMapper, never()).findCovering(anyString(), any(), any(), any());
        assertEquals(1, out.size());
        assertTrue(!off.isEnabled());
    }
    /** 段内复权版本混杂：不同时间拉的段拼在一起，衔接处会算出假跳变——判为未命中并整段重拉。 */
    @Test
    void mixedFqVersionForcesRefetch() {
        TencentClient tencent = mock(TencentClient.class);
        DailyBarMapper barMapper = mock(DailyBarMapper.class);
        DailyBarFetchMapper fetchMapper = mock(DailyBarFetchMapper.class);
        when(fetchMapper.findCovering(anyString(), any(), any(), any()))
                .thenReturn(cover(D1.minusDays(20), D2));
        DailyBar old1 = row(D1, "11.00");
        old1.setFqVersion("17");
        DailyBar old2 = row(D2, "11.50");
        old2.setFqVersion("18");
        when(barMapper.selectRange(anyString(), any(), any())).thenReturn(Arrays.asList(old1, old2));
        when(tencent.dailyBars(anyString(), any(), any()))
                .thenReturn(new ArrayList<>(Arrays.asList(bar(D1, "11.00"), bar(D2, "11.50"))));

        List<DayBar> out = svc(tencent, barMapper, fetchMapper).bars("sz000001", D1, D2);

        verify(tencent).dailyBars(anyString(), any(), any());
        assertEquals(2, out.size());
    }

    /** 除权自愈：回源发现同日收盘价对不上（上游把历史价重算了），整只票的旧行与留痕全部作废。 */
    @Test
    void restatementDropsWholeSymbol() {
        TencentClient tencent = mock(TencentClient.class);
        DailyBarMapper barMapper = mock(DailyBarMapper.class);
        DailyBarFetchMapper fetchMapper = mock(DailyBarFetchMapper.class);
        when(fetchMapper.findCovering(anyString(), any(), any(), any())).thenReturn(null);
        // 库里是除权前的旧价（茅台 6/1 实际 1309.60），上游现在给的是重算后的 1281.58
        when(barMapper.selectRange(anyString(), any(), any()))
                .thenReturn(Arrays.asList(row(D1, "1309.60")));
        when(tencent.dailyBars(anyString(), any(), any()))
                .thenReturn(new ArrayList<>(Arrays.asList(bar(D1, "1281.58"), bar(D2, "1281.00"))));

        svc(tencent, barMapper, fetchMapper).bars("sz000001", D1, D2);

        verify(barMapper).deleteBySymbol("sz000001");
        verify(fetchMapper).deleteBySymbol("sz000001");
        verify(barMapper).upsertBatch(any());
    }
}
