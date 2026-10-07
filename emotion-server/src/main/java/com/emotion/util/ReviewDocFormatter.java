package com.emotion.util;

import com.emotion.entity.Anchor;
import com.emotion.entity.DailyBar;
import com.emotion.entity.DailyRecord;
import com.emotion.entity.IndustrySnapshot;
import com.emotion.entity.IndexClose;
import com.emotion.entity.Position;
import com.emotion.entity.Prediction;
import com.emotion.entity.ThemeSnapshot;
import com.emotion.util.ReviewDoc.ThemeRow;
import com.emotion.vo.MarketStocksVO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台数据 → <b>给人读的复盘文档</b>。版式对齐用户手写的每日复盘长文：
 * 当日五节（【一】指数量能 /【二】板块主线 /【三】情绪连板 /【四】持仓操作 /【五】教训）
 * ＋ 结案一节（【六】预案验证）＋ 次日五节（【七】预期定性 /【八】三路径 /【九】关键锚点
 * /【十】持仓处理 /【十一】竞价裁决表）＋ 免责。
 *
 * <p>和 {@link ReviewMdFormatter} 是两件事：那个产<b>可再导入</b>的 md（```meta + 自检降级），
 * 这个是<b>只读</b>的呈现层——选一天，把库里已经存着的系统取数填进他的版式。
 *
 * <p><b>判断性文字平台编不出来，也不从库里回填</b>（{@code doc_notes} 已停用）：每节固定留一行
 * {@code ✍️ 判断：} 占位，表格里的「定性」列同理留 {@code ✍️}，他写完的那份就是当天的 md。
 * 反过来，<b>能从已有数据算出来的数一律算</b>（红盘率、封板率、连板家数、一字数、量能增减、
 * 跌停归行业分桶），口径集中在 {@link ReviewDocMetrics} 里写死并注明分母——他拿这些数去和
 * 开盘啦/东财对，口径不明比缺数更坏。
 *
 * <p>纯函数：入参是一个备好的 {@link Model}，不查库、不联网、不要 Spring，可离线单测。
 * 所有字段可空，空一律渲染成 {@code —} 或一句"为什么空"的说明，绝不 NPE、绝不抛给调用方；
 * 同一份 Model 渲染两次必须逐字节相同（所以这里不许出现 {@code now()}）。
 */
public final class ReviewDocFormatter {

    private ReviewDocFormatter() {
    }

    /** 渲染一份文档需要的全部数据。public 字段，服务层装配时直接填。 */
    public static final class Model {
        public LocalDate date;
        /** 当日记录（系统七数 + 温度/阶段/九维 + 主线龙头 + 仓位/笔记）。null = 这天没有记录。 */
        public DailyRecord today;
        /** 上一交易日记录，只为【三】今昨对照表和【六】的标题日期。null = 找不到上一条。 */
        public DailyRecord prev;
        public List<IndexClose> indexes = new ArrayList<>();
        /** 当日盘面明细（连板梯队/跌停/大面）。null 或其 available=false = 没有明细。 */
        public MarketStocksVO stocks;
        public List<Position> positions = new ArrayList<>();
        /** 当日预判（PLAN）+ 当日对答案（ANSWER）混在一起，渲染时按 kind 分流。 */
        public List<Prediction> predictions = new ArrayList<>();
        public List<Anchor> anchors = new ArrayList<>();
        /** 只有那天导入过 md 才带得出的题材（旧 {@code 题材:} 键）。空 = 无从带出。 */
        public List<ThemeRow> themes = new ArrayList<>();
        /** 当日核心题材 Top5（{@code t_theme_daily_snapshot}，日内页落的盘）。空 = 那天没跑过日内。 */
        public List<ThemeSnapshot> coreThemes = new ArrayList<>();
        /** 当日板块快照（{@code t_industry_daily_snapshot}，T5 从涨停池现算）。空 = 没拉过行情。 */
        public List<IndustrySnapshot> industries = new ArrayList<>();
        /** 涨停池 / 炸板池家数（封板率的分母）。null = 这天没有盘面明细。 */
        public Integer poolZt;
        public Integer poolZb;
        /**
         * 标的代码 → <b>当天</b>收盘价（{@code t_market_stock.close_price}）。
         * 持仓那三节的「现价」以它为准；台账里的 {@code current_price} 结转时会带着录入当天的价
         * 一路不变，所以这里没有的行才退回台账值并标 {@code †}。空 map = 这天没有盘面明细。
         */
        public Map<String, BigDecimal> dayCloses = new HashMap<>();
        /** 沪指当天的日 K（{@code t_daily_bar}）：【七】的日内高低点只有这一条路。null = 库里没缓存过。 */
        public DailyBar indexBar;
        /** 派生读数。服务层用 {@link ReviewDocMetrics#from} 算好后放进；null 时渲染层按空处理。 */
        public ReviewDocMetrics metrics;
        /** 下一交易日（按交易日历）。null 时渲染层回落到"跳过周末"的日历近似。 */
        public LocalDate nextDate;
    }

    public static String render(Model m) {
        if (m == null || m.date == null) {
            throw new IllegalArgumentException("导出复盘文档需要 date");
        }
        List<String> parts = new ArrayList<>();
        parts.add(title(m));
        parts.add(sectionIndex(m));
        parts.add(sectionTheme(m));
        parts.add(sectionEmotion(m));
        parts.add(sectionPosition(m));
        parts.add(sectionLesson(m));
        parts.add(sectionAnswer(m));
        parts.add(sectionOutlook(m));
        parts.add(sectionPlan(m));
        parts.add(sectionAnchor(m));
        parts.add(sectionNextPosition(m));
        parts.add(sectionAuction(m));
        parts.add(disclaimer());
        return String.join("\n\n", parts).trim() + "\n";
    }

    // ---- 标题 ----

    private static String title(Model m) {
        StringBuilder sb = new StringBuilder();
        LocalDate next = nextDate(m);
        sb.append("# ").append(md(m.date)).append("（").append(weekday(m.date)).append("）完整复盘 + ")
                .append(md(next)).append("（").append(weekday(next)).append("）预期定性");
        DailyRecord r = m.today;
        if (r != null && notBlank(r.getStage())) {
            String label = CycleStageMachine.label(r.getStage(), r.getStagePhase(), r.getStageSeq());
            sb.append(" · ").append(label.isEmpty() ? r.getStage() : label);
            if (r.getStageOverridden() != null && r.getStageOverridden() == 1) {
                sb.append("（已人工改判）");
            }
        }
        return sb.toString();
    }

    // ---- 【一、指数与量能】 ----

    private static String sectionIndex(Model m) {
        StringBuilder sb = head("【一、" + md(m.date) + " 指数与量能】");
        for (Map.Entry<String, String> e : ReviewImportParser.KNOWN_INDEXES.entrySet()) {
            IndexClose ic = findIndex(m.indexes, e.getKey());
            sb.append("- **").append(e.getValue()).append("**：");
            if (ic == null) {
                sb.append("—\n");
                continue;
            }
            sb.append(ic.getClosePrice() == null ? "—" : plain(ic.getClosePrice()));
            if (ic.getChangePct() != null) {
                sb.append("（").append(signed(ic.getChangePct(), "%")).append("）");
            }
            sb.append("\n");
        }
        ReviewDocMetrics x = metrics(m);
        DailyRecord r = m.today;
        if (r == null) {
            sb.append("\n> 这天还没有系统读数（没拉过行情），成交/涨跌家数/涨停跌停/连板高度都无从取。\n");
            return prompt(sb, "核心定性：这波是转强、弱修复还是退潮？指数和个股背离吗？量能与家数支持你的定性吗？");
        }
        sb.append("- **全市场成交**：").append(vol(r.getTotalVolume()));
        if (x.volumeDelta != null) {
            sb.append("（较上一记录 ").append(signed(x.volumeDelta, " 亿")).append("）");
        }
        sb.append("\n");
        sb.append("- **上涨/下跌**：").append(breadth(r.getUpCount(), r.getDownCount())).append("\n");
        sb.append("- **涨停/跌停**：").append(plain(r.getLimitUpCount())).append(" / ")
                .append(plain(r.getLimitDownCount())).append("\n");
        sb.append("- **连板**：").append(num(x.lianbanCount)).append(" 家 2 板及以上｜最高 ")
                .append(plain(r.getMaxConsecutiveLimit())).append(" 板｜一字 ")
                .append(num(x.yiziCount)).append(" 家\n");
        sb.append("- **封板率**：").append(pct(x.sealRate))
                .append("（涨停池 /(涨停池 + 炸板池)，炸板 ").append(dash(m.poolZb)).append(" 家）\n");
        sb.append("\n").append(tempLine(r)).append("\n");
        return prompt(sb, "核心定性：是修复、高位补跌还是退潮深化？放量下跌和缩量下跌不是一回事，写清楚。");
    }

    // ---- 【二、板块主线】 ----

    private static String sectionTheme(Model m) {
        StringBuilder sb = head("【二、" + md(m.date) + " 板块主线】");
        DailyRecord r = m.today;
        if (r != null) {
            sb.append("- **主线**：").append(dash(r.getMainTheme()))
                    .append(" ｜ **总龙头**：").append(leaderWithStatus(r))
                    .append(" ｜ **中军**：").append(dash(r.getMidCapStock())).append("\n\n");
        }
        sb.append("**强/活方向**（当日核心题材 Top5，按系统题材榜的名次排）\n\n");
        if (m.coreThemes == null || m.coreThemes.isEmpty()) {
            sb.append("（这天没有核心题材快照——日内题材榜没跑过或没入库。这一栏请按盘面手写；"
                    + "下面那张表即便有，也只是板块侧的涨停数，不是题材强弱。）\n\n");
        } else {
            sb.append("| 题材 | 强度 | 涨停 | 最高板 | 持续 | 龙头 | 生命周期 | 定性 |\n")
                    .append("|---|---|---|---|---|---|---|---|\n");
            for (ThemeSnapshot t : m.coreThemes) {
                sb.append("| ").append(cell(t.getThemeName())).append(" | ")
                        .append(plain(t.getStrength())).append(" | ")
                        .append(dash(t.getZtCount())).append(" | ")
                        .append(dash(t.getMaxBoard())).append(" | ")
                        .append(t.getContinuousDays() == null ? "—" : t.getContinuousDays() + " 天").append(" | ")
                        .append(leaderCell(t)).append(" | ")
                        .append(cell(t.getLifecycle())).append(" | ✍️ |\n");
            }
            sb.append("\n");
        }
        if (m.industries != null && !m.industries.isEmpty()) {
            sb.append("**板块涨停榜**（通达信二级行业，当天落盘的板块快照，前 5）\n\n");
            sb.append("| 行业 | 涨停 | 最高板 | 一字 | 大面 | 梯队 |\n|---|---|---|---|---|---|\n");
            int shown = 0;
            for (IndustrySnapshot i : m.industries) {
                if (shown++ >= 5) {
                    break;
                }
                sb.append("| ").append(cell(i.getIndustry())).append(" | ")
                        .append(dash(i.getZtCount())).append(" | ")
                        .append(dash(i.getMaxBoard())).append(" | ")
                        .append(dash(i.getYiziCnt())).append(" | ")
                        .append(dash(i.getBigLossCnt())).append(" | ")
                        .append(cell(i.getTierLevels())).append(" |\n");
            }
            sb.append("\n");
        }
        sb.append("**弱/死方向**（当日跌停按行业归桶，家数降序；行业是东财口径，不等于题材名）\n\n");
        List<ReviewDocMetrics.Sector> weak = metrics(m).weakSectors;
        if (weak.isEmpty()) {
            sb.append("（没有跌停明细——这天没拉过盘面快照，或真的一只跌停都没有。）\n\n");
        } else {
            sb.append("| 行业(东财) | 跌停 | 其中大面 | 代表票(当日跌幅) | 定性 |\n")
                    .append("|---|---|---|---|---|\n");
            for (ReviewDocMetrics.Sector s : weak) {
                sb.append("| ").append(cell(s.industry)).append(" | ").append(s.count).append(" | ")
                        .append(s.bigLossCount == 0 ? "—" : s.bigLossCount).append(" | ")
                        .append(s.leaders.isEmpty() ? "—" : String.join("、", s.leaders)).append(" | ✍️ |\n");
            }
            sb.append("\n");
        }
        if (m.themes != null && !m.themes.isEmpty()) {
            sb.append("**那天 md 里存过的题材行**（旧 `题材:` 键，仅供对照，不参与上面的排序）\n");
            for (ThemeRow t : m.themes) {
                sb.append("  - ").append(dash(t.getTheme()));
                if (t.getStrength() != null) {
                    sb.append(" 强度 ").append(t.getStrength());
                }
                if (notBlank(t.getStatus())) {
                    sb.append(" · ").append(t.getStatus());
                }
                if (notBlank(t.getLeaderName())) {
                    sb.append(" · 龙头 ").append(t.getLeaderName());
                }
                sb.append("\n");
            }
        }
        return prompt(sb, "每条主线一句「翻译」：板块合力还是个股穿越、一字独食还是换手？"
                + "真活线怎么排（谁＞谁）、哪些判死刑——这个排序和判词系统不猜。");
    }

    // ---- 【三、情绪与连板生态】 ----

    private static String sectionEmotion(Model m) {
        StringBuilder sb = head("【三、" + md(m.date) + " 情绪与连板生态】");
        sb.append("| 指标 | ").append(todayHead(m)).append(" | ").append(prevHead(m)).append(" | 变化 |")
                .append("\n|---|---|---|---|\n");
        compareRow(sb, "温度(°)", decimal(m.today, DailyRecord::getTemperature),
                decimal(m.prev, DailyRecord::getTemperature), 1);
        compareRow(sb, "成交额(亿)", decimal(m.today, DailyRecord::getTotalVolume),
                decimal(m.prev, DailyRecord::getTotalVolume), 2);
        compareRow(sb, "红盘率(%)", rate(m.today), rate(m.prev));
        compareRow(sb, "涨停家数", count(m.today, DailyRecord::getLimitUpCount),
                count(m.prev, DailyRecord::getLimitUpCount), 0);
        compareRow(sb, "跌停家数", count(m.today, DailyRecord::getLimitDownCount),
                count(m.prev, DailyRecord::getLimitDownCount), 0);
        compareRow(sb, "上涨家数", count(m.today, DailyRecord::getUpCount),
                count(m.prev, DailyRecord::getUpCount), 0);
        compareRow(sb, "下跌家数", count(m.today, DailyRecord::getDownCount),
                count(m.prev, DailyRecord::getDownCount), 0);
        compareRow(sb, "连板高度", count(m.today, DailyRecord::getMaxConsecutiveLimit),
                count(m.prev, DailyRecord::getMaxConsecutiveLimit), 0);
        sb.append("\n> 红盘率＝涨 /(涨 + 跌)，<b>库里没有平盘家数</b>，分母比他手记里的口径小一圈，"
                + "所以这个数会比含平盘的读数略高。封板率、连板家数、一字数只在【一】给当日值——"
                + "上一天的池计数没留，对照不出来。\n\n");
        if (m.today != null) {
            sb.append("**九维读数**（分 −1..3，未评的维不进分母）\n\n")
                    .append(ReviewMdFormatter.dimTable(m.today)).append("\n");
        }
        sb.append(ladder(m.stocks));
        return prompt(sb, "核心矛盾：撑门面的高度和真实广度是不是两回事？今天最关键的变化是哪一条？");
    }

    private static String ladder(MarketStocksVO s) {
        StringBuilder sb = new StringBuilder();
        if (s == null || !s.isAvailable()) {
            sb.append("- **连板梯队**：—（这天没有盘面明细，拉一次行情快照才会有）\n");
            return sb.toString();
        }
        if (s.getLadder().isEmpty()) {
            sb.append("- **连板梯队**：无 2 板以上（全是首板）\n");
        } else {
            sb.append("**连板梯队**\n");
            for (MarketStocksVO.Tier t : s.getLadder()) {
                List<String> names = new ArrayList<>();
                for (MarketStocksVO.Item it : t.getStocks()) {
                    appendName(names, it);
                }
                sb.append("- **").append(t.getBoard()).append(" 板**：")
                        .append(String.join("、", names)).append("\n");
            }
        }
        sb.append("- **首板**：").append(s.getFirstBoardCount()).append(" 家");
        if (!s.getGapBoards().isEmpty()) {
            List<String> gaps = new ArrayList<>();
            for (Integer g : s.getGapBoards()) {
                gaps.add(String.valueOf(g));
            }
            sb.append("（断档 ").append(String.join("、", gaps)).append(" 板）");
        }
        sb.append("\n");
        sb.append("- **跌停 ").append(s.getLimitDownCount()).append(" 家**：")
                .append(names(s.getLimitDown())).append("\n");
        if (!s.getBigLoss().isEmpty()) {
            sb.append("- **大面**：").append(names(s.getBigLoss())).append("\n");
        }
        return sb.toString();
    }

    /** 梯队里一格：名字后面带行业，同一档扫一眼就能看出是几条线在接力。行业空就只给名字。 */
    private static void appendName(List<String> out, MarketStocksVO.Item it) {
        out.add(notBlank(it.getIndustry()) ? it.getName() + "(" + it.getIndustry().trim() + ")" : it.getName());
    }

    // ---- 【四、持仓操作】 ----

    private static String sectionPosition(Model m) {
        StringBuilder sb = head("【四、" + md(m.date) + " 持仓操作】");
        if (m.positions == null || m.positions.isEmpty()) {
            sb.append("（这天没有导入过持仓台账）\n");
            return prompt(sb, "有持仓就逐只写「实际动作 vs 应做动作 vs 纪律」；空仓写为什么空。");
        }
        // 卖价/卖出量是「当日了结」那一笔的真实成交，与现价（收盘）并列摆出来，两者不等就是差价。
        sb.append("| 标的 | 成本 | 现价 | 卖价 | 卖出量 | 浮动 | 动作 | 应做 | 纪律 |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        boolean stale = false;
        for (Position p : m.positions) {
            BigDecimal close = dayClose(m, p);
            stale |= close == null && p.getCurrentPrice() != null;
            BigDecimal floatPct = close == null ? p.getFloatPct() : floatOf(p.getCostPrice(), close);
            sb.append("| ").append(cell(p.getStockName())).append(" ").append(cell(p.getStockCode())).append(" | ")
                    .append(price(p.getCostPrice())).append(" | ")
                    .append(shownPrice(m, p)).append(" | ")
                    .append(price(p.getSellPrice())).append(" | ")
                    .append(p.getSellQty() == null ? "—" : plain(p.getSellQty())).append(" | ")
                    .append(floatPct == null ? "—" : signed(floatPct, "%")).append(" | ")
                    .append(cell(p.getAction())).append(" | ")
                    .append(cell(p.getPlannedAction())).append(" | ")
                    .append(cell(p.getDiscipline())).append(" |\n");
        }
        sb.append("\n> 现价＝<b>当日收盘</b>（当天盘面明细里的 close_price），浮动按它对成本现算，")
                .append("台账里结转过来的旧价不进这一列。");
        if (stale) {
            sb.append("带 † 的那几只这天不在任何池里、库里没有它的当日收盘价，只能沿用台账那一行的值——"
                    + "那通常是买入当天的价，别当今天的收盘读。");
        }
        sb.append("\n");
        return prompt(sb, "每只一句处置评价（格局没破 / 平盘走 / 该清没清）；"
                + "卖了就记成交均价，别拿收盘价当卖价。");
    }

    // ---- 【五、教训补进体系】 ----

    private static String sectionLesson(Model m) {
        StringBuilder sb = head("【五、" + md(m.date) + " 教训补进体系】");
        sb.append("这一节系统一律不填——它要的是<b>从今天的盘面里长出来的规则</b>，")
                .append("不是任何库里的数。写在下面，写完连同整份 md 存档。\n\n");
        sb.append("1. ✍️\n2. ✍️\n3. ✍️\n\n");
        sb.append("> 新口号（替换上一版）：✍️\n");
        return prompt(sb, "每条教训要能改下一次的动作，写成「什么条件下必须怎么做」，不是感想。");
    }

    // ---- 【六、预案验证（结案）】 ----

    private static String sectionAnswer(Model m) {
        String from = m.prev == null || m.prev.getTradeDate() == null
                ? "上一记录" : md(m.prev.getTradeDate());
        StringBuilder sb = head("【六、" + from + " 预案验证 · " + md(m.date) + " 结案】");
        List<Prediction> answers = filter(m.predictions, true);
        if (answers.isEmpty()) {
            sb.append("（今天没有登记 `对答案`——即没有回填上一交易日的同名预判）\n\n");
        } else {
            sb.append("| 昨日预判 | 兑现 | 说明 |\n|---|---|---|\n");
            for (Prediction a : answers) {
                sb.append("| ").append(cell(a.getName())).append(" | ")
                        .append(cell(a.getResult())).append(" | ")
                        .append(cell(a.getResultNote())).append(" |\n");
            }
            sb.append("\n");
        }
        // 结案时要对着的那把数，系统直接给出来，省得他翻页去找；路径成没成仍是他的判断。
        sb.append("> 当日实际读数：").append(actualReadings(m)).append("\n");
        return prompt(sb, "每条路径逐条判 触发 / 未触发，写清错在定位 / 选股 / 执行哪一层。");
    }

    /** 结案要用的一行读数：涨跌停、红盘率、最高板、成交额、温度阶段。缺的项写 —，不猜。 */
    private static String actualReadings(Model m) {
        DailyRecord r = m.today;
        if (r == null) {
            return "—（这天没有系统读数）";
        }
        ReviewDocMetrics x = metrics(m);
        StringBuilder s = new StringBuilder();
        s.append("涨停 ").append(dash(r.getLimitUpCount()))
                .append(" / 跌停 ").append(dash(r.getLimitDownCount()))
                .append(" / 红盘率 ").append(pct(x.redRate))
                .append(" / 连板 ").append(num(x.lianbanCount)).append(" 家")
                .append(" / 最高 ").append(dash(r.getMaxConsecutiveLimit())).append(" 板")
                .append(" / 成交 ").append(vol(r.getTotalVolume()));
        if (r.getTemperature() != null) {
            s.append(" / 温度 ").append(plain(r.getTemperature())).append("°");
        }
        if (notBlank(r.getStage())) {
            s.append(" / 阶段 ").append(dash(r.getStage()));
        }
        return s.toString();
    }

    // ---- 【七、次日预期定性】 ----

    private static String sectionOutlook(Model m) {
        LocalDate next = nextDate(m);
        StringBuilder sb = head("【七、" + md(next) + "（" + weekday(next) + "）预期定性】");
        sb.append("核心四看（每看一句：看什么数、什么算过、什么算不过）：\n\n");
        sb.append("1. ✍️ 涨跌停与红盘率能不能回到什么水平？\n");
        sb.append("2. ✍️ 指数关键位：").append(indexHint(m)).append("\n");
        sb.append("3. ✍️ 高度票开板/一字怎么演？\n");
        sb.append("4. ✍️ 活线谁晋级、谁断？\n\n");
        sb.append("> 外围与节奏（休市、节前、汇率利率这类不在库里的变量）：✍️\n");
        String stage = m.today == null ? "—" : dash(m.today.getStage());
        return prompt(sb, "一句话定性明天，和系统给的阶段（" + stage
                + "）不一致就写为什么不一致。");
    }

    /**
     * 指数关键位：收盘点位来自 {@code t_index_close}，<b>当天的日内开高低来自 {@code t_daily_bar}
     * 的沪指日 K</b>（{@code t_index_close} 只有收盘＋涨跌幅，四价在它那里没有）。
     *
     * <p>日 K 是带保鲜期的缓存，只有被打开过的标的才会落进行，缺行就说缺——
     * 关键位是每天要看的东西，拿"没有"比拿上一天的区间顶上当今天的好。
     */
    private static String indexHint(Model m) {
        IndexClose sh = findIndex(m.indexes, "000001");
        DailyBar bar = m.indexBar;
        boolean hasClose = sh != null && sh.getClosePrice() != null;
        boolean hasBar = bar != null && (bar.getHighPrice() != null || bar.getLowPrice() != null);
        if (!hasClose && !hasBar) {
            return "—（这天既没有上证收盘点位，也没有沪指日 K）";
        }
        StringBuilder sb = new StringBuilder("上证昨收 ").append(hasClose ? plain(sh.getClosePrice()) : "—");
        if (hasBar) {
            sb.append("｜当天日 K 开 ").append(plain(bar.getOpenPrice()))
                    .append(" / 高 ").append(plain(bar.getHighPrice()))
                    .append(" / 低 ").append(plain(bar.getLowPrice()));
        } else {
            sb.append("（这天的沪指日 K 库里没缓存过，日内高低点取不到）");
        }
        sb.append("；关键位由你定，日 K 只到日线级，盘中分钟级的下探库里没有");
        return sb.toString();
    }

    // ---- 【八、次日三路径】 ----

    private static String sectionPlan(Model m) {
        LocalDate next = nextDate(m);
        StringBuilder sb = head("【八、" + md(next) + "（" + weekday(next) + "）三路径】");
        List<Prediction> plans = filter(m.predictions, false);
        if (plans.isEmpty()) {
            sb.append("（没有登记的明日预判）\n");
            return prompt(sb, "写三路径：各自概率、触发条件、每路径的持仓动作与总仓上限。");
        }
        for (Prediction p : plans) {
            sb.append("- **").append(dash(p.getName())).append("**：");
            if (p.getProb() != null) {
                sb.append("概率 ").append(p.getProb()).append("%");
            }
            if (notBlank(p.getConditionText())) {
                sb.append(p.getProb() != null ? " ｜触发 " : "触发 ").append(oneLine(p.getConditionText()));
            }
            sb.append(" ｜动作 ✍️\n");
        }
        sb.append("\n> 「动作」= 这条路径走出来时，每只持仓怎么处理、总仓上限多少。")
                .append("预判的名称/概率/触发条件是从库里带的，动作那一栏系统不代填。\n");
        return prompt(sb, "三条路径的概率加起来该是 100%；写不出来说明还没想完。");
    }

    // ---- 【九、关键锚点】 ----

    private static String sectionAnchor(Model m) {
        LocalDate next = nextDate(m);
        StringBuilder sb = head("【九、" + md(next) + " 关键锚点】");
        sb.append("| 锚点 | 标的 | 意义 |\n|---|---|---|\n");
        int rows = 0;
        List<String> seen = new ArrayList<>();
        if (m.anchors != null) {
            for (Anchor a : m.anchors) {
                rows++;
                if (notBlank(a.getStockCode())) {
                    seen.add(a.getStockCode());
                }
                sb.append("| ").append(cell(a.getCycleTag())).append(" | ")
                        .append(cell(a.getStockName())).append(" ").append(cell(a.getStockCode()))
                        .append("（").append(roleCn(a.getRole())).append(" · ").append(span(a)).append("）")
                        .append(" | ").append(notBlank(a.getNote()) ? cell(a.getNote()) : "✍️").append(" |\n");
            }
        }
        for (Position p : positionList(m)) {
            // 同一只票既是阵眼又持仓时只留锚点那一行：并排两行读起来像渲染坏了
            if (seen.contains(p.getStockCode())) {
                continue;
            }
            rows++;
            sb.append("| 持仓位 | ").append(cell(p.getStockName())).append(" ").append(cell(p.getStockCode()))
                    .append("（现价 ").append(shownPrice(m, p)).append("）")
                    .append(" | ✍️ |\n");
        }
        if (rows == 0) {
            sb.append("| — | —（这天既没有在册阵眼也没有持仓） | ✍️ |\n");
        }
        sb.append("\n> 「锚点」这一列是<b>周期阵眼/总龙</b>（去「主线龙头」页登记），")
                .append("不是每天的题材位；你按题材写的锚点（重组高度、汽车、机器人……）请在表里自己加行。\n");
        return prompt(sb, "每个锚点一句话：它给什么信号、破了意味着什么。");
    }

    // ---- 【十、次日持仓处理】 ----

    private static String sectionNextPosition(Model m) {
        LocalDate next = nextDate(m);
        StringBuilder sb = head("【十、" + md(next) + " 持仓处理】");
        List<Position> ps = positionList(m);
        if (ps.isEmpty()) {
            sb.append("（这天没有持仓，空仓就写为什么空）\n");
            return prompt(sb, "不碰清单（哪些票/哪些方向一律不参与）也写在这里。");
        }
        sb.append("| 标的 | 现价 | 高开 | 炸板 | 平开/低开 | 跌停 | 总纲 |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (Position p : ps) {
            sb.append("| ").append(cell(p.getStockName())).append(" ").append(cell(p.getStockCode())).append(" | ")
                    .append(shownPrice(m, p)).append(" | ")
                    .append(cell(p.getPlanOpen())).append(" | ")
                    .append(cell(p.getPlanBreak())).append(" | ")
                    .append(cell(p.getPlanLow())).append(" | ")
                    .append(cell(p.getPlanFall())).append(" | ")
                    .append(cell(p.getNextDayPlan())).append(" |\n");
        }
        sb.append("\n> 四档按<b>开盘形态</b>分（高开 / 炸板 / 平低开 / 跌停），你手记里那四档是按<b>价位</b>分的"
                + "（开>x 留、区间不封减半、破 y 清、破 z 无条件清）——价位写进哪一格都读得通，"
                + "但这张表的分档轴是形态不是价位，别当成同一个东西。\n");
        return prompt(sb, "每只的仓位动作要能执行：不板清、竞价清、减半、留——四选一，别写\"看情况\"。");
    }

    // ---- 【十一、竞价裁决表】 ----

    private static String sectionAuction(Model m) {
        LocalDate next = nextDate(m);
        StringBuilder sb = head("【十一、" + md(next) + " 竞价裁决表】");
        sb.append("这张表的<b>列每天换</b>（今天看新华传媒/江淮/雪龙/红盘跌停，明天是另一把票），")
                .append("所以系统不代填、也不给固定表头——按当天要裁决的东西自己摆列。\n\n");
        sb.append("| 锚点① | 锚点② | 锚点③ | 全局读数 | 裁决 |\n|---|---|---|---|---|\n");
        sb.append("| ✍️ | ✍️ | ✍️ | 参考【一】：涨停 ").append(dash(m.today == null ? null : m.today.getLimitUpCount()))
                .append(" / 跌停 ").append(dash(m.today == null ? null : m.today.getLimitDownCount()))
                .append(" / 红盘率 ").append(pct(metrics(m).redRate)).append(" | ✍️ |\n");
        return prompt(sb, "裁决写成可执行的组合条件：哪几样同时成立才开新仓、开多少；不同时成立就是空仓。");
    }

    private static String disclaimer() {
        return "## 【附、免责声明】\n\n"
                + "> 以上行情数、温度/阶段、九维、连板梯队、持仓、预判、锚点均为平台系统取数自动生成，"
                + "只作记录与复盘用，不构成任何买卖建议；判断与交易决策由你自己写、自己负责。";
    }

    // ---- 版式小工具 ----

    private static StringBuilder head(String title) {
        StringBuilder sb = new StringBuilder("## ").append(title).append("\n\n");
        return sb;
    }

    /**
     * 收一节：固定留一行写作提示。
     *
     * <p>这里<b>不</b>回填他写过的判断原文——{@code t_daily_record.doc_notes} 那一列已经不再有任何读写方，
     * 判断就写在这份 md 的小节里，下次导入由 {@code ReviewImportParser} 认。
     */
    private static String prompt(StringBuilder sb, String hint) {
        sb.append("\n> ✍️ 判断：").append(hint).append("\n");
        return trim(sb);
    }

    private static String trim(StringBuilder sb) {
        String s = sb.toString().replaceAll("[ \t]+\n", "\n");
        while (s.endsWith("\n")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ---- AI 草稿的令牌表 ----

    /** 指数代码 → 令牌名。名字里不带数字（{@code {科创}} 而非 {@code {科创50}}），理由见 {@link #aiFacts}。 */
    private static final Map<String, String> AI_INDEX_TOKEN = aiIndexTokens();

    private static Map<String, String> aiIndexTokens() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("000001", "上证");
        m.put("399001", "深证");
        m.put("399006", "创业板");
        m.put("000688", "科创");
        m.put("899050", "北证");
        return Collections.unmodifiableMap(m);
    }

    /**
     * AI 草稿可以引用的<b>全部事实</b>：令牌名 → 系统算好、格式化好的值。
     *
     * <p>模型侧永远只看到令牌名和它的语义（{@link com.emotion.ai.ReviewAiPrompt}），看不到这里的
     * value——它没有数可抄，也就没有数可编；数是在回填那一步才进句子的，走的是和正文同一条
     * 格式化管线（{@link #plain} / {@link #signed} / {@link #pct} / {@link #vol}），所以草稿里的
     * 「52.8°」和【一】表格里的「52.8」不可能对不上。
     *
     * <p><b>读不到的键整条不进表</b>，而不是给一个 {@code —}：提示里写「温度 {@code {温度} }」而回填
     * 出来是破折号，比干脆不提温度更容易被当成一个真读数读进去。
     *
     * <p>令牌名一律<b>不含阿拉伯数字</b>：数字门卫查的是<b>回填之前</b>的模型原文，令牌自己带数字
     * 会把「老老实实引用了令牌」的好草稿误杀成违规。
     */
    public static Map<String, String> aiFacts(Model m) {
        Map<String, String> f = new LinkedHashMap<String, String>();
        if (m.date != null) {
            put(f, "日期", md(m.date) + "（" + weekday(m.date) + "）");
            LocalDate next = nextDate(m);
            if (next != null) {
                put(f, "次日", md(next) + "（" + weekday(next) + "）");
            }
        }
        DailyRecord r = m.today;
        DailyRecord p = m.prev;
        ReviewDocMetrics x = metrics(m);
        if (r == null) {
            return f;
        }
        put(f, "温度", r.getTemperature() == null ? null : plain(r.getTemperature()) + "°");
        put(f, "总分", r.getTotalScore() == null ? null : plain(r.getTotalScore()) + " 分");
        put(f, "进分维", r.getScoredDims() == null ? null : r.getScoredDims() + "/9 维");
        put(f, "阶段", stageLabel(r));
        if (p != null) {
            put(f, "昨温度", p.getTemperature() == null ? null : plain(p.getTemperature()) + "°");
            put(f, "昨阶段", stageLabel(p));
            if (r.getTemperature() != null && p.getTemperature() != null) {
                put(f, "温度差", signed(r.getTemperature().subtract(p.getTemperature()), "°"));
            }
        }
        put(f, "成交额", r.getTotalVolume() == null ? null : vol(r.getTotalVolume()));
        put(f, "成交额差", x.volumeDelta == null ? null : signed(x.volumeDelta, " 亿"));
        put(f, "涨家", plain(r.getUpCount()));
        put(f, "跌家", plain(r.getDownCount()));
        put(f, "红盘率", x.redRate == null ? null : pct(x.redRate));
        if (p != null) {
            put(f, "昨涨停", plain(p.getLimitUpCount()));
            put(f, "昨跌停", plain(p.getLimitDownCount()));
            BigDecimal prevRate = rate(p);
            put(f, "昨红盘率", prevRate == null ? null : plain(prevRate) + "%");
        }
        put(f, "涨停", plain(r.getLimitUpCount()));
        put(f, "跌停", plain(r.getLimitDownCount()));
        put(f, "最高板", r.getMaxConsecutiveLimit() == null ? null
                : r.getMaxConsecutiveLimit() + " 板");
        put(f, "连板家数", x.lianbanCount == null ? null : x.lianbanCount + " 家二板及以上");
        put(f, "一字", x.yiziCount == null ? null : x.yiziCount + " 家");
        put(f, "炸板", m.poolZb == null ? null : m.poolZb + " 家");
        put(f, "封板率", x.sealRate == null ? null : pct(x.sealRate));
        if (m.stocks != null && m.stocks.isAvailable() && m.stocks.getBigLoss() != null) {
            put(f, "大面", m.stocks.getBigLoss().size() + " 家");
        }
        for (IndexClose ic : safeIndexes(m)) {
            String token = AI_INDEX_TOKEN.get(ic.getIndexCode());
            if (token == null || ic.getClosePrice() == null) {
                continue;
            }
            f.put(token, plain(ic.getClosePrice())
                    + (ic.getChangePct() == null ? "" : "（" + signed(ic.getChangePct(), "%") + "）"));
        }
        put(f, "主线", blankToNull(r.getMainTheme()));
        if (notBlank(r.getLeadingStock())) {
            put(f, "总龙头", leaderWithStatus(r));
        }
        put(f, "中军", blankToNull(r.getMidCapStock()));
        List<Position> ps = positionList(m);
        if (!ps.isEmpty()) {
            List<String> names = new ArrayList<String>();
            for (Position pos : ps) {
                if (notBlank(pos.getStockName())) {
                    names.add(pos.getStockName());
                }
            }
            put(f, "持仓", names.isEmpty() ? null : String.join("、", names));
            put(f, "持仓只数", names.isEmpty() ? null : names.size() + " 只");
        }
        return f;
    }

    /** 只在值不是 {@code null} 也不是空串时入表——缺数就是「这条不提」。 */
    private static void put(Map<String, String> f, String token, String value) {
        if (notBlank(value)) {
            f.put(token, value);
        }
    }

    private static String stageLabel(DailyRecord r) {
        String label = CycleStageMachine.label(r.getStage(), r.getStagePhase(), r.getStageSeq());
        return label.isEmpty() ? null : label;
    }

    private static List<IndexClose> safeIndexes(Model m) {
        return m.indexes == null ? new ArrayList<IndexClose>() : m.indexes;
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s : null;
    }

    // ---- 取值 / 格式化 ----

    private static ReviewDocMetrics metrics(Model m) {
        return m.metrics == null ? ReviewDocMetrics.empty() : m.metrics;
    }

    private static List<Position> positionList(Model m) {
        return m.positions == null ? new ArrayList<Position>() : m.positions;
    }

    private static String tempLine(DailyRecord r) {
        StringBuilder sb = new StringBuilder("> 系统读数：温度 ");
        sb.append(r.getTemperature() == null ? "—" : plain(r.getTemperature())).append("°");
        String label = CycleStageMachine.label(r.getStage(), r.getStagePhase(), r.getStageSeq());
        sb.append(" · 阶段 ").append(label.isEmpty() ? dash(r.getStage()) : label);
        sb.append(" · 总分 ").append(dash(r.getTotalScore()));
        sb.append(" · 进分 ").append(r.getScoredDims() == null ? "—" : r.getScoredDims() + "/9 维");
        return sb.toString();
    }

    private static String leaderWithStatus(DailyRecord r) {
        String leader = dash(r.getLeadingStock());
        return notBlank(r.getLeadingStockStatus()) ? leader + " · " + r.getLeadingStockStatus() : leader;
    }

    /** 题材快照里的龙头一格：名字 + 当日板数，没登记龙头就空着。 */
    private static String leaderCell(ThemeSnapshot t) {
        if (!notBlank(t.getLeaderName())) {
            return "—";
        }
        return t.getLeaderBoard() == null ? cell(t.getLeaderName())
                : t.getLeaderName() + " " + t.getLeaderBoard() + " 板";
    }

    private static String vol(BigDecimal v) {
        return v == null ? "—" : plain(v) + " 亿";
    }

    /** 价格是 DECIMAL(10,3) 补出来的（11.170）；A 股报价两位，就按两位写。 */
    private static String price(BigDecimal v) {
        return v == null ? "—" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 这行持仓<b>当天</b>的收盘价：没有盘面明细行就是 null——缺数继续按缺数走，
     * 不拿上一天的价冒充今天的。
     */
    private static BigDecimal dayClose(Model m, Position p) {
        if (m.dayCloses == null || p.getStockCode() == null) {
            return null;
        }
        return m.dayCloses.get(p.getStockCode());
    }

    /** 台账里的 current_price 只在录入当天一定对得上，结转过来就成了旧价，所以标 †。 */
    private static String shownPrice(Model m, Position p) {
        BigDecimal close = dayClose(m, p);
        if (close != null) {
            return price(close);
        }
        BigDecimal stored = p.getCurrentPrice();
        return stored == null ? "—" : price(stored) + "†";
    }

    /** 与写侧 {@code ReviewLedgerService.floatPctOf} 同式（%、2 位、HALF_UP）；那个是包内私有，这里不跨包引。 */
    private static BigDecimal floatOf(BigDecimal cost, BigDecimal current) {
        if (cost == null || current == null || cost.signum() <= 0) {
            return null;
        }
        return current.subtract(cost).multiply(BigDecimal.valueOf(100))
                .divide(cost, 2, RoundingMode.HALF_UP);
    }

    private static String breadth(Integer up, Integer down) {
        if (up == null || down == null) {
            return plain(up) + " / " + plain(down);
        }
        int total = up + down;
        String pct = total == 0 ? "—" : Math.round(up * 100.0 / total) + "%";
        return up + " / " + down + "（红盘率 " + pct + "，分母不含平盘）";
    }

    /** 派生比率的展示：null → —，有值补百分号。 */
    private static String pct(Integer percent) {
        return percent == null ? "—" : percent + "%";
    }

    /** 计数型派生量的展示：null 和 0 得分开——0 是"今天真的全是首板"。 */
    private static String num(Integer v) {
        return v == null ? "—" : String.valueOf(v);
    }

    /** 红盘率（涨/(涨+跌)）——为了进【三】的今昨对照表， prev 侧也能算，就这里现算。 */
    private static BigDecimal rate(DailyRecord r) {
        if (r == null || r.getUpCount() == null || r.getDownCount() == null) {
            return null;
        }
        int total = r.getUpCount() + r.getDownCount();
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(Math.round(r.getUpCount() * 100.0 / total));
    }

    private static String signed(BigDecimal v, String unit) {
        return ReviewMdFormatter.signed(v, unit);
    }

    private static String plain(Object v) {
        return ReviewMdFormatter.plain(v);
    }

    private static String dash(Object v) {
        return ReviewMdFormatter.dash(v);
    }

    /** 表格里的一格：null/空 → —，竖线换成全角以免撑破表格。 */
    private static String cell(Object v) {
        if (v == null) {
            return "—";
        }
        String s = String.valueOf(v).trim().replace("|", "｜").replace("\r", "").replace("\n", " ");
        return s.isEmpty() ? "—" : s;
    }

    private static String oneLine(String v) {
        return v == null ? "" : v.replace("\r", " ").replace("\n", " ").trim();
    }

    /** 表头写实际日期：prev 是「库里上一条记录」，在没记录的日子上并不等于昨天。 */
    private static String todayHead(Model m) {
        return "今日 " + md(m.date);
    }

    private static String prevHead(Model m) {
        LocalDate d = m.prev == null ? null : m.prev.getTradeDate();
        return d == null ? "上一记录" : "上一记录 " + md(d);
    }

    /** 今昨对照一行：今日、昨日、带符号差（都为空则整行 —）。scale=0 按整数差。 */
    private static void compareRow(StringBuilder sb, String label, BigDecimal today, BigDecimal prev, int scale) {
        sb.append("| ").append(label).append(" | ").append(num(today, scale)).append(" | ")
                .append(num(prev, scale)).append(" | ").append(delta(today, prev, scale)).append(" |\n");
    }

    private static void compareRow(StringBuilder sb, String label, BigDecimal today, BigDecimal prev) {
        compareRow(sb, label, today, prev, 0);
    }

    private static String num(BigDecimal v, int scale) {
        return v == null ? "—" : v.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static String delta(BigDecimal today, BigDecimal prev, int scale) {
        if (today == null || prev == null) {
            return "—";
        }
        BigDecimal d = today.subtract(prev).setScale(scale, RoundingMode.HALF_UP);
        int cmp = d.signum();
        if (cmp == 0) {
            return "持平";
        }
        return (cmp > 0 ? "+" : "") + d.toPlainString();
    }

    private static BigDecimal decimal(DailyRecord r, Func<DailyRecord, BigDecimal> getter) {
        return r == null ? null : getter.apply(r);
    }

    private static BigDecimal count(DailyRecord r, Func<DailyRecord, Integer> getter) {
        if (r == null) {
            return null;
        }
        Integer v = getter.apply(r);
        return v == null ? null : BigDecimal.valueOf(v);
    }

    /** 避免为几个方法引用引 java.util.function 的泛型噪音。 */
    private interface Func<T, R> {
        R apply(T t);
    }

    private static IndexClose findIndex(List<IndexClose> list, String code) {
        if (list == null) {
            return null;
        }
        for (IndexClose ic : list) {
            if (code.equals(ic.getIndexCode())) {
                return ic;
            }
        }
        return null;
    }

    private static List<Prediction> filter(List<Prediction> list, boolean answer) {
        List<Prediction> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (Prediction p : list) {
            boolean isAnswer = Prediction.KIND_ANSWER.equals(p.getKind());
            if (isAnswer == answer) {
                out.add(p);
            }
        }
        return out;
    }

    private static String names(List<MarketStocksVO.Item> items) {
        if (items == null || items.isEmpty()) {
            return "—";
        }
        List<String> ns = new ArrayList<>();
        for (MarketStocksVO.Item it : items) {
            appendName(ns, it);
        }
        return String.join("、", ns);
    }

    private static String roleCn(String role) {
        if (AnchorServiceRole.CYCLE.equals(role)) {
            return "周期阵眼";
        }
        if (AnchorServiceRole.LEADER.equals(role)) {
            return "周期总龙";
        }
        return dash(role);
    }

    private static String span(Anchor a) {
        if (a.getStartDate() == null) {
            return "起点未设";
        }
        return md(a.getStartDate()) + (a.getEndDate() == null ? " 起在位" : " 至 " + md(a.getEndDate()));
    }

    // ---- 日期 ----

    private static final String[] CN_WEEK = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static String weekday(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return CN_WEEK[dow.getValue() - 1];
    }

    private static String md(LocalDate d) {
        return d.getMonthValue() + "/" + d.getDayOfMonth();
    }

    /** 次日落点：优先用服务层给的交易日，取不到才回落到"跳周末"的日历近似。 */
    private static LocalDate nextDate(Model m) {
        return m.nextDate != null ? m.nextDate : nextSession(m.date);
    }

    /** 下一日历日，跳过周六日。只是排版用的近似，不是交易日历。 */
    private static LocalDate nextSession(LocalDate d) {
        LocalDate n = d.plusDays(1);
        while (n.getDayOfWeek() == DayOfWeek.SATURDAY || n.getDayOfWeek() == DayOfWeek.SUNDAY) {
            n = n.plusDays(1);
        }
        return n;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 与 AnchorService 的 role 常量对齐；在此私有化只为渲染层不反向依赖 service。 */
    private static final class AnchorServiceRole {
        static final String CYCLE = "CYCLE";
        static final String LEADER = "LEADER";

        private AnchorServiceRole() {
        }
    }
}
