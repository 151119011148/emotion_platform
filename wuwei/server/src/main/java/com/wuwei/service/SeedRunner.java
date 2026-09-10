package com.wuwei.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuwei.engine.SentimentEngine;
import com.wuwei.entity.*;
import com.wuwei.mapper.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * 首启种子（wuwei.seed=true 且库为空时执行）：
 * 灌入 13 个交易日的演示行情 —— 一条完整的情绪周期
 * 冰点→启动→发酵→高潮(3日)→分歧→退潮→启动→发酵，
 * 主线「固态电池」亢奋后退潮，新主线「农业种植」完成高低切。
 * 灌完逐日跑三大引擎落库评分与节点，开箱即可演示 P1~P7。
 */
@Service
public class SeedRunner implements CommandLineRunner {

    private final UserMapper userMapper;
    private final StockBaseMapper stockBaseMapper;
    private final ConceptBaseMapper conceptMapper;
    private final StockConceptRelMapper relMapper;
    private final LimitUpDailyMapper limitUpMapper;
    private final LianbanDailyMapper lianbanMapper;
    private final MonitorPoolMapper monitorMapper;
    private final CalcService calcService;
    private final PasswordEncoder passwordEncoder;

    private final Random rnd = new Random(42);

    public SeedRunner(UserMapper userMapper, StockBaseMapper stockBaseMapper,
                      ConceptBaseMapper conceptMapper, StockConceptRelMapper relMapper,
                      LimitUpDailyMapper limitUpMapper, LianbanDailyMapper lianbanMapper,
                      MonitorPoolMapper monitorMapper, CalcService calcService,
                      PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.stockBaseMapper = stockBaseMapper;
        this.conceptMapper = conceptMapper;
        this.relMapper = relMapper;
        this.limitUpMapper = limitUpMapper;
        this.lianbanMapper = lianbanMapper;
        this.monitorMapper = monitorMapper;
        this.calcService = calcService;
        this.passwordEncoder = passwordEncoder;
    }

    // ================= 剧本参数 =================

    private static final String[] DATES = {
            "2026-08-24", "2026-08-25", "2026-08-26", "2026-08-27", "2026-08-28",
            "2026-08-31", "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04",
            "2026-09-07", "2026-09-08", "2026-09-09"
    };
    // 主线：idx0 锂矿(老) / idx1-9 固态电池 / idx10-12 农业种植
    private static final String[] MAIN = {
            "LK", "GN", "GN", "GN", "GN", "GN", "GN", "GN", "GN", "GN", "NY", "NY", "NY"
    };
    // 每日：首板(主线) / 首板(新题材种子) / 首板(其他) / 炸板 / 跌停
    private static final int[][] SCENARIO = {
            // idx0 冰点
            {5, 0, 4, 6, 11},
            // idx1-2 启动
            {8, 0, 4, 5, 8},
            {14, 0, 6, 5, 6},
            // idx3-4 发酵
            {18, 0, 8, 4, 5},
            {20, 0, 8, 5, 4},
            // idx5-7 高潮
            {22, 0, 8, 6, 4},
            {24, 0, 8, 6, 3},
            {24, 0, 10, 8, 3},
            // idx8 分歧（大面日）
            {10, 0, 5, 12, 9},
            // idx9 退潮 + 新题材种子
            {3, 4, 4, 9, 14},
            // idx10-12 新主线启动→发酵
            {10, 0, 4, 5, 7},
            {14, 0, 4, 4, 4},
            {20, 0, 4, 4, 3}
    };

    /** cast：code/name/concept/role/连板路径(13天)/断板日收盘/大面日/核按钮日/反包日 */
    private static final Object[][] CAST = {
            // code, name, concept, role, path, breakClose, noodleDays, nukeDays, backDays
            {"002901.SZ", "智远机电", "GN", "ZONG_LONG",
                    new int[]{0, 1, 2, 3, 4, 5, 6, 7, 0, 0, 0, 0, 0},
                    new double[]{8, -8.2}, new int[]{}, new int[]{}, new int[]{}},
            {"002902.SZ", "华辰精工", "GN", "ZHONG_JUN",
                    new int[]{0, 0, 1, 2, 3, 4, 0, 1, 2, 0, 0, 0, 0},
                    new double[]{6, -2.1, 9, -5.1}, new int[]{9}, new int[]{}, new int[]{}},
            {"002903.SZ", "中州伺服", "GN", "GEN_FENG",
                    new int[]{0, 1, 2, 3, 0, 1, 2, 3, 0, 0, 0, 0, 0},
                    new double[]{4, -1.5, 8, -6.5}, new int[]{8}, new int[]{}, new int[]{}},
            {"603901.SH", "北方传动", "GN", "GEN_FENG",
                    new int[]{0, 0, 1, 2, 3, 4, 0, 1, 0, 0, 0, 0, 0},
                    new double[]{6, -1.8, 8, -4.2}, new int[]{8}, new int[]{}, new int[]{7}},
            {"002904.SZ", "岭南轴承", "GN", "KA_WEI",
                    new int[]{0, 0, 0, 0, 1, 2, 0, 1, 0, 0, 0, 0, 0},
                    new double[]{6, -1.8, 8, -3.0}, new int[]{}, new int[]{}, new int[]{}},
            {"603905.SH", "天工激光", "GN", "FAN_BAO",
                    new int[]{0, 0, 0, 1, 2, 0, 0, 1, 0, 0, 0, 0, 0},
                    new double[]{5, -2.5, 8, -9.8}, new int[]{}, new int[]{8}, new int[]{7}},
            {"002905.SZ", "东岭能源", "LK", "ZONG_LONG",
                    new int[]{2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new double[]{1, -3.5}, new int[]{}, new int[]{}, new int[]{}},
            {"603906.SH", "金穗种业", "NY", "ZONG_LONG",
                    new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4},
                    new double[]{}, new int[]{}, new int[]{}, new int[]{}},
            {"603907.SH", "丰禾农化", "NY", "ZHONG_JUN",
                    new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3},
                    new double[]{}, new int[]{}, new int[]{}, new int[]{}},
            {"002906.SZ", "绿源生化", "NY", "GEN_FENG",
                    new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3},
                    new double[]{}, new int[]{}, new int[]{}, new int[]{}},
            {"002907.SZ", "农资股份", "NY", "GEN_FENG",
                    new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 0, 0},
                    new double[]{11, -1.9}, new int[]{}, new int[]{}, new int[]{}}
    };

    private static final String[] OTHER_CONCEPTS = {"CB", "SL", "LK"};
    private static final String[] PREFIX = {"恒泰", "瑞和", "天工", "金鸿", "中晟", "东晶", "南岭",
            "西陇", "北极", "青云", "晨光", "瀚海", "星野", "鹏程", "麒麟", "万象", "凌云",
            "磐石", "晟安", "卓立", "弘毅", "泰岳", "沃野", "昆仑", "沧海", "明川", "长风",
            "旭日", "皓月", "子午"};
    private static final String[] SUFFIX = {"科技", "电子", "精密", "智能", "材料", "装备",
            "光电", "新材", "能源", "生化", "农业", "机械"};

    @Override
    public void run(String... args) {
        try {
            if (userMapper.selectCount(null) == 0) {
                User u = new User();
                u.setUsername("demo");
                u.setPassword(passwordEncoder.encode("demo123"));
                u.setNickname("演示用户");
                userMapper.insert(u);
            }
            if (stockBaseMapper.selectCount(null) > 0) return; // 已有数据，跳过
            seedAll();
        } catch (Exception e) {
            // 种子失败不阻断启动（比如库不可达时应用仍要能起来报配置错误）
            System.err.println("[wuwei-seed] 种子数据生成失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void seedAll() {
        seedConcepts();
        seedStocks();
        seedMonitor();
        List<LocalDate> dates = new ArrayList<LocalDate>();
        for (int i = 0; i < DATES.length; i++) {
            LocalDate d = LocalDate.parse(DATES[i]);
            dates.add(d);
            seedDay(i, d);
        }
        backfillNextDay(dates);
        calcService.calcDates(dates);
        System.out.println("[wuwei-seed] 13 个交易日演示数据已生成并完成评分/节点计算");
    }

    private void seedConcepts() {
        concept("LK", "锂矿", 4, 9, "退潮", LocalDate.parse("2026-08-10"));
        concept("GN", "固态电池", 4, 0, "萌芽", LocalDate.parse("2026-08-25"));
        concept("NY", "农业种植", 3, 0, "萌芽", LocalDate.parse("2026-09-04"));
        concept("CB", "创新药", 2, 2, "萌芽", LocalDate.parse("2026-08-24"));
        concept("SL", "算力租赁", 3, 1, "萌芽", LocalDate.parse("2026-08-24"));
    }

    private void concept(String id, String name, int hardness, int cont, String stage, LocalDate since) {
        ConceptBase c = new ConceptBase();
        c.setConceptId(id);
        c.setName(name);
        c.setSource("DEMO");
        c.setLevel1("科技制造");
        c.setIsMainLine(false);
        c.setStage(stage);
        c.setCatalystHardness(hardness);
        c.setContinuousDays(cont);
        c.setActiveSince(since);
        conceptMapper.insert(c);
    }

    private void seedStocks() {
        for (Object[] c : CAST) {
            stock((String) c[0], (String) c[1], "主板");
        }
        for (int i = 0; i < 60; i++) {
            String code = i % 2 == 0
                    ? String.format("0029%02d.SZ", 50 + i / 2)
                    : String.format("6039%02d.SH", 50 + i / 2);
            String name = PREFIX[(i / 2) % PREFIX.length] + SUFFIX[(i * 7) % SUFFIX.length];
            stock(code, name, "主板");
        }
    }

    private void stock(String code, String name, String board) {
        StockBase s = new StockBase();
        s.setTsCode(code);
        s.setSymbol(code.split("\\.")[0]);
        s.setName(name);
        s.setExchange(code.endsWith(".SZ") ? "SZ" : "SH");
        s.setBoard(board);
        s.setIsSt(false);
        s.setIsNewStock(false);
        s.setLimitPct(new BigDecimal("10.00"));
        s.setDelisted(false);
        stockBaseMapper.insert(s);
    }

    private void seedMonitor() {
        monitor("002905.SZ", "东岭能源", "ORDINARY", "2026-08-24", "锂矿", false);
        monitor("002903.SZ", "中州伺服", "SERIOUS", "2026-08-31", "固态电池", true);
        monitor("603905.SH", "天工激光", "KEY_MONITOR", "2026-09-02", "固态电池", true);
    }

    private void monitor(String code, String name, String status, String date, String rel, boolean high) {
        MonitorPool m = new MonitorPool();
        m.setTsCode(code);
        m.setName(name);
        m.setStatus(status);
        m.setEnterDate(LocalDate.parse(date));
        m.setRelatedConcept(rel);
        m.setIsHighPosition(high);
        monitorMapper.insert(m);
    }

    private void seedDay(int idx, LocalDate date) {
        int[] sc = SCENARIO[idx];
        String main = MAIN[idx];
        Set<String> dayUsed = new HashSet<String>();

        // ---- cast ----
        for (Object[] c : CAST) {
            String code = (String) c[0];
            String name = (String) c[1];
            String concept = (String) c[2];
            int[] path = (int[]) c[4];
            Map<Integer, Double> breakClose = breakMap(c[5]);
            Set<Integer> noodle = intSet(c[6]);
            Set<Integer> nuke = intSet(c[7]);
            Set<Integer> back = intSet(c[8]);
            int cur = path[idx];
            int prev = idx == 0 ? 0 : path[idx - 1];
            dayUsed.add(code);

            if (cur > 0) {
                LimitUpDaily r = baseRow(date, code, name);
                r.setStatus(cur == 1 ? "ZT_FIRST" : "ZT");
                r.setNZones(cur);
                r.setPrevNZones(prev);
                r.setIsFirstBoard(cur == 1);
                r.setConceptMain(concept);
                r.setFirstLuTime(randTime());
                r.setLastLuTime(randTime());
                r.setOpenTimes(rnd.nextInt(3));
                r.setIsBack(back.contains(idx));
                r.setOpenChg(bd(rnd.nextDouble() * 6 - 2));
                r.setHighChg(bd(10.0));
                r.setCloseChg(bd(10.0));
                r.setAmount(bd(money(15 + rnd.nextDouble() * 20)));
                r.setTurnoverRate(bd(5 + rnd.nextDouble() * 20));
                r.setVolYestRatio(bd(1 + rnd.nextDouble() * 2));
                r.setFdAmount(bd(money(0.5 + rnd.nextDouble() * 5)));
                r.setFdFloatRatio(bd(rnd.nextDouble() * 0.08));
                r.setMaxDrawdown(bd(rnd.nextDouble() * 3));
                r.setIsBigNoodle(false);
                r.setIsNuke(false);
                r.setBrokenFlag(false);
                r.setMonitorStatus(monitorStatus(code, date));
                limitUpMapper.insert(r);
            } else if (prev > 0 && breakClose.containsKey(idx)) {
                LimitUpDaily r = baseRow(date, code, name);
                boolean isNuke = nuke.contains(idx);
                if (idx == 9 && "002901.SZ".equals(code)) {
                    // 总龙头断板次日反包炸板
                    r.setStatus("BOMB");
                    r.setIsBack(true);
                    r.setCloseChg(bd(1.2));
                } else {
                    r.setStatus("BROKEN");
                    r.setCloseChg(bd(breakClose.get(idx)));
                }
                r.setNZones(0);
                r.setPrevNZones(prev);
                r.setIsFirstBoard(false);
                r.setConceptMain(concept);
                r.setOpenChg(bd(rnd.nextDouble() * 4 - 2));
                r.setHighChg(bd(rnd.nextDouble() * 8 - 2));
                r.setOpenTimes(1 + rnd.nextInt(4));
                r.setIsBack(false);
                r.setAmount(bd(money(8 + rnd.nextDouble() * 15)));
                r.setTurnoverRate(bd(15 + rnd.nextDouble() * 25));
                r.setVolYestRatio(bd(1.5 + rnd.nextDouble() * 2));
                r.setFdAmount(null);
                r.setMaxDrawdown(bd(Math.abs(r.getCloseChg() == null ? 0 : r.getCloseChg().doubleValue())));
                r.setIsBigNoodle(noodle.contains(idx));
                r.setIsNuke(isNuke);
                r.setBrokenFlag(true);
                r.setMonitorStatus(monitorStatus(code, date));
                limitUpMapper.insert(r);
            }

            // 连板派生
            if (cur >= 2) {
                LianbanDaily l = new LianbanDaily();
                l.setTradeDate(date);
                l.setTsCode(code);
                l.setName(name);
                l.setNZones(cur);
                l.setConceptMain(concept);
                l.setPrevNZones(prev);
                l.setJrBaseCount(1);
                l.setIsPromote(cur > prev);
                l.setFirstLuTime(randTime());
                l.setOpenTimes(rnd.nextInt(3));
                l.setIsBack(back.contains(idx));
                l.setFdAmount(bd(money(0.5 + rnd.nextDouble() * 5)));
                l.setTurnoverRate(bd(5 + rnd.nextDouble() * 20));
                l.setVolYestRatio(bd(1 + rnd.nextDouble() * 2));
                l.setOpenChg(bd(rnd.nextDouble() * 6 - 2));
                l.setCloseChg(bd(10.0));
                l.setMaxDrawdown(bd(rnd.nextDouble() * 3));
                l.setIsBigNoodle(false);
                l.setIsNuke(false);
                l.setMonitorStatus(monitorStatus(code, date));
                l.setIsSpaceLeader(false);
                l.setIsSectorLeader("ZHONG_JUN".equals(c[3]));
                l.setLeaderAction(cur > prev ? "PROMOTE" : "HOLD");
                lianbanMapper.insert(l);
            }

            // 个股↔题材关系（含龙头分工标签）
            StockConceptRel rel = new StockConceptRel();
            rel.setTradeDate(date);
            rel.setTsCode(code);
            rel.setConceptId(concept);
            rel.setRole("MAIN");
            rel.setDragonRole((String) c[3]);
            relMapper.insert(rel);
        }

        // ---- 连板空间板/板块龙头旗标（当日最高板） ----
        markLeaders(date);

        // ---- fillers：首板 / 炸板 / 跌停 ----
        List<String> pool = fillerPool();
        for (int i = 0; i < sc[0]; i++) {
            String code = pick(pool, dayUsed, idx * 7 + i);
            LimitUpDaily r = baseRow(date, code, nameOf(code));
            r.setStatus("ZT_FIRST");
            r.setNZones(1);
            r.setPrevNZones(0);
            r.setIsFirstBoard(true);
            r.setConceptMain(main);
            fillSealed(r);
            limitUpMapper.insert(r);
        }
        for (int i = 0; i < sc[1]; i++) {
            String code = pick(pool, dayUsed, idx * 11 + 30 + i);
            LimitUpDaily r = baseRow(date, code, nameOf(code));
            r.setStatus("ZT_FIRST");
            r.setNZones(1);
            r.setPrevNZones(0);
            r.setIsFirstBoard(true);
            r.setConceptMain("NY"); // 新题材种子
            fillSealed(r);
            limitUpMapper.insert(r);
        }
        for (int i = 0; i < sc[2]; i++) {
            String code = pick(pool, dayUsed, idx * 13 + 40 + i);
            LimitUpDaily r = baseRow(date, code, nameOf(code));
            r.setStatus("ZT_FIRST");
            r.setNZones(1);
            r.setPrevNZones(0);
            r.setIsFirstBoard(true);
            r.setConceptMain(OTHER_CONCEPTS[i % OTHER_CONCEPTS.length]);
            fillSealed(r);
            limitUpMapper.insert(r);
        }
        for (int i = 0; i < sc[3]; i++) {
            String code = pick(pool, dayUsed, idx * 17 + 45 + i);
            LimitUpDaily r = baseRow(date, code, nameOf(code));
            r.setStatus("BOMB");
            r.setNZones(0);
            r.setPrevNZones(0);
            r.setIsFirstBoard(true);
            r.setConceptMain(main);
            r.setFirstLuTime(randTime());
            r.setOpenTimes(1 + rnd.nextInt(4));
            r.setIsBack(false);
            r.setOpenChg(bd(rnd.nextDouble() * 5 - 2));
            r.setHighChg(bd(9 + rnd.nextDouble()));
            r.setCloseChg(bd(rnd.nextDouble() * 8 - 2));
            r.setAmount(bd(money(2 + rnd.nextDouble() * 5)));
            r.setTurnoverRate(bd(10 + rnd.nextDouble() * 20));
            r.setVolYestRatio(bd(1 + rnd.nextDouble()));
            r.setMaxDrawdown(bd(5 + rnd.nextDouble() * 6));
            r.setIsBigNoodle(rnd.nextDouble() < 0.15);
            r.setBrokenFlag(true);
            limitUpMapper.insert(r);
        }
        for (int i = 0; i < sc[4]; i++) {
            String code = pick(pool, dayUsed, idx * 19 + 55 + i);
            LimitUpDaily r = baseRow(date, code, nameOf(code));
            r.setStatus("DOWN");
            r.setNZones(0);
            r.setPrevNZones(0);
            r.setIsFirstBoard(false);
            r.setOpenChg(bd(-rnd.nextDouble() * 4));
            r.setHighChg(bd(-rnd.nextDouble() * 2));
            r.setCloseChg(bd(-10.0));
            r.setOpenTimes(0);
            r.setAmount(bd(money(1 + rnd.nextDouble() * 4)));
            r.setTurnoverRate(bd(3 + rnd.nextDouble() * 10));
            limitUpMapper.insert(r);
        }
    }

    private void markLeaders(LocalDate date) {
        List<LianbanDaily> rows = lianbanMapper.selectList(
                new LambdaQueryWrapper<LianbanDaily>().eq(LianbanDaily::getTradeDate, date));
        if (rows.isEmpty()) return;
        LianbanDaily best = null;
        int h = 0;
        for (LianbanDaily row : rows) {
            int nz = row.getNZones() == null ? 0 : row.getNZones();
            if (nz > h) h = nz;
            row.setTier(SentimentEngine.tierOf(nz, 0)); // 先占位，下面统一按 H 修正
        }
        for (LianbanDaily row : rows) {
            row.setTier(SentimentEngine.tierOf(row.getNZones() == null ? 0 : row.getNZones(), h));
            if ("ZONG_LONG".equals(relDragonRole(date, row.getTsCode()))
                    && row.getNZones() != null && row.getNZones() == h) {
                best = row;
            }
        }
        if (best == null) {
            for (LianbanDaily row : rows) {
                if (row.getNZones() != null && row.getNZones() == h) {
                    if (best == null) best = row;
                }
            }
        }
        if (best != null) best.setIsSpaceLeader(true);
        for (LianbanDaily row : rows) lianbanMapper.updateById(row);
    }

    private String relDragonRole(LocalDate date, String code) {
        StockConceptRel rel = relMapper.selectOne(
                new LambdaQueryWrapper<StockConceptRel>()
                        .eq(StockConceptRel::getTradeDate, date)
                        .eq(StockConceptRel::getTsCode, code)
                        .last("limit 1"));
        return rel == null ? null : rel.getDragonRole();
    }

    private void fillSealed(LimitUpDaily r) {
        r.setFirstLuTime(randTime());
        r.setLastLuTime(randTime());
        r.setOpenTimes(rnd.nextDouble() < 0.5 ? 0 : rnd.nextInt(3));
        r.setIsBack(false);
        r.setOpenChg(bd(rnd.nextDouble() * 6 - 2));
        r.setHighChg(bd(10.0));
        r.setCloseChg(bd(10.0));
        r.setAmount(bd(money(2 + rnd.nextDouble() * 8)));
        r.setTurnoverRate(bd(5 + rnd.nextDouble() * 20));
        r.setVolYestRatio(bd(1 + rnd.nextDouble() * 2));
        r.setFdAmount(bd(money(0.3 + rnd.nextDouble() * 3)));
        r.setFdFloatRatio(bd(rnd.nextDouble() * 0.06));
        r.setMaxDrawdown(bd(rnd.nextDouble() * 4));
        r.setIsBigNoodle(false);
        r.setIsNuke(false);
        r.setBrokenFlag(false);
    }

    /** 昨日回填次日开盘/收盘涨跌幅（评分引擎的溢价口径） */
    private void backfillNextDay(List<LocalDate> dates) {
        for (int i = 0; i < dates.size() - 1; i++) {
            List<LimitUpDaily> today = limitUpMapper.selectList(
                    new LambdaQueryWrapper<LimitUpDaily>().eq(LimitUpDaily::getTradeDate, dates.get(i)));
            List<LimitUpDaily> next = limitUpMapper.selectList(
                    new LambdaQueryWrapper<LimitUpDaily>().eq(LimitUpDaily::getTradeDate, dates.get(i + 1)));
            Map<String, LimitUpDaily> nextByCode = new HashMap<String, LimitUpDaily>();
            for (LimitUpDaily n : next) nextByCode.put(n.getTsCode(), n);
            for (LimitUpDaily row : today) {
                LimitUpDaily n = nextByCode.get(row.getTsCode());
                if (n != null) {
                    row.setNextOpenChg(n.getOpenChg());
                    row.setNextCloseChg(n.getCloseChg());
                } else {
                    row.setNextOpenChg(bd(rnd.nextDouble() * 5 - 3));
                    row.setNextCloseChg(bd(rnd.nextDouble() * 9 - 6));
                }
                limitUpMapper.updateById(row);
            }
        }
    }

    // ================= utils =================

    private LimitUpDaily baseRow(LocalDate date, String code, String name) {
        LimitUpDaily r = new LimitUpDaily();
        r.setTradeDate(date);
        r.setTsCode(code);
        r.setName(name);
        r.setMonitorStatus("NONE");
        return r;
    }

    private String monitorStatus(String code, LocalDate date) {
        if ("002903.SZ".equals(code) && !date.isBefore(LocalDate.parse("2026-08-31"))) return "SERIOUS";
        if ("603905.SH".equals(code) && !date.isBefore(LocalDate.parse("2026-09-02"))) return "KEY_MONITOR";
        if ("002905.SZ".equals(code) && !date.isBefore(LocalDate.parse("2026-08-24"))) return "ORDINARY";
        return "NONE";
    }

    private List<String> fillerPool() {
        List<String> pool = new ArrayList<String>();
        for (int i = 0; i < 60; i++) {
            pool.add(i % 2 == 0
                    ? String.format("0029%02d.SZ", 50 + i / 2)
                    : String.format("6039%02d.SH", 50 + i / 2));
        }
        return pool;
    }

    private String nameOf(String code) {
        // 与 seedStocks 相同的确定性名字
        int i = -1;
        for (int k = 0; k < 60; k++) {
            String c = k % 2 == 0
                    ? String.format("0029%02d.SZ", 50 + k / 2)
                    : String.format("6039%02d.SH", 50 + k / 2);
            if (c.equals(code)) {
                i = k;
                break;
            }
        }
        if (i < 0) return "未知";
        return PREFIX[(i / 2) % PREFIX.length] + SUFFIX[(i * 7) % SUFFIX.length];
    }

    /** 在当日已用集合内挑一只未被占用的池内股票 */
    private String pick(List<String> pool, Set<String> dayUsed, int base) {
        for (int t = 0; t < pool.size(); t++) {
            String code = pool.get((base + t) % pool.size());
            if (!dayUsed.contains(code)) {
                dayUsed.add(code);
                return code;
            }
        }
        return pool.get(base % pool.size());
    }

    private LocalTime randTime() {
        int h = 9 + rnd.nextInt(5);
        int m = rnd.nextInt(60);
        if (h > 14 || (h == 14 && m > 57)) {
            h = 14;
            m = 57;
        }
        if (h < 9) h = 9;
        return LocalTime.of(h, m);
    }

    private double money(double yi) {
        return yi * 1e8;
    }

    private BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private Map<Integer, Double> breakMap(Object arr) {
        Map<Integer, Double> m = new HashMap<Integer, Double>();
        double[] a = (double[]) arr;
        for (int i = 0; i + 1 < a.length; i += 2) {
            m.put((int) a[i], a[i + 1]);
        }
        return m;
    }

    private Set<Integer> intSet(Object arr) {
        Set<Integer> s = new HashSet<Integer>();
        for (int v : (int[]) arr) s.add(v);
        return s;
    }
}
