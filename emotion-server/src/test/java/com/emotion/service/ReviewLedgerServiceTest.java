package com.emotion.service;

import com.emotion.dto.PositionRequest;
import com.emotion.dto.PredictionRequest;
import com.emotion.entity.Prediction;
import com.emotion.entity.Position;
import com.emotion.entity.Stock;
import com.emotion.mapper.StockMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复盘页两张台账的校验与落库口径。
 *
 * <p>钉三件事：<b>一次抛全</b>（五行的台账改一个看一个是没道理的）、<b>清空可达</b>
 * （空数组必须真的把那天清光，这是 md 那条路做不到的事，也是开这个入口的理由之一）、
 * 以及<b>名字以 t_stock 为准、浮动%手记值优先</b>（他记的可能是含费后的数）。
 */
class ReviewLedgerServiceTest {

    private static final Long USER = 2L;
    private static final LocalDate DATE = LocalDate.of(2026, 9, 4);

    /** 代码表只认这三只，其余一律"不在 A股代码表里"。 */
    private static StockMapper stockMapper() {
        final Map<String, String> names = new LinkedHashMap<>();
        names.put("002909", "集泰股份");
        names.put("002712", "竞业达");
        names.put("603221", "爱丽家居");
        return (StockMapper) Proxy.newProxyInstance(
                StockMapper.class.getClassLoader(),
                new Class<?>[]{StockMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        List<Stock> out = new ArrayList<>();
                        for (Map.Entry<String, String> e : names.entrySet()) {
                            Stock s = new Stock();
                            s.setCode(e.getKey());
                            s.setName(e.getValue());
                            out.add(s);
                        }
                        return out;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    // ---- 持仓 ----

    @Test
    void positionRowUsesCodeTableForTheNameAndStampsOwnerAndDate() {
        RecordingPositionStore store = new RecordingPositionStore();
        PositionRequest r = position("002909", "12.30", "13.40", null, "遵守");
        // 手打的错名不算数：台账和导入页不能给出两个名字
        r.setStockName("随便打的");

        assertEquals(1, service(store, new RecordingPredictionStore()).savePositions(
                USER, DATE, Arrays.asList(r)));

        Position p = only(store.rows);
        assertEquals(USER, p.getUserId());
        assertEquals(DATE, p.getTradeDate());
        assertEquals("集泰股份", p.getStockName());
        assertEquals(new BigDecimal("12.30"), p.getCostPrice());
        assertEquals(new BigDecimal("13.40"), p.getCurrentPrice());
        assertEquals(new BigDecimal("8.94"), p.getFloatPct());
        assertEquals("遵守", p.getDiscipline());
    }

    /** 手记的浮动%优先于反推：含费成本算出来的数跟现价除出来的本来就不该相等。 */
    @Test
    void handwrittenFloatPctBeatsTheDerivedOne() {
        RecordingPositionStore store = new RecordingPositionStore();

        service(store, new RecordingPredictionStore()).savePositions(
                USER, DATE, Arrays.asList(position("002909", "12.30", "13.40", "8.11", null)));

        assertEquals(new BigDecimal("8.11"), only(store.rows).getFloatPct());
    }

    /** 只有留空才反推；成本为 0 时不反推（除不动），宁给空也不给个荒谬数。 */
    @Test
    void floatPctIsLeftBlankWhenItCannotBeDerived() {
        RecordingPositionStore store = new RecordingPositionStore();

        service(store, new RecordingPredictionStore()).savePositions(
                USER, DATE, Arrays.asList(position("002909", null, "13.40", null, null),
                        position("002712", "0", "13.40", null, null)));

        List<Position> rows = store.rows.get(0);
        assertNull(rows.get(0).getFloatPct());
        assertNull(rows.get(1).getFloatPct());
    }

    /** 空数组 = "这天清仓了"，必须真的落到 store 变成一次整日删除。 */
    @Test
    void emptyListClearsTheWholeDay() {
        RecordingPositionStore store = new RecordingPositionStore();

        assertEquals(0, service(store, new RecordingPredictionStore())
                .savePositions(USER, DATE, new ArrayList<PositionRequest>()));

        assertEquals(1, store.calls);
        assertEquals(1, store.rows.size());
        assertTrue(store.rows.get(0).isEmpty());
    }

    /** 全空行是"点了加一行又没填"，不该要求代码、也不该占一行。 */
    @Test
    void blankRowsAreSkippedNotRejected() {
        RecordingPositionStore store = new RecordingPositionStore();

        assertEquals(1, service(store, new RecordingPredictionStore()).savePositions(
                USER, DATE, Arrays.asList(new PositionRequest(), null,
                        position("002909", "12.30", "13.40", null, null))));

        assertEquals(1, store.rows.get(0).size());
    }

    /** 坏行一次说全：改一处存一次再看到下一处，五行的台账能把人耗死。 */
    @Test
    void everyBadRowIsReportedInOneThrowAndNothingIsWritten() {
        RecordingPositionStore store = new RecordingPositionStore();
        ReviewLedgerService svc = service(store, new RecordingPredictionStore());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> svc.savePositions(
                USER, DATE, Arrays.asList(
                        position("2909", null, null, null, null),
                        position("600000", null, null, null, null),
                        position("002909", "12.30", "13.40", null, "应该遵守"))));

        String msg = e.getMessage();
        assertTrue(msg.contains("第 1 行") && msg.contains("6 位数字"), msg);
        assertTrue(msg.contains("第 2 行") && msg.contains("600000"), msg);
        assertTrue(msg.contains("第 3 行") && msg.contains("遵守/违约/待执行"), msg);
        assertEquals(0, store.calls);
    }

    @Test
    void duplicateCodeInOneBatchIsRejected() {
        RecordingPositionStore store = new RecordingPositionStore();
        ReviewLedgerService svc = service(store, new RecordingPredictionStore());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> svc.savePositions(
                USER, DATE, Arrays.asList(position("002909", null, null, null, null),
                        position("002909", "1.00", "2.00", null, null))));

        assertTrue(e.getMessage().contains("已经列过一次"), e.getMessage());
        assertEquals(0, store.calls);
    }

    /** 负价格是填错了，不是行情——库里 DECIMAL 存不下，别让它悄悄进去。 */
    @Test
    void negativePriceIsRejected() {
        RecordingPositionStore store = new RecordingPositionStore();
        ReviewLedgerService svc = service(store, new RecordingPredictionStore());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> svc.savePositions(
                USER, DATE, Arrays.asList(position("002909", "-1.00", null, null, null))));

        assertTrue(e.getMessage().contains("成本不能是负数"), e.getMessage());
        assertEquals(0, store.calls);
    }

    // ---- 预判 / 对答案 ----

    /** 一张表两种 kind 一起存：PLAN 带概率、ANSWER 带结果，各自不碰对方的列。 */
    @Test
    void oneSubmitCarriesBothKinds() {
        RecordingPredictionStore store = new RecordingPredictionStore();

        assertEquals(2, service(new RecordingPositionStore(), store).savePredictions(
                USER, DATE, Arrays.asList(
                        plan("退潮延续", 55, "竞业达低开低走+跌停≥20"),
                        answer("路径二", "落空", "跌停扩至 17 家"))));

        Set<String> kinds = store.kinds.get(0);
        assertTrue(kinds.contains(Prediction.KIND_PLAN) && kinds.contains(Prediction.KIND_ANSWER),
                kinds.toString());

        List<Prediction> rows = store.rows.get(0);
        assertEquals(USER, rows.get(0).getUserId());
        assertEquals(DATE, rows.get(0).getTradeDate());
        assertEquals(new Integer(55), rows.get(0).getProb());
        assertEquals("竞业达低开低走+跌停≥20", rows.get(0).getConditionText());
        assertNull(rows.get(0).getResult());
        assertEquals("落空", rows.get(1).getResult());
        assertEquals("跌停扩至 17 家", rows.get(1).getResultNote());
        assertNull(rows.get(1).getProb());
    }

    /** 两批都空也要把两种 kind 一起点名删——这才让"清掉那天的预判"在前端可达。 */
    @Test
    void clearingPredictionsNamesBothKinds() {
        RecordingPredictionStore store = new RecordingPredictionStore();

        assertEquals(0, service(new RecordingPositionStore(), store)
                .savePredictions(USER, DATE, new ArrayList<PredictionRequest>()));

        assertEquals(Arrays.asList(Prediction.KIND_PLAN, Prediction.KIND_ANSWER),
                new ArrayList<>(store.kinds.get(0)));
        assertTrue(store.rows.get(0).isEmpty());
    }

    @Test
    void predictionRowsAreValidatedOneShotToo() {
        RecordingPredictionStore store = new RecordingPredictionStore();
        ReviewLedgerService svc = service(new RecordingPositionStore(), store);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> svc.savePredictions(
                USER, DATE, Arrays.asList(
                        plan("路径一", null, null),
                        plan("路径二", 130, null),
                        answer("路径三", "差一点", null),
                        answer("路径四", "命中", null),
                        answer("路径四", "落空", null))));

        String msg = e.getMessage();
        assertTrue(msg.contains("第 1 行") && msg.contains("概率必填"), msg);
        assertTrue(msg.contains("第 2 行") && msg.contains("0-100"), msg);
        assertTrue(msg.contains("第 3 行") && msg.contains("命中/落空/部分/违约"), msg);
        assertTrue(msg.contains("第 5 行") && msg.contains("撞车"), msg);
        assertEquals(0, store.calls);
    }

    /** kind 打错、名字没填，都是接不上第二天的错——提前拦住比导出里空一行好。 */
    @Test
    void kindAndNameAreRequired() {
        RecordingPredictionStore svcStore = new RecordingPredictionStore();
        ReviewLedgerService svc = service(new RecordingPositionStore(), svcStore);

        PredictionRequest noKind = new PredictionRequest();
        noKind.setName("路径一");
        PredictionRequest noName = plan(null, 40, null);

        assertThrows(IllegalArgumentException.class,
                () -> svc.savePredictions(USER, DATE, Arrays.asList(noKind)));
        assertThrows(IllegalArgumentException.class,
                () -> svc.savePredictions(USER, DATE, Arrays.asList(noName)));
        assertEquals(0, svcStore.calls);
    }

    // ---- 边界 ----

    /**
     * 校验之外没别的事要做：落库仍然用导入器那同一个 store 原语。
     * 这条钉住"两个入口、一套语义"，将来谁给台账另写一份 insert 就会被这里拦住。
     */
    @Test
    void bothLedgersGoThroughTheImportPrimitive() {
        RecordingPositionStore positions = new RecordingPositionStore();
        RecordingPredictionStore predictions = new RecordingPredictionStore();

        service(positions, predictions).savePositions(USER, DATE,
                Arrays.asList(position("002909", "12.30", "13.40", null, "违约")));
        service(positions, predictions).savePredictions(USER, DATE,
                Arrays.asList(answer("路径一", "命中", null)));

        assertEquals("违约", only(positions.rows).getDiscipline());
        assertEquals("命中", only(predictions.rows).getResult());
    }

    // ---- fixture ----

    private static ReviewLedgerService service(RecordingPositionStore p, RecordingPredictionStore pr) {
        return new ReviewLedgerService(p, pr, stockMapper());
    }

    private static PositionRequest position(String code, String cost, String current, String floatPct,
                                           String discipline) {
        PositionRequest r = new PositionRequest();
        r.setStockCode(code);
        r.setCostPrice(cost == null ? null : new BigDecimal(cost));
        r.setCurrentPrice(current == null ? null : new BigDecimal(current));
        r.setFloatPct(floatPct == null ? null : new BigDecimal(floatPct));
        r.setDiscipline(discipline);
        return r;
    }

    private static PredictionRequest plan(String name, Integer prob, String condition) {
        PredictionRequest r = new PredictionRequest();
        r.setKind(Prediction.KIND_PLAN);
        r.setName(name);
        r.setProb(prob);
        r.setConditionText(condition);
        return r;
    }

    private static PredictionRequest answer(String name, String result, String note) {
        PredictionRequest r = new PredictionRequest();
        r.setKind(Prediction.KIND_ANSWER);
        r.setName(name);
        r.setResult(result);
        r.setResultNote(note);
        return r;
    }

    private static <T> T only(List<List<T>> batches) {
        assertEquals(1, batches.size(), "store 应被调用一次");
        assertEquals(1, batches.get(0).size());
        return batches.get(0).get(0);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == int.class || type == Integer.class) {
            return 0;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 接住 store 收下的行，不碰 mapper：台账测试关心的是校验结果，不是 SQL。 */
    private static final class RecordingPositionStore extends PositionStore {
        final List<List<Position>> rows = new ArrayList<>();
        int calls;

        RecordingPositionStore() {
            super(null);
        }

        @Override
        public int replaceForDate(Long userId, LocalDate date, List<Position> rows) {
            calls++;
            this.rows.add(new ArrayList<>(rows));
            return rows.size();
        }
    }

    private static final class RecordingPredictionStore extends PredictionStore {
        final List<List<Prediction>> rows = new ArrayList<>();
        final List<Set<String>> kinds = new ArrayList<>();
        int calls;

        RecordingPredictionStore() {
            super(null);
        }

        @Override
        public int replaceForDate(Long userId, LocalDate date, List<Prediction> rows, Set<String> kinds) {
            calls++;
            this.rows.add(new ArrayList<>(rows));
            this.kinds.add(kinds);
            return rows.size();
        }
    }
}
