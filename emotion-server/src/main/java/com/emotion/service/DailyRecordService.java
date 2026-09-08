package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.dto.DailyRecordRequest;
import com.emotion.entity.DailyRecord;
import com.emotion.mapper.DailyRecordMapper;
import com.emotion.util.CycleStageMachine;
import com.emotion.util.SectionNotes;
import com.emotion.util.TemperatureCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

    public DailyRecord createOrUpdate(Long userId, DailyRecordRequest req) {
        LocalDate date = req.getTradeDate() != null ? req.getTradeDate() : today();

        DailyRecord existing = getByDate(userId, date);
        DailyRecord record = existing != null ? existing : blank(userId, date);

        copyFields(record, req);
        scoreAndPlace(userId, date, record);

        save(userId, date, record, existing);
        return record;
    }

    /**
     * 复盘 md 导入专用。刻意不走 {@link #createOrUpdate}：
     * {@code copyFields} 是 {@code if (req.getX() != null)} 的空值守卫，
     * 走它就没法表达「这一格我写了空 = 清掉」，而 md 里「键没写」和「键写了空」是两回事。
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

    private void copyFields(DailyRecord record, DailyRecordRequest req) {
        if (req.getMaxConsecutiveLimit() != null) record.setMaxConsecutiveLimit(req.getMaxConsecutiveLimit());
        if (req.getLimitUpCount() != null) record.setLimitUpCount(req.getLimitUpCount());
        if (req.getLimitDownCount() != null) record.setLimitDownCount(req.getLimitDownCount());
        if (req.getYesterdayLimitPremium() != null) record.setYesterdayLimitPremium(req.getYesterdayLimitPremium());
        if (req.getBrokenBoardRate() != null) record.setBrokenBoardRate(req.getBrokenBoardRate());
        if (req.getBigLossCount() != null) record.setBigLossCount(req.getBigLossCount());
        if (req.getTotalVolume() != null) record.setTotalVolume(req.getTotalVolume());
        if (req.getScoreTheme() != null) record.setScoreTheme(req.getScoreTheme());

        // 这十二格是"表单也能写"的那几格（原先只有 md 导入会写涨跌家数）。列都是 ALWAYS 策略，
        // 所以表单留空必须是"清回未填"——这里不能再加 null 守卫，否则那几格永远清不掉。
        // 下面八格 manual_* 同理：留空的意思是"这格我不再覆盖，退回自动值"，不是"别动"。
        // 反向的坑（半截请求把已存的数洗成空）由前端挡：没读回这天的行就不会发这批键。
        record.setUpCount(req.getUpCount());
        record.setDownCount(req.getDownCount());
        record.setMyPositionPct(req.getMyPositionPct());
        record.setCompareNote(isBlank(req.getCompareNote()) ? null : req.getCompareNote());
        record.setManualSealedHomeRate(req.getManualSealedHomeRate());
        record.setManualResealRate(req.getManualResealRate());
        record.setManualPremiumLowPct(req.getManualPremiumLowPct());
        record.setManualPremiumMidPct(req.getManualPremiumMidPct());
        record.setManualPremiumHighPct(req.getManualPremiumHighPct());
        record.setManualAnchorScore(req.getManualAnchorScore());
        record.setManualSurvCount(req.getManualSurvCount());
        record.setManualSurvPremium(req.getManualSurvPremium());

        if (req.getMainTheme() != null) record.setMainTheme(req.getMainTheme());
        if (req.getLeadingStock() != null) record.setLeadingStock(req.getLeadingStock());
        if (req.getLeadingStockStatus() != null) record.setLeadingStockStatus(req.getLeadingStockStatus());
        if (req.getMidCapStock() != null) record.setMidCapStock(req.getMidCapStock());

        if (req.getRotationNote() != null) record.setRotationNote(req.getRotationNote());
        if (req.getReviewNote() != null) record.setReviewNote(req.getReviewNote());
        if (req.getTomorrowPlan() != null) record.setTomorrowPlan(req.getTomorrowPlan());
        // 只有请求真的带了这个键才动：不带键的提交（比如只改阶段改判）不该把他存的判断文字洗掉。
        if (req.getDocNotes() != null) record.setDocNotes(SectionNotes.toJson(req.getDocNotes()));

        if (req.getStageOverridden() != null && req.getStageOverridden() == 1
                && req.getStage() != null) {
            record.setStage(req.getStage());
            record.setStageOverridden(1);
        } else {
            record.setStageOverridden(0);
        }
    }
}
