package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.dto.DailyRecordRequest;
import com.emotion.entity.DailyRecord;
import com.emotion.entity.MarketDaily;
import com.emotion.mapper.DailyRecordMapper;
import com.emotion.util.BoardScoreCalculator;
import com.emotion.util.CycleStageMachine;
import com.emotion.util.ScoreInputs;
import com.emotion.vo.ScoreDetailVO;
import com.emotion.util.ScoringTree;
import com.emotion.util.TemperatureCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@Service
public class DailyRecordService {

    private static final Logger log = LoggerFactory.getLogger(DailyRecordService.class);

    /** 交易日历按北京时间切，JVM 默认时区不可信（前端用的是 UTC，两边会错位）。 */
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private static LocalDate today() {
        return LocalDate.now(CN);
    }

    private final DailyRecordMapper dailyRecordMapper;
    private final ScoreContextService scoreContext;
    private final MarketDailyStore marketDailyStore;

    public DailyRecordService(DailyRecordMapper dailyRecordMapper, ScoreContextService scoreContext,
                              MarketDailyStore marketDailyStore) {
        this.dailyRecordMapper = dailyRecordMapper;
        this.scoreContext = scoreContext;
        this.marketDailyStore = marketDailyStore;
    }

    public DailyRecord createOrUpdate(Long userId, DailyRecordRequest req, Set<String> present) {
        LocalDate date = req.getTradeDate() != null ? req.getTradeDate() : today();

        // 客观九数与用户无关：先落全局 t_market_daily（七数空值守卫、涨跌家数看键在场），
        // 再落主观行。下面 scoreAndPlace 的 fillMarketDefaults 会把同一天刚落的客观值合并进 carrier。
        marketDailyStore.upsertForm(date, req, present);

        DailyRecord existing = rawByDate(userId, date);
        DailyRecord record = existing != null ? existing : blank(userId, date);

        copyFields(record, req, present);
        scoreAndPlace(userId, date, record);

        save(userId, date, record, existing);
        return record;
    }

    /**
     * 复盘 md 导入专用。刻意不走 {@link #createOrUpdate}：
     * {@code copyFields} 的守卫要么看值非 null、要么看 JSON 键在不在场，
     * 而 md 的「键没写」和「键写了空」是两回事——走它就没法表达「这一格我写了空 = 清掉」。
     *
     * <p>mutator 拿到的是<b>从库里读出来的整行</b>，只允许改它点名的那几个字段——
     * 「键没写 = 不动」因此天然成立。派生列一律 ALWAYS 策略，
     * 拿一个只带 id 的半行去 update 会把九个分一并洗成 NULL。
     */
    public DailyRecord importManual(Long userId, LocalDate date, Consumer<DailyRecord> mutator) {
        DailyRecord existing = rawByDate(userId, date);
        DailyRecord record = existing != null ? existing : blank(userId, date);

        // 改判过的日子先记住人工结论：导入不接收「阶段」这个键，所以它没资格把改判盖掉。
        // 复盘页那条路（copyFields 之后 scoreAndPlace 无条件重写 stage）上的同类问题不在这里动，等你点头。
        boolean overridden = existing != null && existing.getStageOverridden() != null
                && existing.getStageOverridden() == 1 && !isBlank(existing.getStage());
        String manualStage = overridden ? existing.getStage() : null;

        mutator.accept(record);
        scoreAndPlace(userId, date, record);
        if (manualStage != null) {
            record.setStage(manualStage);
        }

        save(userId, date, record, existing);
        return record;
    }

    /**
     * 在一份<b>不落库</b>的副本上跑同一套打分。
     *
     * <p>为的是点确认之前就能看到「导入会把温度从 50.0 改成 52.9」——
     * 主线明确度是第 7 维，真的会动分子和分母，这个可见性是刻意的。
     */
    public DailyRecord scoredCopy(Long userId, LocalDate date, DailyRecord base) {
        DailyRecord copy = new DailyRecord();
        BeanUtils.copyProperties(base, copy);
        scoreAndPlace(userId, date, copy);
        return copy;
    }

    private void save(Long userId, LocalDate date, DailyRecord record, DailyRecord existing) {
        if (existing != null) {
            dailyRecordMapper.updateById(record);
        } else {
            dailyRecordMapper.insert(record);
        }
        resequence(userId);
    }

    private DailyRecord blank(Long userId, LocalDate date) {
        DailyRecord record = new DailyRecord();
        record.setUserId(userId);
        record.setTradeDate(date);
        return record;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 只重算派生列（九维分、温度、阶段、段号），人工填的那十三项一个字都不动。
     *
     * <p>那天没有记录就返回 null：重算不是录入，凭空造一行会把"这天没复盘"显示成"这天什么都没发生"。
     */
    public DailyRecord recalc(Long userId, LocalDate date) {
        DailyRecord record = rawByDate(userId, date);
        if (record == null) {
            return null;
        }
        scoreAndPlace(userId, date, record);
        dailyRecordMapper.updateById(record);
        resequence(userId);
        return record;
    }

    /** 按日期升序逐日重算：prevTemperature 读的是上一条，倒着跑会让前一天的温差取自一个还没更新的数。 */
    public int recalcAll(Long userId) {
        List<DailyRecord> all = dailyRecordMapper.selectList(
                new LambdaQueryWrapper<DailyRecord>()
                        .eq(DailyRecord::getUserId, userId)
                        .orderByAsc(DailyRecord::getTradeDate));
        for (DailyRecord record : all) {
            scoreAndPlace(userId, record.getTradeDate(), record);
            dailyRecordMapper.updateById(record);
        }
        resequence(userId);
        return all.size();
    }

    /**
     * 子段序号整条重排。
     *
     * <p>不能只改当天那一行：段号问的是"这是第几个退潮段"，答案在更早的记录里。
     * 回写用的是从库里读出来的整行对象——派生列全是 ALWAYS 策略，
     * new 一个只带 id + 两列的实体去 updateById 会把九个分一并清成 NULL。
     */
    private void resequence(Long userId) {
        List<DailyRecord> all = dailyRecordMapper.selectList(
                new LambdaQueryWrapper<DailyRecord>()
                        .eq(DailyRecord::getUserId, userId)
                        .orderByAsc(DailyRecord::getTradeDate));
        Integer[] beforeSeq = new Integer[all.size()];
        String[] beforePhase = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            beforeSeq[i] = all.get(i).getStageSeq();
            beforePhase[i] = all.get(i).getStagePhase();
        }
        // 7-stage CycleStageMachine 已下线（五维 4 带不再需要 "反弹/一阶段/二阶段" 的回合段号）。
        // 保留 resequence 链路以便 Stage 7 接入新的带变化段号；现在它是 no-op。
        int changed = 0;
        for (int i = 0; i < all.size(); i++) {
            DailyRecord row = all.get(i);
            boolean sameSeq = row.getStageSeq() == null ? beforeSeq[i] == null : row.getStageSeq().equals(beforeSeq[i]);
            boolean samePhase = row.getStagePhase() == null ? beforePhase[i] == null
                    : row.getStagePhase().equals(beforePhase[i]);
            if (!sameSeq || !samePhase) {
                dailyRecordMapper.updateById(row);
                changed++;
            }
        }
        if (changed > 0) {
            log.info("子段重排：{} 账号 {} 行有变", userId, changed);
        }
    }

    private void scoreAndPlace(Long userId, LocalDate date, DailyRecord record) {
        // 客观九数已与用户隔离：打分前从全局 t_market_daily 回填 carrier 上还空着的客观字段。
        // carrier 上非空的（本次表单/md 刚提交的）保持不动——upsert 已先落库，两者本就是同一份值。
        fillMarketDefaults(record, date);

        // LIMIT 20 covers both the old 5-record window and the new 20-day turnover baseline.
        // LadderMetricsService reuses this list from ScoreContextService internally; keeping 20 here is harmless
        // for the legacy path (only reads the most recent).
        List<DailyRecord> recent = dailyRecordMapper.selectList(
                new LambdaQueryWrapper<DailyRecord>()
                        .eq(DailyRecord::getUserId, userId)
                        .lt(DailyRecord::getTradeDate, date)
                        .orderByDesc(DailyRecord::getTradeDate)
                        .last("LIMIT 20"));

        // 公开读数一次性装配：档位溢价 / 盘面三池 / 阵眼 / 监管 / 五维 metrics；人工 manual_* 叠在最后。
        ScoreInputs in = scoreContext.forDate(userId, date, record);

        // 1) 旧 9 维引擎：仍写 score_height…theme / anchor_score / surv_* / sealed_home_rate 等展示列，
        //    以及临时的 total_score/temperature。Stage 7 前保留，之后随前端一起下线。
        TemperatureCalculator.calculate(record, recent, in);

        if (!recent.isEmpty()) {
            record.setPrevTemperature(recent.get(0).getTemperature());
        }

        // 2) 五维引擎：覆写 total_score / temperature / stage / scored_dims，并写入新增的
        //    score_market…anchor + signal_flags + forced_ebb。也就是说温度计读数的权威口径已切到五维。
        applyFiveDimScore(record, in);
    }

    /**
     * 五维双层引擎：{@link ScoreContextService} 装配好的树 + metrics 交给 {@link BoardScoreCalculator} 纯函数求值，
     * 然后把 5 维分/总分/温度/信号/强制退潮/阶段带写回 record。旧 9 维展示列（score_height…theme、
     * anchor/surv/sealed_home…）不删，仍留着上一轮的值作历史留痕；只是不再参与总分。
     *
     * <p>prev_temperature 直接沿用 {@link #scoreAndPlace} 安排的上一条写入（已是五维口径），方向判定才不会拿
     * 旧 9 维温度对新 5 维温度。
     */
    static void applyFiveDimScore(DailyRecord record, ScoreInputs in) {
        ScoringTree tree = in.getScoringTree() != null ? in.getScoringTree() : BoardScoreCalculator.builtinTree();
        BoardScoreCalculator.Result r = BoardScoreCalculator.evaluate(tree, in.getMetrics());

        record.setScoreMarket(r.getDimScores().get("market"));
        record.setScoreThemeMain(r.getDimScores().get("theme_main"));
        record.setScoreBoard(r.getDimScores().get("board"));
        record.setScoreFirst(r.getDimScores().get("first"));
        record.setScoreAnchor(r.getDimScores().get("anchor"));
        record.setSignalFlags(r.getSignalFlags().isEmpty() ? null : String.join(",", r.getSignalFlags()));
        record.setForcedEbb(r.isForcedEbb() ? 1 : 0);
        record.setForcedEbbReason(r.getForcedEbbReason());

        BigDecimal total = r.getTotal();
        record.setScoredDims(r.getDimScores().size());
        record.setTotalScore(total == null ? null : total.setScale(0, RoundingMode.HALF_UP).intValue());
        record.setTemperature(total);

        // stage 仍然落 4 带（高潮/发酵/混沌/退潮）或 "退潮(强制)"；无 total 时空串，不给阶段。
        String stage = r.getStage();
        record.setStage(stage == null ? "" : stage);

        // 方向：与 prev_temperature 相比。>= +3 上升、<= -3 下降、其他横盘；无可比则空串。
        BigDecimal prev = record.getPrevTemperature();
        if (total == null || prev == null) {
            record.setStageDirection("");
        } else {
            BigDecimal delta = total.subtract(prev);
            if (delta.compareTo(new BigDecimal("3")) >= 0) {
                record.setStageDirection("上升");
            } else if (delta.compareTo(new BigDecimal("-3")) <= 0) {
                record.setStageDirection("下降");
            } else {
                record.setStageDirection("横盘");
            }
        }
    }

    /**
     * Stage 9 只读端点：给定日期现装 metrics + tree 走一遍引擎，返回整棵 eval 树 + 结构信号 + 原始读数快照。
     * 不写库；改一 sub 权重或一 ladder 阈值后刷新即可反映（真数据驱动）。
     */
    public ScoreDetailVO scoreDetail(Long userId, LocalDate date) {
        DailyRecord record = rawByDate(userId, date);
        DailyRecord base = record != null ? record : blank(userId, date);
        // 即使这天没有主观行，五维明细也要能看到全局客观读数（涨停/量能/红盘率来自 t_market_daily）。
        fillMarketDefaults(base, date);
        ScoreInputs in = scoreContext.forDate(userId, date, base);
        ScoringTree tree = in.getScoringTree();
        boolean fromDb = tree != null;
        if (tree == null) {
            tree = BoardScoreCalculator.builtinTree();
        }
        BoardScoreCalculator.Result r = BoardScoreCalculator.evaluate(tree, in.getMetrics());

        ScoreDetailVO vo = new ScoreDetailVO();
        vo.setTradeDate(date);
        vo.setModelKey(tree.getModelKey());
        vo.setDims(r.getDimEvals());
        vo.setTotal(r.getTotal());
        vo.setSignalFlags(r.getSignalFlags());
        vo.setForcedEbb(r.isForcedEbb());
        vo.setForcedEbbReason(r.getForcedEbbReason());
        vo.setSource(fromDb ? "DB" : "BUILTIN");
        if (in.getMetrics() != null) {
            vo.setMetrics(new java.util.LinkedHashMap<>(in.getMetrics()));
        }
        if (in.getMetricNotes() != null) {
            vo.setNotes(new java.util.ArrayList<>(in.getMetricNotes().values()));
        }
        return vo;
    }


    public DailyRecord getToday(Long userId) {
        return mergeMarket(rawToday(userId));
    }

    private DailyRecord rawToday(Long userId) {
        return dailyRecordMapper.selectOne(
                new LambdaQueryWrapper<DailyRecord>()
                        .eq(DailyRecord::getUserId, userId)
                        .eq(DailyRecord::getTradeDate, today()));
    }

    /**
     * 该用户某天的主观行（不含客观日表数据）。内部写链路用它判断"这天存没存过复盘"，
     * 不要用 {@link #viewByDate}：那个接口在没有主观行时会返回一个 id=null 的客观合成行。
     */
    public DailyRecord rawByDate(Long userId, LocalDate date) {
        return dailyRecordMapper.selectOne(
                new LambdaQueryWrapper<DailyRecord>()
                        .eq(DailyRecord::getUserId, userId)
                        .eq(DailyRecord::getTradeDate, date));
    }

    /**
     * 读接口主力：用户主观行 + 全局客观九数合并。没有主观行时返回 null——
     * "这天没复盘"在历史/曲线/导出里仍按缺一天处理；只想看客观盘面用 {@link #viewByDate}。
     */
    public DailyRecord getByDate(Long userId, LocalDate date) {
        return mergeMarket(rawByDate(userId, date));
    }

    /**
     * 复盘页/五维明细用的视图：没有主观行但 t_market_daily 有这天的客观数据时，
     * 返回 id=null 的合成 carrier（前端据此走 create 路径保存主观内容）；两边都没有才是 null。
     */
    public DailyRecord viewByDate(Long userId, LocalDate date) {
        DailyRecord row = rawByDate(userId, date);
        MarketDaily market = marketDailyStore.getByDate(date);
        if (row == null && market == null) {
            return null;
        }
        DailyRecord view = row != null ? row : blank(userId, date);
        mergeMarket(view, market);
        return view;
    }

    /**
     * 把 t_market_daily 当天的客观九数合并进出参 carrier（已在 carrier 上的非空值不覆盖）。
     * 读接口与打分前回填共用这一份映射，保证"客观只有一份真值"。
     */
    private DailyRecord mergeMarket(DailyRecord record) {
        if (record == null) {
            return null;
        }
        mergeMarket(record, marketDailyStore.getByDate(record.getTradeDate()));
        return record;
    }

    /** 打分前回填：只填 carrier 上还空着的客观格，本次请求刚提交的值（含显式清空）原样保留。 */
    private void fillMarketDefaults(DailyRecord record, LocalDate date) {
        if (record == null) {
            return;
        }
        mergeMarket(record, marketDailyStore.getByDate(date));
    }

    /**
     * 逐格 null 填充：永远不拿客观值覆盖 carrier 上已有的值。
     * 保存路径上 carrier 的客观格就是刚写进 t_market_daily 的那份；读路径上它们来自上一次合并。
     */
    private static void mergeMarket(DailyRecord r, MarketDaily m) {
        if (r == null || m == null) {
            return;
        }
        if (r.getMaxConsecutiveLimit() == null) r.setMaxConsecutiveLimit(m.getMaxConsecutiveLimit());
        if (r.getLimitUpCount() == null) r.setLimitUpCount(m.getLimitUpCount());
        if (r.getLimitDownCount() == null) r.setLimitDownCount(m.getLimitDownCount());
        if (r.getUpCount() == null) r.setUpCount(m.getUpCount());
        if (r.getDownCount() == null) r.setDownCount(m.getDownCount());
        if (r.getYesterdayLimitPremium() == null) r.setYesterdayLimitPremium(m.getYesterdayLimitPremium());
        if (r.getBrokenBoardRate() == null) r.setBrokenBoardRate(m.getBrokenBoardRate());
        if (r.getBigLossCount() == null) r.setBigLossCount(m.getBigLossCount());
        if (r.getTotalVolume() == null) r.setTotalVolume(m.getTotalVolume());
    }

    /** 批量合并，避免逐行查一次客观表。 */
    private List<DailyRecord> mergeMarketAll(List<DailyRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        List<LocalDate> dates = new java.util.ArrayList<>();
        for (DailyRecord r : rows) {
            if (r.getTradeDate() != null) {
                dates.add(r.getTradeDate());
            }
        }
        if (dates.isEmpty()) {
            return rows;
        }
        Map<LocalDate, MarketDaily> byDate = marketDailyStore.mapByDates(dates);
        for (DailyRecord r : rows) {
            mergeMarket(r, byDate.get(r.getTradeDate()));
        }
        return rows;
    }

    public List<DailyRecord> getRange(Long userId, LocalDate start, LocalDate end) {
        return mergeMarketAll(dailyRecordMapper.selectList(
                new LambdaQueryWrapper<DailyRecord>()
                        .eq(DailyRecord::getUserId, userId)
                        .ge(DailyRecord::getTradeDate, start)
                        .le(DailyRecord::getTradeDate, end)
                        .orderByAsc(DailyRecord::getTradeDate)));
    }

    public List<DailyRecord> getLatest(Long userId, int days) {
        return mergeMarketAll(dailyRecordMapper.selectList(
                new LambdaQueryWrapper<DailyRecord>()
                        .eq(DailyRecord::getUserId, userId)
                        .orderByDesc(DailyRecord::getTradeDate)
                        .last("LIMIT " + days)));
    }

    /**
     * 表单 → 这一行。三套语义，分界是列的更新策略：
     *
     * <p>① 上面那七格行情读数是空值守卫：发 null 和不发一样，都不动。
     *
     * <p>② 中间这十二格（涨跌家数、我的仓位、对照、八格 {@code manual_*}）列都是 ALWAYS 策略，
     * 所以<b>看的是键在不在场，不是值是不是 null</b>：在场就照发（发 null 是「这格我清回未填 /
     * 退回自动值」，那是真意图），不在场一个字都不动。这两种从 DTO 上分不出来——反序列化出来
     * 都是 null——所以键集合只能从原始 body 拿。少了这道守卫，页面上一删块，
     * md 导入存进去的涨跌家数就会在下一次「更新记录」时被半截请求洗成 NULL。
     *
     * <p>③ 下面那几串（主线 / 龙头 / 轮动 / 明日计划 / 复盘笔记）是 md 导入作者的地盘，
     * 保持空值守卫：页面不发这个键 = 不动它已经写进去的值。
     */
    static void copyFields(DailyRecord record, DailyRecordRequest req, Set<String> present) {
        if (req.getMaxConsecutiveLimit() != null) record.setMaxConsecutiveLimit(req.getMaxConsecutiveLimit());
        if (req.getLimitUpCount() != null) record.setLimitUpCount(req.getLimitUpCount());
        if (req.getLimitDownCount() != null) record.setLimitDownCount(req.getLimitDownCount());
        if (req.getYesterdayLimitPremium() != null) record.setYesterdayLimitPremium(req.getYesterdayLimitPremium());
        if (req.getBrokenBoardRate() != null) record.setBrokenBoardRate(req.getBrokenBoardRate());
        if (req.getBigLossCount() != null) record.setBigLossCount(req.getBigLossCount());
        if (req.getTotalVolume() != null) record.setTotalVolume(req.getTotalVolume());
        if (req.getScoreTheme() != null) record.setScoreTheme(req.getScoreTheme());

        if (present.contains("upCount")) record.setUpCount(req.getUpCount());
        if (present.contains("downCount")) record.setDownCount(req.getDownCount());
        if (present.contains("myPositionPct")) record.setMyPositionPct(req.getMyPositionPct());
        if (present.contains("compareNote")) {
            record.setCompareNote(isBlank(req.getCompareNote()) ? null : req.getCompareNote());
        }
        if (present.contains("manualSealedHomeRate")) record.setManualSealedHomeRate(req.getManualSealedHomeRate());
        if (present.contains("manualResealRate")) record.setManualResealRate(req.getManualResealRate());
        if (present.contains("manualPremiumLowPct")) record.setManualPremiumLowPct(req.getManualPremiumLowPct());
        if (present.contains("manualPremiumMidPct")) record.setManualPremiumMidPct(req.getManualPremiumMidPct());
        if (present.contains("manualPremiumHighPct")) record.setManualPremiumHighPct(req.getManualPremiumHighPct());
        if (present.contains("manualAnchorScore")) record.setManualAnchorScore(req.getManualAnchorScore());
        if (present.contains("manualSurvCount")) record.setManualSurvCount(req.getManualSurvCount());
        if (present.contains("manualSurvPremium")) record.setManualSurvPremium(req.getManualSurvPremium());
        if (present.contains("manualSectorLimitUpCount")) record.setManualSectorLimitUpCount(req.getManualSectorLimitUpCount());
        if (present.contains("manualLadderCompleteScore")) record.setManualLadderCompleteScore(req.getManualLadderCompleteScore());
        if (present.contains("manualSectorPremiumPct")) record.setManualSectorPremiumPct(req.getManualSectorPremiumPct());
        if (present.contains("manualThemePersistenceDays")) record.setManualThemePersistenceDays(req.getManualThemePersistenceDays());
        if (present.contains("manualTopHighTurnoverPct")) record.setManualTopHighTurnoverPct(req.getManualTopHighTurnoverPct());
        if (present.contains("manualFirstPremiumPct")) record.setManualFirstPremiumPct(req.getManualFirstPremiumPct());
        if (present.contains("manualFirstSealedRate")) record.setManualFirstSealedRate(req.getManualFirstSealedRate());
        if (present.contains("manualTopHighBreak")) record.setManualTopHighBreak(req.getManualTopHighBreak());
        if (present.contains("manualAnchorSupervisionDiscount")) record.setManualAnchorSupervisionDiscount(req.getManualAnchorSupervisionDiscount());
        if (present.contains("manualAmountGatherPct")) record.setManualAmountGatherPct(req.getManualAmountGatherPct());

        if (req.getMainTheme() != null) record.setMainTheme(req.getMainTheme());
        if (req.getLeadingStock() != null) record.setLeadingStock(req.getLeadingStock());
        if (req.getLeadingStockStatus() != null) record.setLeadingStockStatus(req.getLeadingStockStatus());
        if (req.getMidCapStock() != null) record.setMidCapStock(req.getMidCapStock());

        if (req.getRotationNote() != null) record.setRotationNote(req.getRotationNote());
        if (req.getReviewNote() != null) record.setReviewNote(req.getReviewNote());
        if (req.getTomorrowPlan() != null) record.setTomorrowPlan(req.getTomorrowPlan());

        if (req.getStageOverridden() != null && req.getStageOverridden() == 1
                && req.getStage() != null) {
            record.setStage(req.getStage());
            record.setStageOverridden(1);
        } else {
            record.setStageOverridden(0);
        }
    }
}
