package com.emotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.MarketStock;
import com.emotion.entity.Surveillance;
import com.emotion.entity.SurveillanceDaily;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.mapper.SurveillanceDailyMapper;
import com.emotion.mapper.SurveillanceMapper;
import com.emotion.market.SurveillanceKind;
import com.emotion.market.TencentClient;
import com.emotion.market.TencentClient.DayBar;
import com.emotion.vo.SurveillanceTrackVO;

/**
 * 监管全生命周期轨迹：把 t_surveillance（只存事件）按 SurveillanceKind 的窗口现推成
 * 每日轨迹（D+1 → D+N）并幂等落入 t_surveillance_daily，支撑 D5 高位生态「监管池 → 热力表」。
 *
 * <p>数据口径与 {@link SurveillanceService} 同源：窗口交易日用指数日K（公共日历），
 * change_pct 用该票自己的日K补齐；某窗口交易日该票无日K = 停牌（与公共日历错位）。
 * 落库先删窗口再整体 INSERT，重算无副作用。
 */
@Service
public class SurveillanceTrackService {

    private static final Logger log = LoggerFactory.getLogger(SurveillanceTrackService.class);

    /** 往前兜多少历日找监管事件（超过单个窗口长度即可）。 */
    private static final int LOOKBACK_DAYS = 60;
    /** 落库时窗口交易日的回溯上界（10 个交易日在 28 个历日内必然装得下）。 */
    private static final int WINDOW_CAL_DAYS = 28;
    /** 累计涨幅曲线只在监管股 ≤ 5 只时返回，避免糊成一团。 */
    private static final int CHART_MAX_ITEMS = 5;

    private final SurveillanceMapper surveillanceMapper;
    private final SurveillanceDailyMapper dailyMapper;
    private final MarketStockMapper stockMapper;
    private final TencentClient tencent;

    public SurveillanceTrackService(SurveillanceMapper surveillanceMapper,
                                    SurveillanceDailyMapper dailyMapper,
                                    MarketStockMapper stockMapper,
                                    TencentClient tencent) {
        this.surveillanceMapper = surveillanceMapper;
        this.dailyMapper = dailyMapper;
        this.stockMapper = stockMapper;
        this.tencent = tencent;
    }

    /**
     * 某日监管全生命周期轨迹。先兜底把缺的事件窗口落库，再读表组装。
     *
     * @param date   当前交易日（用于判"这件事出池没"）
     * @param kinds  只看哪几类；空 = 默认只看 SEVERE＋EXCH（ZD 不进主视图）
     * @param active 只返回仍在监管期（未出池）的事件；false = 连同历史已出池一起给
     */
    public SurveillanceTrackVO track(LocalDate date, java.util.Collection<SurveillanceKind> kinds,
                                     boolean active) {
        List<Surveillance> events = surveillanceMapper.selectList(
                new LambdaQueryWrapper<Surveillance>()
                        .lt(Surveillance::getAnnDate, date)
                        .ge(Surveillance::getAnnDate, date.minusDays(LOOKBACK_DAYS)));
        // 默认只看"会进分"的 SEVERE 与 EXCH；ZD(例行异常波动)只展示不进分，主视图不占位
        java.util.Collection<SurveillanceKind> only = (kinds == null || kinds.isEmpty())
                ? java.util.Arrays.asList(SurveillanceKind.SEVERE, SurveillanceKind.EXCH)
                : kinds;
        // 同票合并：一只股票的 严重异动/交易所函 合为一行。锚点取组内最早公告日（其窗口
        // 天然盖住后续公告的活跃期），类型标签拼起来，总天数取组内最大监管期。
        Map<String, Surveillance> anchor = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> kindsOf = new LinkedHashMap<>();
        for (Surveillance e : events) {
            SurveillanceKind kind = kindOf(e.getKind());
            if (kind == null || !only.contains(kind)) {
                continue;
            }
            String code = e.getStockCode();
            kindsOf.computeIfAbsent(code, k -> new LinkedHashSet<>()).add(e.getKind());
            Surveillance cur = anchor.get(code);
            if (cur == null || e.getAnnDate().isBefore(cur.getAnnDate())) {
                anchor.put(code, e);
            }
        }
        List<Surveillance> anchors = new ArrayList<>(anchor.values());

        List<SurveillanceDaily> windowRows = new ArrayList<>();
        List<LocalDate> tradingDays = calendarFor(anchors, date);
        loadWindowsInto(anchors, tradingDays, date, windowRows);

        SurveillanceTrackVO vo = new SurveillanceTrackVO();
        vo.setDate(date);
        Map<TrackKey, List<SurveillanceDaily>> byWindow = new HashMap<>();
        for (SurveillanceDaily row : windowRows) {
            byWindow.computeIfAbsent(new TrackKey(row.getStockCode(), row.getAnnDate()),
                    k -> new ArrayList<>()).add(row);
        }
        List<SurveillanceTrackVO.Item> items = new ArrayList<>();
        for (Map.Entry<TrackKey, List<SurveillanceDaily>> en : byWindow.entrySet()) {
            String code = en.getKey().code;
            List<SurveillanceDaily> daily = new ArrayList<>(en.getValue());
            daily.sort(Comparator.comparingInt(SurveillanceDaily::getDayOffset));
            SurveillanceTrackVO.Item item = buildItem(daily, anchor.get(code),
                    kindsOf.get(code), date, tradingDays);
            if (active && item.isDone()) {
                continue; // 只看监控中：已出池的历史不占主视图
            }
            items.add(item);
        }
        items.sort(itemSorter());
        vo.setItems(items);
        if (items.size() <= CHART_MAX_ITEMS) {
            vo.setChart(buildChart(items));
        }
        return vo;
    }

    /** 缺窗口的现算补落；已落的读出来合并；数据源有更晚交易日但库窗口没盖到的重算补长。 */
    private void loadWindowsInto(List<Surveillance> events, List<LocalDate> tradingDays,
                                 LocalDate date, List<SurveillanceDaily> windowRows) {
        if (events.isEmpty()) {
            return;
        }
        for (Surveillance e : events) {
            List<SurveillanceDaily> existing = readWindow(e.getStockCode(), e.getAnnDate());
            if (!existing.isEmpty() && !windowIncomplete(existing, e, tradingDays, date)) {
                windowRows.addAll(existing);
                continue;
            }
            List<SurveillanceDaily> computed = computeWindow(e);
            if (computed.isEmpty()) {
                log.warn("{} {} 的监管窗口没有任何交易日，本轮跳过落库", e.getStockCode(), e.getAnnDate());
                if (!existing.isEmpty()) {
                    windowRows.addAll(existing);
                }
                continue;
            }
            dailyMapper.deleteWindow(e.getStockCode(), e.getAnnDate());
            dailyMapper.insertBatch(computed);
            windowRows.addAll(computed);
        }
    }

    /** 已落库窗口是否"短了"：库窗口最后一天早于"date 前窗口内本应已覆盖的最新交易日"。 */
    private boolean windowIncomplete(List<SurveillanceDaily> existing, Surveillance e,
                                     List<LocalDate> tradingDays, LocalDate date) {
        if (e.getAnnDate() == null || existing.isEmpty()) {
            return false;
        }
        SurveillanceKind kind = kindOf(e.getKind());
        if (kind == null) {
            return false;
        }
        int totalDays = kind.days();
        LocalDate existingLast = existing.get(existing.size() - 1).getTradeDate();
        LocalDate want = null;
        for (LocalDate day : tradingDays) { // tradingDays 升序
            if (day.isAfter(date)) {
                break;
            }
            int off = SurveillanceService.dayIndexAfter(e.getAnnDate(), day, tradingDays);
            if (off >= 1 && off <= totalDays) {
                want = day;
            }
        }
        return want != null && existingLast.isBefore(want);
    }

    private List<SurveillanceDaily> readWindow(String code, LocalDate annDate) {
        return dailyMapper.selectList(new LambdaQueryWrapper<SurveillanceDaily>()
                .eq(SurveillanceDaily::getStockCode, code)
                .eq(SurveillanceDaily::getAnnDate, annDate)
                .orderByAsc(SurveillanceDaily::getDayOffset));
    }

    /** 这批事件的公告日跨到 date 的指数交易日历（判出池/当前进度用，不含节假日）。 */
    private List<LocalDate> calendarFor(java.util.Collection<Surveillance> events, LocalDate date) {
        LocalDate start = null;
        for (Surveillance e : events) {
            if (e.getAnnDate() != null && (start == null || e.getAnnDate().isBefore(start))) {
                start = e.getAnnDate();
            }
        }
        if (start == null) {
            return new ArrayList<>();
        }
        return datesOf(fetchBars(tencent.shIndexCode(), start, date));
    }

    /** 现推某事件的每日轨迹（不落库，纯派生，便于单测注入）。 */
    List<SurveillanceDaily> computeWindow(Surveillance event) {
        LocalDate ann = event.getAnnDate();
        SurveillanceKind kind = kindOf(event.getKind());
        if (ann == null || kind == null) {
            return new ArrayList<>();
        }
        int days = kind.days();
        LocalDate calEnd = ann.plusDays(WINDOW_CAL_DAYS);

        // 窗口交易日 = 指数日K 从 D0 起，取偏移 ≤ days 的那些
        List<DayBar> calendarBars = fetchBars(tencent.shIndexCode(), ann, calEnd);
        List<LocalDate> tradingDays = datesOf(calendarBars);
        List<DayBar> ownBars = fetchBars(TencentClient.symbolOf(event.getStockCode()), ann, calEnd);

        List<MarketStock> poolRows = stockMapper.selectList(
                new LambdaQueryWrapper<MarketStock>()
                        .eq(MarketStock::getCode, event.getStockCode())
                        .ge(MarketStock::getTradeDate, ann)
                        .le(MarketStock::getTradeDate, calEnd));
        Map<LocalDate, MarketStock> poolByDate = new HashMap<>();
        for (MarketStock row : poolRows) {
            poolByDate.putIfAbsent(row.getTradeDate(), row);
        }

        List<SurveillanceDaily> rows = new ArrayList<>();
        for (LocalDate day : tradingDays) {
            if (day.isBefore(ann)) {
                continue;
            }
            int offset = SurveillanceService.dayIndexAfter(ann, day, tradingDays);
            if (offset > days) {
                break;
            }
            SurveillanceDaily row = new SurveillanceDaily();
            row.setStockCode(event.getStockCode());
            row.setStockName(event.getStockName());
            row.setAnnDate(ann);
            row.setKind(kind.name());
            row.setTradeDate(day);
            row.setDayOffset(offset);
            MarketStock poolRow = poolByDate.get(day);
            if (poolRow != null) {
                row.setPool(poolRow.getPool());
                row.setConsecutive(poolRow.getConsecutive());
                row.setBigLoss(poolRow.getBigLoss());
                row.setBreakCount(poolRow.getBreakCount());
                row.setSealAmount(poolRow.getSealAmount());
            }
            BigDecimal pct = barPct(ownBars, day);
            row.setChangePct(pct);
            // D0 公告日当天无日K属正常（公告多在盘后）；D+1 起无日K才是停牌
            row.setSuspended(day.isAfter(ann) && pct == null ? 1 : 0);
            rows.add(row);
        }
        return rows;
    }

    /** 组装单件 item + 状态判定。done/currentOffset 由交易日历现算。kind/totalDays 按组内合并。 */
    private static SurveillanceTrackVO.Item buildItem(List<SurveillanceDaily> daily,
                                                       Surveillance event,
                                                       java.util.Collection<String> kindNames,
                                                       LocalDate date, List<LocalDate> tradingDays) {
        SurveillanceTrackVO.Item item = new SurveillanceTrackVO.Item();
        SurveillanceDaily last = daily.get(daily.size() - 1);
        item.setCode(last.getStockCode());
        item.setName(last.getStockName());
        // 类型标签：单类显示原名，多类合并（如 "EXCH+SEVERE"），按字母序稳定
        String kindLabel = mergedKindLabel(kindNames);
        item.setKind(kindLabel);
        item.setAnnDate(last.getAnnDate());
        int totalDays = mergedTotalDays(kindNames);
        if (totalDays <= 0) {
            totalDays = last.getDayOffset();
        }
        item.setTotalDays(totalDays);
        item.setEndDate(last.getTradeDate());
        LocalDate ann = event == null ? null : event.getAnnDate();
        // 公告之后到 date 的真实交易日数，即当前走到 D+N 的 N；够到 totalDays 才算出池
        int elapsed = (ann == null || date == null) ? 0
                : SurveillanceService.dayIndexAfter(ann, date, tradingDays);
        boolean done = elapsed >= totalDays;

        List<SurveillanceTrackVO.DayCell> cells = new ArrayList<>();
        int maxBoard = 0;
        int turnDay = 0;
        boolean hasZt = false;
        boolean hasNuke = false;
        int swept = 0;
        BigDecimal cum = BigDecimal.ONE;
        for (SurveillanceDaily row : daily) {
            if (row.getDayOffset() == 0) {
                continue; // D0 只用于定轴，不进格子
            }
            SurveillanceTrackVO.DayCell cell = new SurveillanceTrackVO.DayCell();
            cell.setOffset(row.getDayOffset());
            cell.setDate(row.getTradeDate());
            cell.setChg(row.getChangePct());
            cell.setConsecutive(row.getConsecutive());
            cell.setPool(row.getPool());
            cell.setSuspended(row.getSuspended() != null && row.getSuspended() == 1);
            if (cell.isSuspended()) {
                cell.setEvent("停牌");
            } else if (MarketStock.POOL_LIMIT_DOWN.equals(row.getPool())) {
                cell.setEvent("跌停");
            } else if (MarketStock.POOL_BROKEN.equals(row.getPool())) {
                cell.setEvent("断板");
            } else if (row.getBigLoss() != null && row.getBigLoss() == 1) {
                cell.setEvent("核按钮");
            }
            cells.add(cell);

            if (MarketStock.POOL_LIMIT_UP.equals(row.getPool())) {
                hasZt = true;
            }
            if ((row.getBigLoss() != null && row.getBigLoss() == 1)
                    || MarketStock.POOL_LIMIT_DOWN.equals(row.getPool())) {
                hasNuke = true;
            }
            if (row.getConsecutive() != null && row.getConsecutive() > maxBoard) {
                maxBoard = row.getConsecutive();
            }
            if (turnDay == 0 && (MarketStock.POOL_LIMIT_DOWN.equals(row.getPool())
                    || MarketStock.POOL_BROKEN.equals(row.getPool())
                    || (row.getChangePct() != null && row.getChangePct().signum() < 0))) {
                turnDay = row.getDayOffset();
            }
            swept++;
            if (cell.getChg() != null) {
                cum = cum.multiply(BigDecimal.ONE.add(
                        cell.getChg().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
            }
        }
        item.setDaily(cells);
        item.setMaxBoard(maxBoard);
        item.setTurnDay(turnDay == 0 ? null : turnDay);
        item.setCumChg(swept == 0 ? null
                : cum.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP));

        item.setCurrentOffset(done || elapsed < 1 ? null : Math.min(elapsed, totalDays));
        item.setDone(done);
        item.setAvoid(hasZt && !hasNuke);

        SurveillanceTrackVO.Status st = statusOf(done, hasZt, hasNuke, maxBoard);
        item.setStatus(st.getName());
        item.setStatusTone(st.getTone());
        item.setWhy(st.getReason());
        return item;
    }

    /** 单类原名，多类按字母序以 + 相连（如 "EXCH+SEVERE"）。 */
    private static String mergedKindLabel(java.util.Collection<String> kindNames) {
        if (kindNames == null || kindNames.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>(new LinkedHashSet<>(kindNames));
        names.sort(String::compareTo);
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(n);
        }
        return sb.toString();
    }

    /** 组内最大监管期（SEVERE/EXCH=10）；未知类型记 0，由调用方回退。 */
    private static int mergedTotalDays(java.util.Collection<String> kindNames) {
        int max = 0;
        if (kindNames == null) {
            return 0;
        }
        for (String n : kindNames) {
            SurveillanceKind k = kindOf(n);
            if (k != null) {
                max = Math.max(max, k.days());
            }
        }
        return max;
    }

    /** 状态五态。聚合标记已由调用方统计，这里是纯分类。 */
    static SurveillanceTrackVO.Status statusOf(boolean done, boolean hasZt, boolean hasNuke, int maxBoard) {
        if (done) {
            return new SurveillanceTrackVO.Status("已出池", "中性", "窗口已走完，看后续反包/走弱");
        }
        if (hasZt) {
            if (hasNuke) {
                return new SurveillanceTrackVO.Status("先扬后抑", "危险",
                        "监管期内一度涨停又核按钮/断板：抱团被按下，随时补跌");
            }
            return new SurveillanceTrackVO.Status("绕异动", "警示",
                    "监管期内仍涨停：监管被无视，抱团高潮，随时反转（最高 " + maxBoard + " 板）");
        }
        if (hasNuke) {
            return new SurveillanceTrackVO.Status("监管生效", "危险",
                    "监管期内无涨停且已出跌停/核按钮：监管起效，退潮确认");
        }
        return new SurveillanceTrackVO.Status("监管生效", "中性",
                "D+1 起未再涨停：监管压制下转弱，无抱团延续");
    }

    /** ≤5 只时的累计涨幅曲线。 */
    private static List<SurveillanceTrackVO.Series> buildChart(List<SurveillanceTrackVO.Item> items) {
        List<SurveillanceTrackVO.Series> series = new ArrayList<>();
        for (SurveillanceTrackVO.Item item : items) {
            SurveillanceTrackVO.Series s = new SurveillanceTrackVO.Series();
            s.setName(item.getName() + "(" + item.getKind() + ")");
            BigDecimal cum = BigDecimal.ONE;
            List<BigDecimal> points = new ArrayList<>();
            for (SurveillanceTrackVO.DayCell cell : item.getDaily()) {
                if (cell.getChg() != null) {
                    cum = cum.multiply(BigDecimal.ONE.add(
                            cell.getChg().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
                }
                points.add(cum.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP));
            }
            s.setPoints(points);
            series.add(s);
        }
        return series;
    }

    private static SurveillanceKind kindOf(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return SurveillanceKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("监管类别未知：{}", raw);
            return null;
        }
    }

    // ---------- 取数小工具 ----------

    private List<DayBar> fetchBars(String symbol, LocalDate begin, LocalDate end) {
        if (symbol == null) {
            return new ArrayList<>();
        }
        List<DayBar> bars = tencent.dailyBars(symbol, begin, end);
        return bars == null ? new ArrayList<>() : bars;
    }

    private static BigDecimal barPct(List<DayBar> bars, LocalDate date) {
        if (bars == null) {
            return null;
        }
        for (DayBar bar : bars) {
            if (date.equals(bar.getDate())) {
                return bar.getPct();
            }
        }
        return null;
    }

    private static List<LocalDate> datesOf(List<DayBar> bars) {
        List<LocalDate> days = new ArrayList<>(bars.size());
        for (DayBar bar : bars) {
            days.add(bar.getDate());
        }
        return days;
    }

    /** 排序：未出池在前，绕异动→先扬后抑→监管生效→已出池，同档按公告日序。 */
    private static Comparator<SurveillanceTrackVO.Item> itemSorter() {
        return Comparator
                .comparing((SurveillanceTrackVO.Item i) -> i.isDone())
                .thenComparing(i -> ("绕异动".equals(i.getStatus()) ? 0
                        : "先扬后抑".equals(i.getStatus()) ? 1
                        : "监管生效".equals(i.getStatus()) ? 2 : 3))
                .thenComparing(SurveillanceTrackVO.Item::getAnnDate);
    }

    /** 去重键。 */
    private static final class TrackKey {
        private final String code;
        private final LocalDate annDate;

        TrackKey(String code, LocalDate annDate) {
            this.code = code;
            this.annDate = annDate;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TrackKey)) {
                return false;
            }
            TrackKey that = (TrackKey) o;
            return code == null ? that.code == null : code.equals(that.code)
                    && (annDate == null ? that.annDate == null : annDate.equals(that.annDate));
        }

        @Override
        public int hashCode() {
            int result = code == null ? 0 : code.hashCode();
            result = 31 * result + (annDate == null ? 0 : annDate.hashCode());
            return result;
        }
    }
}