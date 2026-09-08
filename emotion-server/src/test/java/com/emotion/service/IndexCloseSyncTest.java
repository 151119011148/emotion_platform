package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.emotion.entity.IndexClose;
import com.emotion.market.TencentClient.DayBar;
import com.emotion.mapper.IndexCloseMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指数收盘的自动取数侧：腾讯日 K → 五行 → 按代码覆盖入库。
 *
 * <p>两条要钉住的性质：<b>少一只不牵连其余</b>（北证50 最容易掉，掉一只应该是四行，
 * 而不是整批不给），以及<b>删除只按这批出现过的代码</b>（腾讯没回的那几行是 md 导进来的，
 * 不该因为"这次没取到"就消失）。
 */
class IndexCloseSyncTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 4);

    // ---- 行装配 ----

    @Test
    void allFiveBarsBecomeTheImportContractCodes() {
        Map<String, DayBar> bars = new LinkedHashMap<>();
        bars.put("000001", bar("3930.12", "-0.30"));
        bars.put("399001", bar("12376.14", "0.51"));
        bars.put("399006", bar("1521.40", "0"));
        bars.put("000688", bar("1024.55", "-1.20"));
        bars.put("899050", bar("1088.88", "2.34"));

        List<IndexClose> rows = MarketDataService.indexRows(DATE, bars);

        assertEquals(Arrays.asList("000001", "399001", "399006", "000688", "899050"), codes(rows));
        assertEquals(Arrays.asList("上证指数", "深证成指", "创业板指", "科创50", "北证50"), names(rows));
        assertEquals(new BigDecimal("3930.12"), rows.get(0).getClosePrice());
        assertEquals(new BigDecimal("-0.30"), rows.get(0).getChangePct());
        for (IndexClose row : rows) {
            assertEquals(DATE, row.getTradeDate());
        }
    }

    /** 缺一只就少一行，其余照给——"取到四只"比"整批作废"有用得多。 */
    @Test
    void missingBarOnlyDropsItsOwnRow() {
        Map<String, DayBar> bars = new LinkedHashMap<>();
        bars.put("000001", bar("3930.12", "-0.30"));
        bars.put("399006", bar("1521.4", "0"));

        List<IndexClose> rows = MarketDataService.indexRows(DATE, bars);

        assertEquals(Arrays.asList("000001", "399006"), codes(rows));
    }

    @Test
    void emptyBarsProduceNoRows() {
        assertTrue(MarketDataService.indexRows(DATE, new LinkedHashMap<>()).isEmpty());
        assertTrue(MarketDataService.indexRows(DATE, null).isEmpty());
        assertTrue(MarketDataService.indexRows(null, single("000001", "1", "1")).isEmpty());
    }

    // ---- 按代码覆盖 ----

    /** 这批覆盖哪些代码：重复只留一次，没代码的行不算（它决定删除的收窄范围）。 */
    @Test
    void batchCodesAreDedupedAndNullSafe() {
        IndexClose blank = new IndexClose();
        assertEquals(Arrays.asList("000001", "399006"), IndexCloseStore.distinctCodes(
                Arrays.asList(row("000001", "上证指数"), row("399006", "创业板指"),
                        row("000001", "上证指数"), blank)));
        assertTrue(IndexCloseStore.distinctCodes(Arrays.asList(blank)).isEmpty());
    }

    /** 只删这批出现过的 code：一次部分回包不该把 md 导进来的其余几只一起抹掉。 */
    @Test
    void upsertDeletesOnceAndReinsertsExactlyTheBatch() {
        RecordingMapper rec = new RecordingMapper();
        IndexCloseStore store = new IndexCloseStore(rec.proxy());
        List<IndexClose> batch = Arrays.asList(row("000001", "上证指数"), row("399006", "创业板指"));

        assertEquals(2, store.upsertForDate(DATE, batch));

        assertEquals(1, rec.deletes.size());
        assertEquals(Arrays.asList("000001", "399006"), IndexCloseStore.distinctCodes(flatten(rec.inserts)));
    }

    /** 空批次什么都不做——连删除都不该发，否则"这次没取到"就变成了"那天没有指数"。 */
    @Test
    void emptyBatchTouchesNothing() {
        RecordingMapper rec = new RecordingMapper();
        IndexCloseStore store = new IndexCloseStore(rec.proxy());

        assertEquals(0, store.upsertForDate(DATE, new ArrayList<IndexClose>()));
        assertEquals(0, store.upsertForDate(DATE, null));
        assertEquals(0, store.upsertForDate(DATE, Arrays.asList(new IndexClose())));

        assertTrue(rec.deletes.isEmpty());
        assertTrue(rec.inserts.isEmpty());
    }

    /** md 导入那条路仍是整日重建：空表也要发一次删除（"这天说了没有指数"），只是不插行。 */
    @Test
    void mdImportStillReplacesTheWholeDay() {
        RecordingMapper rec = new RecordingMapper();
        IndexCloseStore store = new IndexCloseStore(rec.proxy());

        assertEquals(0, store.replaceForDate(DATE, new ArrayList<IndexClose>()));

        assertEquals(1, rec.deletes.size());
        assertTrue(rec.inserts.isEmpty());
        assertEquals(-1, store.replaceForDate(DATE, null));
        assertEquals(1, rec.deletes.size());
    }

    // ---- fixture ----

    private static Map<String, DayBar> single(String code, String close, String pct) {
        Map<String, DayBar> bars = new LinkedHashMap<>();
        bars.put(code, bar(close, pct));
        return bars;
    }

    private static DayBar bar(String close, String pct) {
        DayBar b = new DayBar();
        b.setDate(DATE);
        b.setClose(new BigDecimal(close));
        b.setPct(new BigDecimal(pct));
        return b;
    }

    private static IndexClose row(String code, String name) {
        IndexClose ic = new IndexClose();
        ic.setTradeDate(DATE);
        ic.setIndexCode(code);
        ic.setIndexName(name);
        ic.setClosePrice(new BigDecimal("100.00"));
        return ic;
    }

    private static List<String> codes(List<IndexClose> rows) {
        List<String> out = new ArrayList<>();
        for (IndexClose r : rows) {
            out.add(r.getIndexCode());
        }
        return out;
    }

    private static List<String> names(List<IndexClose> rows) {
        List<String> out = new ArrayList<>();
        for (IndexClose r : rows) {
            out.add(r.getIndexName());
        }
        return out;
    }

    private static List<IndexClose> flatten(List<List<IndexClose>> batches) {
        List<IndexClose> all = new ArrayList<>();
        batches.forEach(all::addAll);
        return all;
    }

    /** 只记 delete / insertBatch 两类调用，其余按返回类型给默认值。 */
    private static final class RecordingMapper implements InvocationHandler {
        final List<Wrapper<IndexClose>> deletes = new ArrayList<>();
        final List<List<IndexClose>> inserts = new ArrayList<>();

        IndexCloseMapper proxy() {
            return (IndexCloseMapper) Proxy.newProxyInstance(
                    IndexCloseMapper.class.getClassLoader(),
                    new Class<?>[]{IndexCloseMapper.class}, this);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object p, Method method, Object[] args) {
            switch (method.getName()) {
                case "delete":
                    deletes.add((Wrapper<IndexClose>) args[0]);
                    return 1;
                case "insertBatch":
                    inserts.add(new ArrayList<>((List<IndexClose>) args[0]));
                    return args[0] == null ? 0 : ((List<IndexClose>) args[0]).size();
                default:
                    Class<?> rt = method.getReturnType();
                    if (rt == int.class || rt == Integer.class) {
                        return 0;
                    }
                    return rt == boolean.class ? Boolean.FALSE : null;
            }
        }
    }
}
