package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.emotion.entity.DailyRecord;
import com.emotion.mapper.DailyRecordMapper;
import com.emotion.util.ScoreInputs;

/**
 * 定时任务那条录入路（{@link DailyRecordService#ensureObjectiveRecord}）：<b>只写客观数据</b>这句话
 * 落到列上到底是什么样。
 *
 * <p>三件容易写歪的事：
 * <ol>
 *   <li>没行时插的是空壳——十三项主观字段一格都不许有值，缺数也不许兜 0；</li>
 *   <li>有行时只重算派生列，他手填的主线/龙头/笔记/仓位/明日计划原样留着；</li>
 *   <li>改判过的阶段带（{@code stage_overridden=1}）保住，机器算出来的 stage 盖不上去——
 *       这正是它不能走 {@code createOrUpdate} 的原因：那条路的 {@code copyFields}
 *       else 分支会顺手把 {@code stage_overridden} 洗成 0。</li>
 * </ol>
 */
class DailyRecordServiceObjectiveTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    private DailyRecordMapper mapper;
    private ScoreContextService scoreContext;
    private MarketDailyStore marketDailyStore;
    private DailyRecordService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DailyRecordMapper.class);
        scoreContext = mock(ScoreContextService.class);
        marketDailyStore = mock(MarketDailyStore.class);
        service = new DailyRecordService(mapper, scoreContext, marketDailyStore);

        when(mapper.selectList(any())).thenReturn(new ArrayList<>());
        // 空 metrics = 一维都评不出来，正好用来验「缺数不兜 0」
        when(scoreContext.forDate(any(), any())).thenReturn(ScoreInputs.empty());
        when(marketDailyStore.getByDate(any())).thenReturn(null);
    }

    @Test
    void insertsObjectiveShellLeavingEverySubjectiveColumnNull() {
        when(mapper.selectOne(any())).thenReturn(null);

        boolean created = service.ensureObjectiveRecord(DAY);

        assertTrue(created, "当天没有行，应该是新建");
        ArgumentCaptor<DailyRecord> cap = ArgumentCaptor.forClass(DailyRecord.class);
        verify(mapper).insert(cap.capture());
        verify(mapper, never()).updateById(any());

        DailyRecord row = cap.getValue();
        assertEquals(DAY, row.getTradeDate());
        // 主观十三项：机器一格都不填
        assertNull(row.getMainTheme(), "主线");
        assertNull(row.getLeadingStock(), "龙头");
        assertNull(row.getLeadingStockStatus(), "龙头状态");
        assertNull(row.getMidCapStock(), "龙二");
        assertNull(row.getRotationNote(), "轮动笔记");
        assertNull(row.getReviewNote(), "复盘笔记");
        assertNull(row.getTomorrowPlan(), "明日计划");
        assertNull(row.getMyPositionPct(), "我的仓位");
        assertNull(row.getScoreTheme(), "题材评分");
        assertNull(row.getCompareNote(), "对比笔记");
        assertNull(row.getManualAnchorScore(), "manual_* 覆盖列");
        assertNull(row.getManualSectorLimitUpCount(), "manual_* 覆盖列");
        assertNull(row.getManualLadderCompleteScore(), "manual_* 覆盖列");
        // 引擎跑过了，但没数据就是没数据：不兜 0、不编阶段
        assertEquals(0, row.getScoredDims().intValue(), "一维都评不出来时 scored_dims=0");
        assertNull(row.getTemperature(), "无源时 temperature 为 null，不能兜 0");
        assertEquals("", row.getStage(), "无源时 stage 为空串，不给假阶段");
    }

    @Test
    void recalculatesExistingRowWithoutTouchingSubjectiveFields() {
        DailyRecord existing = existingRow();
        existing.setMainTheme("AI算力");
        existing.setLeadingStock("000001");
        existing.setReviewNote("今天的笔记");
        existing.setTomorrowPlan("明天的计划");
        existing.setMyPositionPct(new BigDecimal("30"));
        existing.setStageOverridden(0);
        when(mapper.selectOne(any())).thenReturn(existing);

        boolean created = service.ensureObjectiveRecord(DAY);

        assertFalse(created, "当天已有行，不该报新建");
        verify(mapper, never()).insert(any());
        verify(mapper).updateById(existing);
        assertEquals("AI算力", existing.getMainTheme());
        assertEquals("000001", existing.getLeadingStock());
        assertEquals("今天的笔记", existing.getReviewNote());
        assertEquals("明天的计划", existing.getTomorrowPlan());
        assertEquals(new BigDecimal("30"), existing.getMyPositionPct());
        // 没改判的日子，阶段带按机器口径重算（这里是无源=空串）
        assertEquals("", existing.getStage());
    }

    @Test
    void keepsManuallyOverriddenStageAndItsFlag() {
        DailyRecord existing = existingRow();
        existing.setStage("退潮");
        existing.setStageOverridden(1);
        when(mapper.selectOne(any())).thenReturn(existing);

        service.ensureObjectiveRecord(DAY);

        assertEquals("退潮", existing.getStage(), "改判过的阶段带不能被机器算出来的盖掉");
        assertEquals(1, existing.getStageOverridden().intValue(),
                "改判标记也要留着——createOrUpdate 的空请求会把它洗成 0，所以那条路不能用");
        verify(mapper).updateById(existing);
    }

    private static DailyRecord existingRow() {
        DailyRecord r = new DailyRecord();
        r.setId(99L);
        r.setTradeDate(DAY);
        return r;
    }
}
