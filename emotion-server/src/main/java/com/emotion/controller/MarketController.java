package com.emotion.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.dto.PremiumTierRequest;
import com.emotion.entity.IndexClose;
import com.emotion.market.MarketDataException;
import com.emotion.market.TencentClient;
import com.emotion.service.IndexCloseStore;
import com.emotion.service.MarketDailyStore;
import com.emotion.service.MarketDataService;
import com.emotion.service.ScoreContextService;
import com.emotion.service.SurveillanceService;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.MarketBreadthVO;
import com.emotion.vo.MarketIndexesVO;
import com.emotion.vo.MarketSnapshotVO;
import com.emotion.vo.MarketStocksVO;
import com.emotion.vo.PremiumPoolVO;
import com.emotion.vo.PremiumTiersVO;
import com.emotion.vo.ScoreContextVO;

/**
 * 行情拉取不写打分字段：打分仍靠 /api/records 落库，
 * 这样人工确认（主线题材、龙头、明确度）才有地方插进去，也不会绕开阶段判定逻辑。
 * 例外一：公开明细表——/snapshot 顺带落 t_market_stock（逐只池子）、
 * t_premium_tier（档位溢价）、t_index_close（五大指数收盘）与 t_market_daily（客观行情日九数），
 * 四张表都不绑用户、都不带人工判断，全账号共享一份。
 *
 * t_surveillance 不跟着 /snapshot 走：一次刷新要打二三十次公告接口，会把
 * {@code market.budget-ms} 顶穿，所以它是显式的 /surveillance/refresh。
 * t_index_close 能跟着走是因为它只在"那天还没有收盘价"时才取（五根日 K，一次一只），
 * 补齐之后就一发都不发；失败也只少那五行，不参与打分，进不了 warnings 之外的任何判据。
 *
 * <p>要<b>读</b>第 8/9 维（阵眼、监管名单）同理另开一条 {@code /score-context}：
 * 那两维一次要打十几到三十几次日 K，塞进 /snapshot 的后果是他只要七个数、却被上游拖成一整屏红。
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketDataService marketDataService;
    private final SurveillanceService surveillanceService;
    private final ScoreContextService scoreContextService;
    private final IndexCloseStore indexCloseStore;
    private final MarketDailyStore marketDailyStore;

    public MarketController(MarketDataService marketDataService,
                            SurveillanceService surveillanceService,
                            ScoreContextService scoreContextService,
                            IndexCloseStore indexCloseStore,
                            MarketDailyStore marketDailyStore) {
        this.marketDataService = marketDataService;
        this.surveillanceService = surveillanceService;
        this.scoreContextService = scoreContextService;
        this.indexCloseStore = indexCloseStore;
        this.marketDailyStore = marketDailyStore;
    }

    /**
     * 全市场实时涨跌家数（东财 f104/105/106）。无日期参数：上游只有当前时刻，
     * 非交易时段给最近交易日收盘口径；历史日期的涨跌家数在每日复盘 md 里。
     */
    @GetMapping("/breadth")
    public ApiResponse<MarketBreadthVO> breadth() {
        return ApiResponse.ok(marketDataService.breadth());
    }

    /**
     * 大盘生态页·五大指数区块：公开表 t_index_close，不绑登录态。
     * 不传 date 回落到最近一个有指数行的交易日（周末进来不空屏）。
     */
    @GetMapping("/indexes")
    public ApiResponse<MarketIndexesVO> indexes(@RequestParam(required = false) String date) {
        Map.Entry<LocalDate, List<IndexClose>> hit = indexCloseStore.readOrLatest(parse(date));
        MarketIndexesVO vo = new MarketIndexesVO();
        vo.setTradeDate(hit.getKey());
        for (IndexClose row : hit.getValue()) {
            MarketIndexesVO.Item item = new MarketIndexesVO.Item();
            item.setCode(row.getIndexCode());
            item.setName(row.getIndexName());
            item.setClose(row.getClosePrice());
            item.setChangePct(row.getChangePct());
            vo.getIndexes().add(item);
        }
        return ApiResponse.ok(vo);
    }

    @GetMapping("/snapshot")
    public ApiResponse<MarketSnapshotVO> snapshot(Authentication auth,
                                                  @RequestParam(required = false) String date,
                                                  @RequestParam(defaultValue = "false") boolean refresh) {
        LocalDate tradeDate = parse(date);
        MarketSnapshotVO vo = marketDataService.snapshot(tradeDate, refresh);

        // 客观行情九数与用户无关：拉取即 upsert 进全局 t_market_daily（非空才覆盖）。
        // 涨跌家数只有实时口径——仅当拉的就是行情源最新交易时段（snapshotDate=请求日）才一起写，
        // 历史日的实时数不代表那天，liveBreadth=false 时整两列不碰。
        if (vo.getTradeDate() != null && vo.getFilled() != null) {
            boolean liveBreadth = vo.getSnapshotDate() != null
                    && vo.getSnapshotDate().equals(vo.getTradeDate())
                    && vo.getFilled().getUpCount() != null
                    && vo.getFilled().getDownCount() != null;
            marketDailyStore.upsertSnapshot(vo.getTradeDate(), vo.getFilled(), liveBreadth);
        }
        return ApiResponse.ok(vo);
    }

    /** 一日盘面个股明细：连板梯队、断档、大面名单、跌停名单，供卡片 hover 用。 */
    @GetMapping("/stocks")
    public ApiResponse<MarketStocksVO> stocks(@RequestParam(required = false) String date) {
        return ApiResponse.ok(marketDataService.stocks(parse(date)));
    }

    /** 一日"昨日涨停池今日溢价"的逐档与三组结果。 */
    @GetMapping("/premium-tiers")
    public ApiResponse<PremiumTiersVO> premiumTiers(@RequestParam(required = false) String date) {
        return ApiResponse.ok(marketDataService.premiumTiers(parse(date)));
    }

    /**
     * 子项读数：第 4 维两个家数口径、第 8 维阵眼、第 9 维监管名单，各带自己的算式。
     *
     * <p>要带登录态：第 8 维问的是"他设的阵眼今天反馈如何"，那是账号各自的登记，不是公开事实。
     * <p>只交公开读数，八列 {@code manual_*} 一概不回——人工值从表单走 {@code /api/records}，
     * 响应里带一份就有第二份真值，也就给了"拉一次行情把手改洗成自动值"的机会。
     */
    @GetMapping("/score-context")
    public ApiResponse<ScoreContextVO> scoreContext(Authentication auth,
                                                    @RequestParam(required = false) String date) {
        return ApiResponse.ok(scoreContextService.scoreContextVO(userId(auth), parse(date)));
    }

    /**
     * 回补取数清单：这一天要拉哪些票的日 K。上一交易日由服务端从明细表回看，
     * 脚本自己推会和服务端推成两个答案。
     */
    @GetMapping("/premium-pool")
    public ApiResponse<PremiumPoolVO> premiumPool(@RequestParam(required = false) String date) {
        return ApiResponse.ok(marketDataService.premiumPool(parse(date)));
    }

    /**
     * 一段日 K，涨幅由服务端算好。回补脚本因此只做搬运：
     * 腾讯那套"忽略 start、只认截至 end 的最近 N 根"的格式只有一处需要知道。
     */
    @GetMapping("/daily-bars")
    public ApiResponse<List<TencentClient.DayBar>> dailyBars(@RequestParam String symbol,
                                                             @RequestParam String start,
                                                             @RequestParam String end) {
        return ApiResponse.ok(marketDataService.dailyBars(symbol, parse(start), parse(end)));
    }

    /**
     * 档位溢价回补：脚本逐日 POST 它从日 K 取到的涨跌幅，服务端算档位。
     * 只给一个 date，一次重写那一天。
     */
    @PostMapping("/premium-tiers")
    public ApiResponse<PremiumTiersVO> savePremiumTiers(@RequestBody PremiumTierRequest body) {
        Map<String, BigDecimal> pct = body.getPct() == null
                ? Collections.<String, BigDecimal>emptyMap() : body.getPct();
        return ApiResponse.ok(marketDataService.savePremiumTiers(body.getTradeDate(), pct));
    }

    /**
     * 昨涨停股今日逐只表现回补（<b>含首板</b>）：1 进 2 大面的全样本来源。
     * 脚本从日 K（source=KBAR）或东财昨涨停板块（source=BK）取每只票当日涨跌幅 POST 上来，
     * 服务端按库里的昨日涨停池归档；matched 少于 total 时大面仍是下界，响应里必须能看见。
     */
    @PostMapping("/zt-perf")
    public ApiResponse<com.emotion.vo.ZtPerfVO> saveZtPerf(@RequestBody PremiumTierRequest body) {
        Map<String, BigDecimal> pct = body.getPct() == null
                ? Collections.<String, BigDecimal>emptyMap() : body.getPct();
        return ApiResponse.ok(marketDataService.saveZtPerf(body.getTradeDate(), pct, body.getSource()));
    }

    /**
     * 跟踪集合：脚本逐只刷新的输入清单。并发放在脚本里而不是这里——
     * 一次 100 只的刷新如果走服务端，就是一个打上百次上游的 HTTP 请求。
     */
    @GetMapping("/surveillance/tracked")
    public ApiResponse<List<String>> trackedSurveillanceCodes(@RequestParam String start,
                                                              @RequestParam String end) {
        return ApiResponse.ok(surveillanceService.trackedCodes(require(start), require(end)));
    }

    /**
     * 刷新公告事件。一只一次请求，并发交给脚本（同一个 daily-bars 那套组织方式）：
     * 服务端在这里排队打上游会把 HTTP 请求挂几十秒，而脚本已经有线程池和重试。
     *
     * @param codes 逗号分隔的 6 位代码；不传则用跟踪集合（窗口内非首板涨停池 ∪ 表里已有事件的代码）
     */
    @PostMapping("/surveillance/refresh")
    public ApiResponse<List<SurveillanceService.RefreshOutcome>> refreshSurveillance(
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(required = false) String codes) {
        LocalDate begin = require(start);
        LocalDate last = require(end);
        List<String> targets = new ArrayList<>();
        if (codes != null && !codes.trim().isEmpty()) {
            for (String raw : codes.split(",")) {
                if (!raw.trim().isEmpty()) {
                    targets.add(raw.trim());
                }
            }
        } else {
            targets.addAll(surveillanceService.trackedCodes(begin, last));
        }
        return ApiResponse.ok(surveillanceService.refresh(targets, begin, last));
    }

    /** 登录态里带的是账号 id（JwtAuthFilter 放进 principal），阵眼按它查各自的登记。 */
    private static Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    private static LocalDate require(String raw) {
        LocalDate date = parse(raw);
        if (date == null) {
            throw new MarketDataException("必须提供 start 与 end（yyyy-MM-dd）");
        }
        return date;
    }

    private static LocalDate parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            // 这里必须是中文：GlobalExceptionHandler 会把 message 原样弹到界面上
            throw new MarketDataException("日期格式应为 yyyy-MM-dd，收到：" + raw);
        }
    }
}
