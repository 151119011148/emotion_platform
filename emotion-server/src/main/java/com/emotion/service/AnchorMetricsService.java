package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.Anchor;
import com.emotion.entity.Stock;
import com.emotion.mapper.StockMapper;
import com.emotion.market.AnchorMetrics;
import com.emotion.market.TencentClient;
import com.emotion.market.TencentClient.DayBar;
import com.emotion.util.TemperatureCalculator;
import com.emotion.vo.AnchorVO;

/**
 * 阵眼跨度：把登记的阵眼对着日 K 量一遍，给出跨度、最高板、断板、当日反馈和第 8 维分数。
 *
 * <p>请求数 = 在位阵眼数（实测一到三只），一次请求覆盖它自己的整个跨度。
 * 跨度里的每个数都是算出来的：只要有一个能手填，它就会开始和行情各说各话。
 */
@Service
public class AnchorMetricsService {

    private static final Logger log = LoggerFactory.getLogger(AnchorMetricsService.class);

    /** 往前多要一段日历日：跨度第一天也要有前收可比，不然最高板会少算第一天。 */
    private static final int LEAD_IN_DAYS = 20;

    private final AnchorService anchorService;
    private final TencentClient tencent;
    private final StockMapper stockMapper;

    public AnchorMetricsService(AnchorService anchorService, TencentClient tencent, StockMapper stockMapper) {
        this.anchorService = anchorService;
        this.tencent = tencent;
        this.stockMapper = stockMapper;
    }

    /** 某日的在位阵眼 + 跨度指标。列表为空就是"没设阵眼"，与"设了但拉不到行情"必须分得开。 */
    public AnchorVO vo(Long userId, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        List<Anchor> anchors = anchorService.listInPosition(userId, day);
        AnchorVO vo = new AnchorVO();
        vo.setTradeDate(day);
        vo.setItems(new ArrayList<AnchorVO.Item>());
        if (anchors.isEmpty()) {
            vo.setAvailable(false);
            vo.setNote("未设阵眼：第 8 维不计入分母（不是 0 分）");
            return vo;
        }
        Map<String, String> boards = boardsOf(anchors);
        List<AnchorMetrics.Span> spans = new ArrayList<>(anchors.size());
        for (Anchor anchor : anchors) {
            AnchorMetrics.Span span = measure(anchor, day,
                    AnchorMetrics.limitOf(boards.get(anchor.getStockCode())));
            spans.add(span);
            vo.getItems().add(item(anchor, span));
        }
        vo.setScore(TemperatureCalculator.worstAnchorScore(spans));
        vo.setAvailable(vo.getScore() != null);
        vo.setNote(summary(anchors, spans, vo.getScore()));
        return vo;
    }

    /** 曲线画跨度区间用：只按登记的起止日给区间，不拉当日行情。 */
    public List<AnchorVO.Span> spans(Long userId, LocalDate from, LocalDate to) {
        List<Anchor> anchors = anchorService.listOverlapping(userId, from, to);
        Map<String, String> boards = boardsOf(anchors);
        List<AnchorVO.Span> spans = new ArrayList<>(anchors.size());
        for (Anchor anchor : anchors) {
            // 终点落在窗口外时按窗口边界截，跨度天数只算窗口内那一段
            LocalDate until = anchor.getEndDate() == null || anchor.getEndDate().isAfter(to)
                    ? to : anchor.getEndDate();
            AnchorMetrics.Span measured = measure(anchor, until,
                    AnchorMetrics.limitOf(boards.get(anchor.getStockCode())));
            AnchorVO.Span span = new AnchorVO.Span();
            span.setId(anchor.getId());
            span.setCode(anchor.getStockCode());
            span.setName(anchor.getStockName());
            span.setRole(anchor.getRole());
            span.setRoleLabel(roleLabel(anchor.getRole()));
            span.setStartDate(anchor.getStartDate());
            span.setEndDate(anchor.getEndDate());
            span.setChartFrom(anchor.getStartDate().isBefore(from) ? from : anchor.getStartDate());
            span.setChartTo(until.isAfter(to) ? to : until);
            span.setTradeDays(measured.getTradeDays() > 0 ? measured.getTradeDays() : null);
            span.setMaxBoard(measured.getMaxBoard() > 0 ? measured.getMaxBoard() : null);
            spans.add(span);
        }
        return spans;
    }

    /**
     * 曲线第二根轴：一段窗口里每天的阵眼涨跌与第 8 维分。
     *
     * <p>请求数 = 在位阵眼数而不是天数：一只一次日 K，覆盖它自己的整个跨度，再逐重量。
     * 起点仍按跨度算而不是按窗口算 —— 窗口截断会让 {@code peakClose} 少看一段，
     * 于是"创跨度新高"会在图上一片假红柱。
     * <p>取最差那只：柱子和当天进的分必须出自同一个判据，否则图上那根负柱对不上分。
     * 拉不到行情的那天直接没有点，不补 0 —— 0% 在图上会被读成"横盘"。
     */
    public List<AnchorVO.Daily> dailySeries(Long userId, LocalDate from, LocalDate to) {
        List<Anchor> anchors = anchorService.listOverlapping(userId, from, to);
        Map<String, String> boards = boardsOf(anchors);
        java.util.TreeMap<LocalDate, AnchorVO.Daily> byDate =
                new java.util.TreeMap<LocalDate, AnchorVO.Daily>();
        for (Anchor anchor : anchors) {
            String symbol = TencentClient.symbolOf(anchor.getStockCode());
            if (symbol == null) {
                continue;
            }
            List<DayBar> bars;
            try {
                bars = tencent.dailyBars(symbol, anchor.getStartDate().minusDays(LEAD_IN_DAYS), to);
            } catch (RuntimeException e) {
                log.warn("阵眼 {} 日 K 未取得：{}", anchor.getStockCode(), e.getMessage());
                continue;
            }
            BigDecimal limit = AnchorMetrics.limitOf(boards.get(anchor.getStockCode()));
            for (DayBar bar : bars) {
                LocalDate day = bar.getDate();
                if (day == null || day.isBefore(from) || day.isAfter(to) || day.isBefore(anchor.getStartDate())) {
                    continue;
                }
                if (anchor.getEndDate() != null && day.isAfter(anchor.getEndDate())) {
                    continue;
                }
                AnchorMetrics.Span span = AnchorMetrics.measure(bars, anchor.getStartDate(),
                        anchor.getEndDate(), day, limit);
                if (span.isAvailable()) {
                    mergeWorst(byDate, point(anchor, span, day));
                }
            }
        }
        return new ArrayList<AnchorVO.Daily>(byDate.values());
    }

    private static AnchorVO.Daily point(Anchor anchor, AnchorMetrics.Span span, LocalDate day) {
        AnchorVO.Daily daily = new AnchorVO.Daily();
        daily.setDate(day);
        daily.setCode(anchor.getStockCode());
        daily.setName(anchor.getStockName());
        daily.setPct(span.getPct());
        daily.setLowPct(span.getLowPct());
        daily.setScore(TemperatureCalculator.calcAnchorScore(span));
        return daily;
    }

    /** 同一天多只在位留分低的那只；分相同留跌得深的那只。 */
    private static void mergeWorst(Map<LocalDate, AnchorVO.Daily> byDate, AnchorVO.Daily candidate) {
        AnchorVO.Daily held = byDate.get(candidate.getDate());
        if (held == null || worse(candidate.getScore(), candidate.getPct(), held.getScore(), held.getPct())) {
            byDate.put(candidate.getDate(), candidate);
        }
    }

    private static boolean worse(Integer a, BigDecimal aPct, Integer b, BigDecimal bPct) {
        if (a == null) {
            return false;
        }
        if (b == null || a < b) {
            return true;
        }
        return a.equals(b) && aPct != null && bPct != null && aPct.compareTo(bPct) < 0;
    }

    /** 涨跌停按板块阈值判，所以要把每只票的板块问出来。一次 in 查询，不逐只问。 */
    private Map<String, String> boardsOf(List<Anchor> anchors) {
        List<String> codes = new ArrayList<>(anchors.size());
        for (Anchor anchor : anchors) {
            codes.add(anchor.getStockCode());
        }
        Map<String, String> boards = new HashMap<>();
        if (codes.isEmpty()) {
            return boards;
        }
        List<Stock> stocks = stockMapper.selectList(new LambdaQueryWrapper<Stock>()
                .select(Stock::getCode, Stock::getBoard)
                .in(Stock::getCode, codes));
        for (Stock stock : stocks) {
            boards.put(stock.getCode(), stock.getBoard());
        }
        return boards;
    }

    /** 一只一次请求，覆盖它自己的整个跨度。拉不到就是拉不到，不拿 0 冒充。 */
    private AnchorMetrics.Span measure(Anchor anchor, LocalDate day, BigDecimal limitPct) {
        LocalDate until = anchor.getEndDate() == null || anchor.getEndDate().isAfter(day)
                ? day : anchor.getEndDate();
        String symbol = TencentClient.symbolOf(anchor.getStockCode());
        if (symbol == null) {
            log.warn("阵眼登记里的代码 {} 判不出市场，跳过取数", anchor.getStockCode());
            return AnchorMetrics.measure(new ArrayList<DayBar>(), anchor.getStartDate(), until, until, limitPct);
        }
        List<DayBar> bars;
        try {
            bars = tencent.dailyBars(symbol, anchor.getStartDate().minusDays(LEAD_IN_DAYS), until);
        } catch (RuntimeException e) {
            // 一只票的日 K 拉不到不该把整块面板带崩：它变成"这只未评"，其余照常出数
            log.warn("阵眼 {} 日 K 未取得：{}", anchor.getStockCode(), e.getMessage());
            bars = new ArrayList<>();
        }
        return AnchorMetrics.measure(bars, anchor.getStartDate(), until, until, limitPct);
    }

    private AnchorVO.Item item(Anchor anchor, AnchorMetrics.Span span) {
        AnchorVO.Item item = new AnchorVO.Item();
        item.setId(anchor.getId());
        item.setCode(anchor.getStockCode());
        item.setName(anchor.getStockName());
        item.setRole(anchor.getRole());
        item.setRoleLabel(roleLabel(anchor.getRole()));
        item.setCycleTag(anchor.getCycleTag());
        item.setStartDate(anchor.getStartDate());
        item.setEndDate(anchor.getEndDate());
        item.setNote(anchor.getNote());
        item.setLimitPct(span.getLimitPct());
        item.setAvailable(span.isAvailable());
        // 一根柱子都没拉到，跨度本身就是不存在的：这里写 0 等于把"没数据"报成"跨度 0 天、最高 0 板"
        boolean measured = span.getTradeDays() > 0;
        item.setTradeDays(measured ? span.getTradeDays() : null);
        item.setMaxBoard(measured ? span.getMaxBoard() : null);
        item.setLastBreakDate(span.getLastBreakDate());
        if (!span.isAvailable()) {
            // 当日那一批判据不兜 false：Span 里是基本类型，搬过来就是一个看着很正常的"没跌停、没断板"
            item.setReason(reasonOf(anchor.getStockName(), span));
            return item;
        }
        item.setPct(span.getPct());
        item.setLowPct(span.getLowPct());
        item.setDrawdownPct(span.getDrawdownPct());
        item.setCloseLimitDown(span.isCloseLimitDown());
        item.setTouchedLimitDown(span.isTouchedLimitDown());
        item.setBrokeToday(span.isBrokeToday());
        item.setNewSpanHigh(span.isNewSpanHigh());
        item.setScore(TemperatureCalculator.calcAnchorScore(span));
        item.setReason(reasonOf(anchor.getStockName(), span));
        return item;
    }

    /** 中文依据串：0 分从哪来的必须写在卡片上，不让人去猜是哪个判据命中的。 */
    static String reasonOf(String name, AnchorMetrics.Span span) {
        if (!span.isAvailable()) {
            return name + " 当日无行情（第 8 维未评，不是 0 分）";
        }
        String fact;
        if (span.isCloseLimitDown()) {
            fact = "收盘跌停";
        } else if (span.isTouchedLimitDown()) {
            fact = "盘中触板";
        } else if (span.isBrokeToday()) {
            fact = "断板";
        } else if (span.getPct() != null && span.getPct().signum() > 0) {
            fact = span.isNewSpanHigh() ? "收红·创跨度新高" : "收红";
        } else {
            fact = span.getPct() != null && span.getPct().signum() == 0 ? "平盘" : "收绿";
        }
        StringBuilder text = new StringBuilder(name).append(' ').append(fact);
        if (span.getPct() != null) {
            text.append(' ').append(span.getPct()).append('%');
        }
        if (span.isTouchedLimitDown() && !span.isCloseLimitDown() && span.getLowPct() != null) {
            text.append("（最低 ").append(span.getLowPct()).append("%）");
        }
        text.append(" · 跨度第 ").append(span.getTradeDays()).append(" 日 · 最高 ").append(span.getMaxBoard()).append(" 板");
        if (span.getDrawdownPct() != null && span.getDrawdownPct().signum() < 0) {
            text.append(" · 距跨度高点 ").append(span.getDrawdownPct()).append('%');
        }
        return text.toString();
    }

    private static String summary(List<Anchor> anchors, List<AnchorMetrics.Span> spans, Integer worst) {
        StringBuilder text = new StringBuilder("在位 ").append(anchors.size()).append(" 只");
        int measured = 0;
        for (AnchorMetrics.Span span : spans) {
            if (span.isAvailable()) {
                measured++;
            }
        }
        if (measured < anchors.size()) {
            text.append("，其中 ").append(anchors.size() - measured).append(" 只当日无行情");
        }
        text.append(worst == null ? "：第 8 维未评" : "：进分取最差 " + worst + " 分（阵眼是哨兵，不取平均）");
        return text.toString();
    }

    private static String roleLabel(String role) {
        return AnchorService.ROLE_LEADER.equals(role) ? "周期总龙" : "周期阵眼";
    }

    /** 供打分路径复用：一个用户某天在位的阵眼代码。 */
    public Collection<String> codesOf(Long userId, LocalDate day) {
        List<Anchor> anchors = anchorService.listInPosition(userId, day);
        List<String> codes = new ArrayList<>(anchors.size());
        for (Anchor anchor : anchors) {
            codes.add(anchor.getStockCode());
        }
        return codes;
    }
}
