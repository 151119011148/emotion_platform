package com.wuwei.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wuwei.entity.*;
import com.wuwei.mapper.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 5维评分引擎（PRD §4.1）。
 *
 * 总分 = 大盘生态25% + 主线明确度20% + 连板生态25% + 首板生态15% + 阵眼15%
 * 主线明确度内含 5 要素：涨停聚集度25 / 高度聚集度25 / 成交额聚集度20 / 催化剂硬度15 / 持续性15
 * 阵眼内含龙头分工：总龙头50 / 中军20 / 跟风15 / 卡位10 / 反包5
 * 主线维再叠加监管 R 分折扣（外挂层）。
 */
@Service
public class SentimentEngine {

    private final LimitUpDailyMapper limitUpMapper;
    private final LianbanDailyMapper lianbanMapper;
    private final ConceptBaseMapper conceptMapper;
    private final StockConceptRelMapper relMapper;
    private final MonitorPoolMapper monitorMapper;

    public SentimentEngine(LimitUpDailyMapper limitUpMapper, LianbanDailyMapper lianbanMapper,
                           ConceptBaseMapper conceptMapper, StockConceptRelMapper relMapper,
                           MonitorPoolMapper monitorMapper) {
        this.limitUpMapper = limitUpMapper;
        this.lianbanMapper = lianbanMapper;
        this.conceptMapper = conceptMapper;
        this.relMapper = relMapper;
        this.monitorMapper = monitorMapper;
    }

    public SentimentResult calcDaily(LocalDate date) {
        SentimentResult r = new SentimentResult();
        r.setTradeDate(date);

        List<LimitUpDaily> today = listByDate(date);
        LocalDate prevDate = prevTradingDate(date);
        List<LimitUpDaily> prev = prevDate == null ? Collections.<LimitUpDaily>emptyList()
                : listByDate(prevDate);
        List<LianbanDaily> lb = lianbanMapper.selectList(
                new LambdaQueryWrapper<LianbanDaily>().eq(LianbanDaily::getTradeDate, date));
        List<LianbanDaily> prevLb = prevDate == null ? Collections.<LianbanDaily>emptyList()
                : lianbanMapper.selectList(
                new LambdaQueryWrapper<LianbanDaily>().eq(LianbanDaily::getTradeDate, prevDate));

        // ---------- 盘面计数 ----------
        int zt = 0, dt = 0, bomb = 0, first = 0, noodle = 0;
        double totalAmount = 0, mainAmount = 0;
        Map<String, Integer> conceptZt = new LinkedHashMap<String, Integer>();
        Map<String, Double> conceptAmount = new LinkedHashMap<String, Double>();
        int maxBoardOverall = 0;
        Set<String> sealedCodes = new HashSet<String>();

        for (LimitUpDaily row : today) {
            String st = row.getStatus();
            boolean sealed = "ZT".equals(st) || "ZT_FIRST".equals(st);
            if (sealed) {
                zt++;
                if (row.getIsFirstBoard() != null && row.getIsFirstBoard()) first++;
                sealedCodes.add(row.getTsCode());
                int nz = row.getNZones() == null ? 1 : row.getNZones();
                if (nz > maxBoardOverall) maxBoardOverall = nz;
                String c = row.getConceptMain();
                if (c != null) {
                    Integer n = conceptZt.get(c);
                    conceptZt.put(c, n == null ? 1 : n + 1);
                    Double a = conceptAmount.get(c);
                    double amt = row.getAmount() == null ? 0 : row.getAmount().doubleValue();
                    conceptAmount.put(c, (a == null ? 0 : a) + amt);
                }
            } else if ("DOWN".equals(st)) dt++;
            else if ("BOMB".equals(st)) bomb++;
            if (row.getIsBigNoodle() != null && row.getIsBigNoodle()) noodle++;
            if (row.getIsNuke() != null && row.getIsNuke()) noodle++;
            double amt = row.getAmount() == null ? 0 : row.getAmount().doubleValue();
            totalAmount += amt;
        }

        r.setZtCount(zt);
        r.setDtCount(dt);
        r.setBombCount(bomb);
        r.setFirstCount(first);
        r.setMaxBoard(maxBoardOverall);
        r.setBigNoodleCount(noodle);

        // ---------- 维1 大盘生态 25% ----------
        double breadth = clamp(50 + (zt - dt) * 1.5, 0, 100);
        double avgPremium = avgNextCloseChg(prev, sealedCodesPrev(prev));
        double premiumScore = clamp(50 + avgPremium * 12, 0, 100);
        double sealedRate = (zt + bomb) == 0 ? 0 : (double) zt / (zt + bomb);
        double fbScore = sealedRate * 100;
        double market = 0.4 * breadth + 0.35 * premiumScore + 0.25 * fbScore;
        r.setMarket(round2(market));

        // ---------- 维2 主线明确度 20%（5要素 + 监管折扣） ----------
        // 主线判定：当日涨停聚集度最高的概念
        String mainId = null;
        int mainZt = 0;
        for (Map.Entry<String, Integer> e : conceptZt.entrySet()) {
            if (e.getValue() > mainZt) {
                mainZt = e.getValue();
                mainId = e.getKey();
            }
        }
        ConceptBase conceptRow = mainId == null ? null : conceptMapper.selectById(mainId);
        double conceptScore = 0;
        Map<String, Object> conceptDetail = new LinkedHashMap<String, Object>();
        double ztRatio = 0, heightRatio = 0, amountRatio = 0;

        if (mainId != null && conceptRow != null && zt > 0) {
            int mainMax = 0;
            for (LianbanDaily row : lb) {
                if (mainId.equals(row.getConceptMain()) && row.getNZones() != null
                        && row.getNZones() > mainMax) {
                    mainMax = row.getNZones();
                }
            }
            if (mainMax == 0) {
                // 无连板时看触板池里的高度
                for (LimitUpDaily row : today) {
                    if (mainId.equals(row.getConceptMain()) && row.getNZones() != null
                            && row.getNZones() > mainMax) {
                        mainMax = row.getNZones();
                    }
                }
            }
            double mainAmt = conceptAmount.containsKey(mainId) ? conceptAmount.get(mainId) : 0;

            ztRatio = (double) mainZt / zt;
            heightRatio = maxBoardOverall == 0 ? 0 : (double) mainMax / maxBoardOverall;
            amountRatio = totalAmount == 0 ? 0 : mainAmt / totalAmount;

            double ztScore = clamp(ztRatio * 250, 0, 100);
            double heightScore = clamp(heightRatio * 100, 0, 100);
            double amountScore = clamp(amountRatio / 0.4 * 100, 0, 100);
            int hardness = conceptRow.getCatalystHardness() == null ? 3 : conceptRow.getCatalystHardness();
            double hardnessScore = hardness * 20.0;
            int contDays = conceptRow.getContinuousDays() == null ? 0 : conceptRow.getContinuousDays();
            double contScore = Math.min(contDays, 10) * 10.0;

            double raw = 0.25 * ztScore + 0.25 * heightScore + 0.2 * amountScore
                    + 0.15 * hardnessScore + 0.15 * contScore;

            // 监管 R 分折扣（外挂层）：活跃重点监控/严重异动每家 -8%，下限 0.6
            int serious = countActiveSerious(date);
            double discount = Math.max(0.6, 1 - 0.08 * serious);
            conceptScore = raw * discount;

            conceptDetail.put("conceptId", mainId);
            conceptDetail.put("conceptName", conceptRow.getName());
            conceptDetail.put("zt_ratio", round2(ztScore));
            conceptDetail.put("height", round2(heightScore));
            conceptDetail.put("amount", round2(amountScore));
            conceptDetail.put("hardness", round2(hardnessScore));
            conceptDetail.put("continuous", round2(contScore));
            conceptDetail.put("ztRatioPct", round2(ztRatio * 100));
            conceptDetail.put("heightRatioPct", round2(heightRatio * 100));
            conceptDetail.put("amountRatioPct", round2(amountRatio * 100));
            conceptDetail.put("monitorDiscount", round2(discount));
            conceptDetail.put("stage", conceptRow.getStage());

            r.setMainConceptId(mainId);
            r.setMainConceptName(conceptRow.getName());
            r.setMainStage(conceptRow.getStage());
            r.setMainContinuousDays(contDays);
            r.setMainZtCount(mainZt);
        }
        r.setConcept(round2(conceptScore));
        r.setZtRatio(ztRatio);
        r.setHeightRatio(heightRatio);
        r.setAmountRatio(amountRatio);

        // ---------- 维3 连板生态 25% ----------
        double lianbanScore = 0;
        double midPromoteRate = 0;
        Map<String, Object> lianbanDetail = new LinkedHashMap<String, Object>();
        if (!lb.isEmpty()) {
            int h = 0, high = 0, midHigh = 0, mid = 0, promoted = 0, midEligible = 0, midPromoted = 0;
            for (LianbanDaily row : lb) {
                int nz = row.getNZones() == null ? 0 : row.getNZones();
                if (nz > h) h = nz;
                if (row.getIsPromote() != null && row.getIsPromote()) promoted++;
                if (nz >= 3 && nz <= 4) {
                    midEligible++;
                    if (row.getIsPromote() != null && row.getIsPromote()) midPromoted++;
                }
            }
            for (LianbanDaily row : lb) {
                int nz = row.getNZones() == null ? 0 : row.getNZones();
                String tier = tierOf(nz, h);
                if ("HIGH".equals(tier)) high++;
                else if ("MIDHIGH".equals(tier)) midHigh++;
                else if ("MID".equals(tier)) mid++;
            }
            double heightScore = Math.min(100, h * 14.0);
            double promoteRate = (double) promoted / lb.size();
            double promoteScore = promoteRate * 100;
            double tierScore = Math.min(100, high * 22.0 + midHigh * 15.0 + mid * 6.0);
            midPromoteRate = midEligible == 0 ? 0 : (double) midPromoted / midEligible;

            lianbanScore = clamp(0.4 * heightScore + 0.35 * promoteScore + 0.25 * tierScore - noodle * 7, 0, 100);

            lianbanDetail.put("maxBoard", h);
            lianbanDetail.put("count", lb.size());
            lianbanDetail.put("promoteRate", round2(promoteRate * 100));
            lianbanDetail.put("highCount", high);
            lianbanDetail.put("midHighCount", midHigh);
            lianbanDetail.put("midCount", mid);
            lianbanDetail.put("noodlePenalty", noodle * 7);
            lianbanDetail.put("height", round2(heightScore));
            lianbanDetail.put("promote", round2(promoteScore));
            lianbanDetail.put("tier", round2(tierScore));
        }
        r.setLianban(round2(lianbanScore));
        r.setMidPromoteRate(midPromoteRate);

        // ---------- 维4 首板生态 15% ----------
        double shoubanScore = 0;
        Map<String, Object> shoubanDetail = new LinkedHashMap<String, Object>();
        {
            double firstSealedRate = (first + bomb) == 0 ? 0 : (double) first / (first + bomb);
            double firstPremium = avgNextCloseChg(prev, null); // 近似：沿用昨日涨停溢价
            double countScore = Math.min(100, first * 2.5);
            double rateScore = firstSealedRate * 100;
            double premScore = clamp(50 + firstPremium * 12, 0, 100);
            shoubanScore = clamp(0.5 * countScore + 0.3 * rateScore + 0.2 * premScore, 0, 100);
            shoubanDetail.put("firstCount", first);
            shoubanDetail.put("bombCount", bomb);
            shoubanDetail.put("sealedRate", round2(firstSealedRate * 100));
            shoubanDetail.put("premium", round2(firstPremium));
            shoubanDetail.put("count", round2(countScore));
            shoubanDetail.put("rate", round2(rateScore));
            shoubanDetail.put("prem", round2(premScore));
        }
        r.setShouban(round2(shoubanScore));

        // ---------- 维5 阵眼 15%（龙头分工） ----------
        Map<String, String> dragonRole = new HashMap<String, String>();
        if (r.getMainConceptId() != null) {
            List<StockConceptRel> rels = relMapper.selectList(
                    new LambdaQueryWrapper<StockConceptRel>()
                            .eq(StockConceptRel::getTradeDate, date)
                            .eq(StockConceptRel::getConceptId, r.getMainConceptId())
                            .isNotNull(StockConceptRel::getDragonRole));
            for (StockConceptRel rel : rels) {
                dragonRole.put(rel.getTsCode(), rel.getDragonRole());
            }
        }

        LianbanDaily leaderToday = findLeader(lb);
        double zongLong, zhongJun, genFeng, kaWei, fanBao;
        String leaderAction;
        if (leaderToday != null) {
            leaderAction = "PROMOTE".equals(leaderToday.getLeaderAction()) ? "PROMOTE" : "HOLD";
            int lbNz = leaderToday.getNZones() == null ? 0 : leaderToday.getNZones();
            zongLong = "PROMOTE".equals(leaderToday.getLeaderAction())
                    ? Math.min(100, 60 + lbNz * 6.0) : 55;
            r.setLeaderName(leaderToday.getName());
            r.setLeaderBoard(lbNz);
        } else {
            // 昨日龙头今天缺席：按今日表现定断板/核按钮
            LianbanDaily prevLeader = findLeader(prevLb);
            leaderAction = "BREAK";
            zongLong = 0;
            if (prevLeader != null) {
                LimitUpDaily perf = findRow(today, prevLeader.getTsCode());
                r.setLeaderName(prevLeader.getName());
                r.setLeaderBoard(prevLeader.getNZones() == null ? 0 : prevLeader.getNZones());
                if (perf != null) {
                    double cc = perf.getCloseChg() == null ? 0 : perf.getCloseChg().doubleValue();
                    if (perf.getIsNuke() != null && perf.getIsNuke()) {
                        leaderAction = "NUKE";
                        zongLong = 0;
                    } else {
                        zongLong = cc <= -5 ? 10 : 25;
                    }
                } else {
                    zongLong = 30;
                }
            }
        }
        // 中军：板块龙头股（容量担当），封板为佳
        List<LianbanDaily> zhongJunRows = new ArrayList<LianbanDaily>();
        for (LianbanDaily row : lb) {
            if (row.getIsSectorLeader() != null && row.getIsSectorLeader()) zhongJunRows.add(row);
        }
        if (zhongJunRows.isEmpty()) {
            zhongJun = 0;
        } else {
            double sum = 0;
            for (LianbanDaily row : zhongJunRows) {
                sum += row.getCloseChg() == null ? 0 : row.getCloseChg().doubleValue();
            }
            zhongJun = clamp(50 + (sum / zhongJunRows.size()) * 5, 0, 100);
        }
        // 跟风：主线内非龙头/中军的连板家数
        int genCount = 0;
        for (LianbanDaily row : lb) {
            if (r.getMainConceptId() != null && r.getMainConceptId().equals(row.getConceptMain())
                    && !(row.getIsSpaceLeader() != null && row.getIsSpaceLeader())
                    && !(row.getIsSectorLeader() != null && row.getIsSectorLeader())
                    && !"KA_WEI".equals(dragonRole.get(row.getTsCode()))) {
                genCount++;
            }
        }
        genFeng = Math.min(100, genCount * 20.0);
        // 卡位
        kaWei = 0;
        for (Map.Entry<String, String> e : dragonRole.entrySet()) {
            if ("KA_WEI".equals(e.getValue())) {
                LimitUpDaily perf = findRow(today, e.getKey());
                if (perf != null && perf.getCloseChg() != null
                        && perf.getCloseChg().doubleValue() >= 0) {
                    kaWei = Math.max(kaWei, 80);
                } else {
                    kaWei = Math.max(kaWei, 40);
                }
            }
        }
        // 反包：今日反包板
        fanBao = 0;
        for (LimitUpDaily row : today) {
            if (row.getIsBack() != null && row.getIsBack() && sealedStatus(row.getStatus())) {
                fanBao = Math.max(fanBao, 80);
            }
        }

        double zhenyan = 0.5 * zongLong + 0.2 * zhongJun + 0.15 * genFeng
                + 0.10 * kaWei + 0.05 * fanBao;
        r.setZhenyan(round2(zhenyan));

        Map<String, Object> zhenyanDetail = new LinkedHashMap<String, Object>();
        zhenyanDetail.put("zong_long", round2(zongLong));
        zhenyanDetail.put("zhong_jun", round2(zhongJun));
        zhenyanDetail.put("gen_feng", round2(genFeng));
        zhenyanDetail.put("ka_wei", round2(kaWei));
        zhenyanDetail.put("fan_bao", round2(fanBao));
        zhenyanDetail.put("leaderName", r.getLeaderName());
        zhenyanDetail.put("leaderBoard", r.getLeaderBoard());
        zhenyanDetail.put("leaderAction", leaderAction);
        zhenyanDetail.put("genFengCount", genCount);

        // ---------- 汇总 ----------
        double total = 0.25 * r.getMarket() + 0.20 * r.getConcept() + 0.25 * r.getLianban()
                + 0.15 * r.getShouban() + 0.15 * r.getZhenyan();

        // ---------- 强制退潮判定 ----------
        boolean forced = false;
        String reason = null;
        if (noodle >= 5) {
            forced = true;
            reason = "大面/核按钮达 " + noodle + " 家，亏钱效应爆表";
        } else if (countActiveSerious(date) >= 3) {
            forced = true;
            reason = "重点监控/严重异动达 3 家以上，监管风险规避";
        } else if (!lb.isEmpty() && r.getLianban() <= 15 && maxBoardOverall >= 4) {
            forced = true;
            reason = "连板生态塌陷（仍有≥4板高度但连板分≤15），高度末端防深亏";
        }
        if (forced) {
            total = Math.min(total, 35);
        }
        r.setForceExit(forced);
        r.setForceReason(reason);
        r.setTotal(round2(total));

        Map<String, Object> marketDetail = new LinkedHashMap<String, Object>();
        marketDetail.put("zt", zt);
        marketDetail.put("dt", dt);
        marketDetail.put("bomb", bomb);
        marketDetail.put("avgPremium", round2(avgPremium));
        marketDetail.put("breadth", round2(breadth));
        marketDetail.put("premium", round2(premiumScore));
        marketDetail.put("sealedRate", round2(sealedRate * 100));

        r.getDetails().put("market", marketDetail);
        r.getDetails().put("concept", conceptDetail);
        r.getDetails().put("lianban", lianbanDetail);
        r.getDetails().put("shouban", shoubanDetail);
        r.getDetails().put("zhenyan", zhenyanDetail);
        return r;
    }

    // ================= helpers =================

    /** 四层划分（PRD §1.3）：低位2板 / 中位3-4板 / 中高位5~⌈H/2⌉ / 极高位其余 */
    public static String tierOf(int nZones, int maxBoard) {
        if (nZones <= 1) return "FIRST";
        if (nZones == 2) return "LOW";
        if (nZones <= 4) return "MID";
        int half = (maxBoard + 1) / 2;
        if (nZones >= 5 && nZones <= Math.max(half, 4)) return "MIDHIGH";
        return "HIGH";
    }

    private List<LimitUpDaily> listByDate(LocalDate date) {
        return limitUpMapper.selectList(
                new LambdaQueryWrapper<LimitUpDaily>().eq(LimitUpDaily::getTradeDate, date));
    }

    private LocalDate prevTradingDate(LocalDate date) {
        List<LimitUpDaily> rows = limitUpMapper.selectList(
                new QueryWrapper<LimitUpDaily>().select("DISTINCT trade_date")
                        .lt("trade_date", date)
                        .orderByDesc("trade_date")
                        .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0).getTradeDate();
    }

    private Set<String> sealedCodesPrev(List<LimitUpDaily> prev) {
        Set<String> codes = new HashSet<String>();
        for (LimitUpDaily row : prev) {
            if (sealedStatus(row.getStatus())) codes.add(row.getTsCode());
        }
        return codes;
    }

    /** 昨日集合中今日收盘涨跌幅均值（昨日已回填 next_close_chg） */
    private double avgNextCloseChg(List<LimitUpDaily> prev, Set<String> filter) {
        double sum = 0;
        int n = 0;
        for (LimitUpDaily row : prev) {
            if (filter != null && !filter.contains(row.getTsCode())) continue;
            if (!sealedStatus(row.getStatus())) continue;
            if (row.getNextCloseChg() == null) continue;
            sum += row.getNextCloseChg().doubleValue();
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    private int countActiveSerious(LocalDate date) {
        List<MonitorPool> rows = monitorMapper.selectList(
                new LambdaQueryWrapper<MonitorPool>()
                        .in(MonitorPool::getStatus, "SERIOUS", "KEY_MONITOR", "SUSPEND")
                        .le(MonitorPool::getEnterDate, date)
                        .and(w -> w.isNull(MonitorPool::getExitDate)
                                .or().ge(MonitorPool::getExitDate, date)));
        return rows.size();
    }

    private LianbanDaily findLeader(List<LianbanDaily> rows) {
        LianbanDaily best = null;
        for (LianbanDaily row : rows) {
            if (row.getIsSpaceLeader() != null && row.getIsSpaceLeader()) {
                if (best == null || nz(row) > nz(best)) best = row;
            }
        }
        return best;
    }

    private int nz(LianbanDaily row) {
        return row.getNZones() == null ? 0 : row.getNZones();
    }

    private LimitUpDaily findRow(List<LimitUpDaily> rows, String tsCode) {
        for (LimitUpDaily row : rows) {
            if (row.getTsCode().equals(tsCode)) return row;
        }
        return null;
    }

    private boolean sealedStatus(String status) {
        return "ZT".equals(status) || "ZT_FIRST".equals(status);
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
