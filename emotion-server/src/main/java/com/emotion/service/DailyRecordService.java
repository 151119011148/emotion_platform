package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.dto.DailyRecordRequest;
import com.emotion.entity.DailyRecord;
import com.emotion.mapper.DailyRecordMapper;
import com.emotion.util.CycleStageMachine;
import com.emotion.util.TemperatureCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

    public DailyRecordService(DailyRecordMapper dailyRecordMapper, ScoreContextService scoreContext) {
        this.dailyRecordMapper = dailyRecordMapper;
        this.scoreContext = scoreContext;
    }

    public DailyRecord createOrUpdate(Long userId, DailyRecordRequest req, Set<String> present) {
        LocalDate date = req.getTradeDate() != null ? req.getTradeDate() : today();

        DailyRecord existing = getByDate(userId, date);
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
        DailyRecord existing = getByDate(userId, date);
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
        DailyRecord record = getByDate(userId, date);
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
        CycleStageMachine.assign(all);
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
        List<DailyRecord> recent = dailyRecordMapper.selectList(
                new LambdaQueryWrapper<DailyRecord>()
                        .eq(DailyRecord::getUserId, userId)
                        .lt(DailyRecord::getTradeDate, date)
                        .orderByDesc(DailyRecord::getTradeDate)
                        .last("LIMIT 5"));

        // 溢价维吃公开档位表，不是请求里带的那个含首板标量；阵眼与监管名单同理——谁存那天都该得到同一个分。
        // 唯一的例外是 record 自己那八列 manual_*：公开读数负责默认值，他手改的那一格才是这一天的结论。
        TemperatureCalculator.calculate(record, recent, scoreContext.forDate(userId, date, record));

        if (!recent.isEmpty()) {
            record.setPrevTemperature(recent.get(0).getTemperature());
        }

        double temp = record.getTemperature() == null ? 0 : record.getTemperature().doubleValue();
        Double prev = record.getPrevTemperature() == null ? null : record.getPrevTemperature().doubleValue();
        // 缺维守卫：未评的维度已整维剔出分母，评不了几维的日子算出的读数只是残值的自我确认，
        // 这时候给阶段等于给操作建议，比不给更危险。
        int dims = record.getScoredDims() == null ? 0 : record.getScoredDims();
        if (dims < TemperatureCalculator.MIN_DIMS_FOR_STAGE) {
            record.setStage("");
            record.setStageDirection("");
        } else {
            record.setStage(TemperatureCalculator.determineStage(temp, prev));
            record.setStageDirection(TemperatureCalculator.determineDirection(temp, prev));
        }
    }

    public DailyRecord getToday(Long userId) {
        return dailyRecordMapper.selectOne(
                new LambdaQueryWrapper<DailyRecord>()
                        .eq(DailyRecord::getUserId, userId)
                        .eq(DailyRecord::getTradeDate, today()));
    }

    public DailyRecord getByDate(Long userId, LocalDate date) {
        return dailyRecordMapper.selectOne(
                new LambdaQueryWrapper<DailyRecord>()
                        .eq(DailyRecord::getUserId, userId)
                        .eq(DailyRecord::getTradeDate, date));
    }

    public List<DailyRecord> getRange(Long userId, LocalDate start, LocalDate end) {
        return dailyRecordMapper.selectList(
                new LambdaQueryWrapper<DailyRecord>()
                        .eq(DailyRecord::getUserId, userId)
                        .ge(DailyRecord::getTradeDate, start)
                        .le(DailyRecord::getTradeDate, end)
                        .orderByAsc(DailyRecord::getTradeDate));
    }

    public List<DailyRecord> getLatest(Long userId, int days) {
        return dailyRecordMapper.selectList(
                new LambdaQueryWrapper<DailyRecord>()
                        .eq(DailyRecord::getUserId, userId)
                        .orderByDesc(DailyRecord::getTradeDate)
                        .last("LIMIT " + days));
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
