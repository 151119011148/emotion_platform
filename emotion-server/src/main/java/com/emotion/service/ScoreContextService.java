package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.emotion.entity.DailyRecord;
import com.emotion.mapper.MarketStockMapper;
import com.emotion.market.PoolCounts;
import com.emotion.util.ManualOverride;
import com.emotion.util.ScoreInputs;
import com.emotion.util.TemperatureCalculator;
import com.emotion.vo.AnchorVO;
import com.emotion.vo.ScoreContextVO;
import com.emotion.vo.SurveillanceVO;

/**
 * 组装打分需要的公开输入：档位溢价（{@code t_premium_tier}）、盘面三池家数
 * （{@code t_market_stock} 一条聚合）、阵眼（{@code t_anchor} + 日 K）、
 * 监管名单（{@code t_surveillance} + 日 K）。
 *
 * <p>四样都是"每日公开事实"，跟谁复盘无关，所以谁存那天都该拿到同一个分。其中只有阵眼和监管名单
 * 两样要打网络，而 {@link com.emotion.util.TemperatureCalculator} 一行网络代码都没有——
 * 取数集中在这里，打分才能继续用数组单测。
 *
 * <p>公开输入取齐之后，最后一步把调用方那一行的<b>人工覆盖列</b>叠上去（{@link #applyManual}）：
 * 公开事实负责"谁拿到的是同一个分"，人工列负责"但那天我看到的不是这个数"。
 *
 * <p>取不到一律留 null 并把原因写进依据串，绝不兜成 0：那等于把"上游抖了一下"记成"今天确认极差"。
 * 一次复盘因此最多打 1 次档位表读 + 1 次家数聚合 + 每只阵眼 1 次日 K + 1 次日历日 K + 每只在列监管股 1 次日 K。
 */
@Service
public class ScoreContextService {

    private static final Logger log = LoggerFactory.getLogger(ScoreContextService.class);

    /** 不传日期时的"今天"按北京时间切：JVM 默认时区在别的机器上会算错交易日。 */
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    /**
     * 两列各自的宽度，留 5 字缓冲给依据串后面可能追加的标记。
     * 以前是一个 NOTE_MAX=200 通吃，surv_note 那 300 字就这么被静默砍掉了。
     */
    private static final int ANCHOR_NOTE_MAX = 295;
    private static final int SURV_NOTE_MAX = 495;

    private final PremiumTierStore premiumTierStore;
    private final MarketStockMapper marketStockMapper;
    private final AnchorMetricsService anchors;
    private final SurveillanceService surveillance;

    public ScoreContextService(PremiumTierStore premiumTierStore,
                              MarketStockMapper marketStockMapper,
                              AnchorMetricsService anchors,
                              SurveillanceService surveillance) {
        this.premiumTierStore = premiumTierStore;
        this.marketStockMapper = marketStockMapper;
        this.anchors = anchors;
        this.surveillance = surveillance;
    }

    public ScoreInputs forDate(Long userId, LocalDate date, DailyRecord record) {
        ScoreInputs in = ScoreInputs.empty();
        in.setPremiumTiers(premiumTierStore.read(date));
        fillPoolCounts(in, date);
        fillAnchor(in, userId, date);
        fillSurvival(in, date);
        applyManual(in, record);
        return in;
    }

    /**
     * 复盘页那块「子项读数」：把合并维拆开，每个子项各带自动值与算式。
     *
     * <p>传进去的 record 是 {@code null}，因此这里只有公开读数——他手改的那几格从表单来，
     * 不经过这条响应。少这一层，前端就没有"拉一次行情把手改覆盖成自动值"的路径可走。
     */
    public ScoreContextVO scoreContextVO(Long userId, LocalDate requestedDate) {
        LocalDate date = requestedDate != null ? requestedDate : LocalDate.now(CN);
        long began = System.currentTimeMillis();
        ScoreInputs in = forDate(userId, date, null);

        ScoreContextVO vo = new ScoreContextVO();
        vo.setTradeDate(date);
        PoolCounts pools = in.getPoolCounts();
        vo.setZtCount(pools == null ? null : pools.getZtCount());
        vo.setZbCount(pools == null ? null : pools.getZbCount());
        vo.setResealCount(pools == null ? null : pools.getResealCount());
        // record 给 null，炸板率那一支整支缺席，只剩两个家数口径——正是这一屏要摊开的两条
        TemperatureCalculator.BrokenDim broken = TemperatureCalculator.calcBrokenDim(null, in);
        vo.setSealedHomeRate(broken.getSealedHomeRate());
        vo.setResealRate(broken.getResealRate());
        vo.setSealedNote(broken.getSealedNote());
        vo.setResealNote(broken.getResealNote());
        vo.setAnchorScore(in.getAnchorScore());
        vo.setAnchorNote(in.getAnchorNote());
        vo.setSurvCount(in.getSurvCount());
        vo.setSurvPremium(in.getSurvPremium());
        vo.setSurvNote(in.getSurvNote());
        vo.setElapsedMs(System.currentTimeMillis() - began);
        return vo;
    }

    /**
     * 把这一行的人工覆盖叠到刚取出来的公开读数上。这里只管第 8/9 维：
     * 它们的人工值替换的就是"进分的那个数"本身（一个分、一组家数与均值），一换全换，
     * 叠在输入层最干净。
     *
     * <p>第 2/4 维不在这里叠，原因在 {@code TemperatureCalculator#calcBrokenDim} 那段注释上：
     * 那两维的人工值是"组均涨幅"和"家数百分数"，跟公开表里的逐档行、逐池家数不是同一个量，
     * 在这里叠就得让 {@link ScoreInputs} 同时装下家数和百分数两份能各自漂移的数。
     *
     * <p>依据串一律"人工在前、自动退成来路"：这条串是界面上唯一解释这个分从哪来的话，
     * 而自动那串的开头是"1 分｜…"，人工把分改了以后它还挂在前面，读的人只会认为系统在算错账。
     */
    static void applyManual(ScoreInputs in, DailyRecord record) {
        if (record == null) {
            return;
        }
        Integer manualAnchor = record.getManualAnchorScore();
        if (manualAnchor != null) {
            Integer score = ManualOverride.clampScore(manualAnchor);
            String head = score + " 分｜人工覆盖";
            if (!score.equals(manualAnchor)) {
                head = head + "（" + manualAnchor + " 越界，已夹到 " + score + "）";
            }
            in.setAnchorScore(score);
            in.setAnchorNote(cut(head + "｜自动值来路：" + in.getAnchorNote(), ANCHOR_NOTE_MAX));
        }

        Integer manualCount = record.getManualSurvCount();
        BigDecimal manualPremium = record.getManualSurvPremium();
        if (manualCount == null && manualPremium == null) {
            return;
        }
        Integer count = ManualOverride.pick(manualCount, in.getSurvCount());
        BigDecimal pct = ManualOverride.pick(manualPremium, in.getSurvPremium());
        in.setSurvCount(count);
        in.setSurvPremium(pct);

        StringBuilder head = new StringBuilder("第 9 维人工覆盖：进分 ");
        head.append(count == null ? "未填" : count + " 家").append("，今日溢价 ");
        head.append(pct == null ? "未填" : pct + "%");
        if (!TemperatureCalculator.survivalScored(count, pct)) {
            // 只填一格时最容易踩：溢价手改成一个负数，家数却还是 0，这一维整维没进分。
            head.append("｜家数与溢价缺一（或家数为 0）→ 这一维不进分");
        }
        in.setSurvNote(cut(head + "｜自动值来路：" + in.getSurvNote(), SURV_NOTE_MAX));
    }

    /**
     * 三池家数聚合。直接打 mapper 而不是绕 {@link StockPoolWriter}：那个类只承诺写，
     * 在这里读一次本地表既不打上游也不进缓存，重算多少天都只是每天一条 GROUP BY。
     */
    private void fillPoolCounts(ScoreInputs in, LocalDate date) {
        try {
            PoolCounts counts = marketStockMapper.countPools(date);
            in.setPoolCounts(counts == null || counts.isEmpty() ? null : counts);
        } catch (RuntimeException e) {
            // 留 null：第 4 维退回只看炸板率。把"读库抖了一下"写成 0% 封板率，等于凭空判一次崩盘。
            in.setPoolCounts(null);
            log.warn("盘面家数聚合失败 date={} 原因={}（第 4 维退回炸板率单子项）", date, e.toString());
        }
    }

    private void fillAnchor(ScoreInputs in, Long userId, LocalDate date) {
        try {
            AnchorVO vo = anchors.vo(userId, date);
            in.setAnchorScore(vo.getScore());
            in.setAnchorNote(cut(anchorNote(vo), ANCHOR_NOTE_MAX));
        } catch (RuntimeException e) {
            in.setAnchorScore(null);
            in.setAnchorNote(cut("阵眼取数异常：" + reason(e) + "（第 8 维未评，不是 0 分）", ANCHOR_NOTE_MAX));
            log.warn("第 8 维取数失败 user={} date={} 原因={}", userId, date, e.toString());
        }
    }

    /**
     * 依据串取"进分那一只"的话：多只在位时打分用的是最差的那只，卡片上必须能看到
     * 这个 0 分是谁撞出来的，否则这一维就成了一个无法追问的数。
     */
    static String anchorNote(AnchorVO vo) {
        Integer worst = vo.getScore();
        List<String> reasons = new ArrayList<>();
        if (vo.getItems() != null) {
            for (AnchorVO.Item item : vo.getItems()) {
                if (worst == null || (item.getScore() != null && item.getScore().equals(worst))) {
                    reasons.add(item.getReason());
                }
            }
        }
        String detail = reasons.isEmpty() ? vo.getNote() : join(reasons, " · ");
        return worst == null ? detail : worst + " 分｜" + detail;
    }

    private void fillSurvival(ScoreInputs in, LocalDate date) {
        // 先问库里有没有事件再决定要不要打网络：窗口内一条事件都没有，名单必然空，
        // 但那到底是"当天没票被监管"还是"这天的公告从没回补过"，只有事件表本身能回答。
        int events;
        try {
            events = surveillance.eventsInWindow(date);
        } catch (RuntimeException e) {
            in.setSurvCount(null);
            in.setSurvNote(cut("监管事件表读取异常：" + reason(e) + "（第 9 维未评）", SURV_NOTE_MAX));
            return;
        }
        if (events == 0) {
            in.setSurvCount(null);
            in.setSurvNote(cut(date + " 往前 45 天内监管事件表为空：未拉取，第 9 维不计入分母"
                    + "（不是「当天没有进分的监管股」）", SURV_NOTE_MAX));
            return;
        }
        try {
            SurveillanceVO vo = surveillance.vo(date);
            in.setSurvCount(vo.getCount());
            in.setSurvPremium(vo.getAvgPct());
            in.setSurvNote(cut(survNote(vo), SURV_NOTE_MAX));
        } catch (RuntimeException e) {
            in.setSurvCount(null);
            in.setSurvPremium(null);
            in.setSurvNote(cut("监管名单取数异常：" + reason(e) + "（第 9 维未评，不是 0 分）", SURV_NOTE_MAX));
            log.warn("第 9 维取数失败 date={} 原因={}", date, e.toString());
        }
    }

    /** 均值算式后面带上进分且剩余窗口最长的那一只，剩下的在面板里看，依据串装不下一整份名单。 */
    static String survNote(SurveillanceVO vo) {
        String text = vo.getNote();
        if (vo.getItems() != null) {
            for (SurveillanceVO.Item item : vo.getItems()) {
                if (item.isScored()) {
                    return text + " · 剩余最久 " + item.getName() + " " + item.getDescribe();
                }
            }
        }
        return text;
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(sep);
            }
            text.append(part);
        }
        return text.toString();
    }

    /** 上游异常常常没有 message，退化到类名，别在依据串里留一个 "null"。 */
    private static String reason(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
