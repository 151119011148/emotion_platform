package com.emotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.ZtPerf;
import com.emotion.mapper.ZtPerfMapper;
import com.emotion.market.PoolRow;

/**
 * {@code t_zt_perf} 的唯一出入口：写走「先删后插」（同 {@link PremiumTierStore}），
 * 读还原成逐只行 / code→今日涨跌幅 map。
 *
 * <p>这张表是 1 进 2 大面的<b>全样本</b>来源：三池只覆盖今天还触板的票，昨首板今天未触板
 * 而收盘深水区的票只能靠它。实时快照与日 K 回补共用 {@link #buildRows} 这一份归档逻辑，
 * 两条路径写出来的行必须同构。
 */
@Service
public class ZtPerfStore {

    private static final Logger log = LoggerFactory.getLogger(ZtPerfStore.class);

    public static final String SOURCE_QUOTE = "QUOTE";
    public static final String SOURCE_KBAR = "KBAR";
    public static final String SOURCE_BK = "BK";

    private final ZtPerfMapper mapper;

    public ZtPerfStore(ZtPerfMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 把昨日涨停池 + 每只票今日涨跌幅归成逐只行。<b>首板也保留</b>（lbc≤1 归一成 1），
     * 取不到价的票不进结果——调用方按 prev 池家数核对覆盖数，缺样本要能看见。
     */
    public static List<ZtPerf> buildRows(LocalDate date, List<PoolRow> prevLimitUp,
                                         Map<String, BigDecimal> pctByCode, String source) {
        List<ZtPerf> rows = new ArrayList<>();
        if (date == null || prevLimitUp == null || pctByCode == null) {
            return rows;
        }
        for (PoolRow row : prevLimitUp) {
            BigDecimal pct = row.getCode() == null ? null : pctByCode.get(row.getCode());
            if (pct == null) {
                continue;
            }
            ZtPerf perf = new ZtPerf();
            perf.setTradeDate(date);
            perf.setCode(row.getCode());
            perf.setName(row.getName());
            Integer lbc = row.getLbc();
            perf.setPrevConsecutive(lbc == null || lbc < 1 ? 1 : lbc);
            perf.setChangePct(pct);
            perf.setSource(source);
            rows.add(perf);
        }
        return rows;
    }

    /**
     * 一日一次重写。
     *
     * @param rows 当日逐只表现；空表意味着"这次一只价都没取到"，保留上一次数据而不是清空
     *             （清空会让"1 进 2 大面"从全样本退回炸板池下界，且看不出是数据被抹了）
     * @return 实际写入行数，-1 表示跳过
     */
    @Transactional(rollbackFor = Exception.class)
    public int replaceForDate(LocalDate date, List<ZtPerf> rows) {
        if (date == null || rows == null || rows.isEmpty()) {
            log.info("{} 无昨涨停逐只表现可写，保留上一次数据", date);
            return -1;
        }
        mapper.delete(new LambdaQueryWrapper<ZtPerf>().eq(ZtPerf::getTradeDate, date));
        mapper.insertBatch(rows);
        log.info("{} 昨涨停今日表现写入 {} 只（含首板，源 {}）", date, rows.size(), rows.get(0).getSource());
        return rows.size();
    }

    /** 读一日全部逐只行；没有记录返回空列表（调用方据此知道全样本缺席，计数只是下界）。 */
    public List<ZtPerf> read(LocalDate date) {
        if (date == null) {
            return new ArrayList<>();
        }
        return mapper.selectList(new LambdaQueryWrapper<ZtPerf>().eq(ZtPerf::getTradeDate, date));
    }

    /** code → 今日涨跌幅% 的快查形状，供 1 进 2 大面归属与成绩单补价使用。 */
    public Map<String, BigDecimal> readPctByCode(LocalDate date) {
        Map<String, BigDecimal> pct = new HashMap<>();
        for (ZtPerf row : read(date)) {
            if (row.getCode() != null && row.getChangePct() != null) {
                pct.put(row.getCode(), row.getChangePct());
            }
        }
        return pct;
    }
}
