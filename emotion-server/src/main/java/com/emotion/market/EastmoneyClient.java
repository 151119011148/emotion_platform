package com.emotion.market;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 东财涨跌停/炸板池。带 date 参数可以回溯历史日期——这是它相对腾讯快照的关键优势。
 *
 * 两个实测出来的坑：
 * <ul>
 *   <li>每个池子只认自己的 sort。跌停池用 fbt:asc 会回 rc=0、tc=9、pool=[]，
 *       看着像"9 家跌停但一只都取不到"，换成 fund:asc 才给明细；</li>
 *   <li>非交易日不报错，而是回落到最近一个交易日的数据（周六回了周五的 39 家）。
 *       日期只能靠腾讯快照日（{@code date > snapshotDate} 即无数据）和"三个池同时为空"来判。</li>
 *   <li>公告接口只认 {@code ann_type=A}：不带它、或换成 ALL / f_node / s_node 都是 0 条，
 *       而 {@code searchkey}、{@code nodeid}、{@code column_code} 三个过滤参数实测全被静默忽略
 *       （返回条数等于不过滤的全量）。所以异动类别只能在客户端按 {@code columns[].column_code} 判。</li>
 * </ul>
 *
 * 历史成交额这里取不到：push2his / push2 / 92.push2his 三个日K主机在本机都是空响应。
 */
@Component
public class EastmoneyClient {

    private static final Logger log = LoggerFactory.getLogger(EastmoneyClient.class);
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    /** 上游 pz 硬上限 100：给 6000 也只回 100，且不报错。 */
    private static final int LIST_PAGE_SIZE = 100;
    private static final int MAX_LIST_PAGES = 80;
    /** 沪深京四个板块 + 北交所。北交所那条会捎回定向可转债，所以还要过白名单。 */
    private static final String LIST_FS = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048";
    /** 00 深主板 / 30 创业板 / 60 沪主板 / 68 科创板 / 43、83、87、88、92 北交所。 */
    private static final Pattern STOCK_PREFIX = Pattern.compile("^(00|30|60|68|43|83|87|88|92)\\d{4}$");

    /** 公告接口一页 100 条封顶（给 500 也只回 100，同 clist 的毛病）。 */
    private static final int ANN_PAGE_SIZE = 100;
    private static final int MAX_ANN_PAGES = 10;
    /** 股票交易异常波动：标题含"严重"才是严重异常波动。 */
    private static final String COL_ABNORMAL = "001002004007";
    /**
     * 交易所监管关注/工作函一族（010002009001 予以监管警示的决定、010002009005 监管工作函）。
     * 注意别和 001002009「董事会决议公告」混了——差在第一个字符，前缀判据恰好不会。
     */
    private static final String COL_EXCHANGE_WATCH = "010002009";

    private final HttpFetcher fetcher;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String poolBase;
    private final String listBase;
    private final String annBase;
    private final String ut;
    private final int poolSize;

    public EastmoneyClient(HttpFetcher fetcher,
                           @Value("${market.eastmoney.pool-base:http://push2ex.eastmoney.com}") String poolBase,
                           // 名单接口只有 delay 子域在本机通：push2 / 1.push2 / 80.push2 全是空响应
                           @Value("${market.eastmoney.list-base:http://push2delay.eastmoney.com}") String listBase,
                           @Value("${market.eastmoney.ann-base:https://np-anotice-stock.eastmoney.com}") String annBase,
                           @Value("${market.eastmoney.ut:7eea3edcaed734bea9cbfc24409ed989}") String ut,
                           @Value("${market.eastmoney.pool-size:200}") int poolSize) {
        this.fetcher = fetcher;
        this.poolBase = poolBase;
        this.listBase = listBase;
        this.annBase = annBase;
        this.ut = ut;
        this.poolSize = poolSize;
    }

    public PoolResult limitUp(LocalDate date) {
        return pool("/getTopicZTPool", "fbt%3Aasc", date);
    }

    public PoolResult limitDown(LocalDate date) {
        return pool("/getTopicDTPool", "fund%3Aasc", date);
    }

    public PoolResult broken(LocalDate date) {
        return pool("/getTopicZBPool", "fbt%3Aasc", date);
    }

    // ---------- A股代码总表 ----------

    /**
     * 全量 A股代码名称。
     *
     * pz 服务端硬上限 100（给 6000 也只回 100 且不报错），只能靠 pn 翻页；
     * 页数护栏是为了上游哪天改成"翻不完"时这里至少能返回一个标了 truncated 的结果，
     * 而不是无限翻下去。
     */
    public StockListResult allStocks() {
        StockListResult merged = new StockListResult();
        merged.setOk(true);
        for (int pn = 1; pn <= MAX_LIST_PAGES; pn++) {
            String url = listBase + "/api/qt/clist/get"
                    + "?ut=" + ut
                    + "&pn=" + pn
                    + "&pz=" + LIST_PAGE_SIZE
                    + "&po=1&np=1&fltt=2&invt=2&fid=f12"
                    // fs 里的 + 不能转义也不能编码：它必须是字面量 +，http 侧才认这套板块组合
                    + "&fs=" + LIST_FS
                    + "&fields=f12,f13,f14";
            StockListResult page = parseStockPage(fetcher.getText(url));
            if (!page.isOk()) {
                return StockListResult.failed("第 " + pn + " 页失败：" + page.getReason());
            }
            merged.setTotal(page.getTotal());
            merged.getRows().addAll(page.getRows());
            merged.getDropped().addAll(page.getDropped());
            // 用上游 total 收口，不用"本页不足 100"：后者会因为一行脏数据就提前退出，把名单截断
            int seen = merged.getRows().size() + merged.getDropped().size();
            if (seen >= page.getTotal() || (page.getRows().isEmpty() && page.getDropped().isEmpty())) {
                return merged;
            }
            if (pn == MAX_LIST_PAGES) {
                merged.setTruncated(true);
                merged.setReason("撞上页数护栏 " + MAX_LIST_PAGES);
            }
        }
        return merged;
    }

    /** 包级可见，便于用 fixture 离线单测。 */
    StockListResult parseStockPage(String body) {
        if (body == null) {
            return StockListResult.failed("行情源无响应");
        }
        JsonNode data;
        try {
            JsonNode root = mapper.readTree(body);
            if (root.path("rc").asInt(-1) != 0) {
                return StockListResult.failed("行情源返回码 " + root.path("rc").asInt(-1));
            }
            data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return StockListResult.failed("行情源未给出名单");
            }
        } catch (IOException e) {
            log.warn("名单响应解析失败: {}", e.getClass().getSimpleName());
            return StockListResult.failed("行情源响应无法解析");
        }
        JsonNode diff = data.path("diff");
        if (!diff.isArray() && !diff.isObject()) {
            return StockListResult.failed("行情源名单格式异常");
        }

        StockListResult result = new StockListResult();
        result.setOk(true);
        result.setTotal(data.path("total").asInt(0));
        for (JsonNode node : diff) {
            String code = text(node, "f12");
            String name = text(node, "f14");
            if (code == null || name == null) {
                continue;
            }
            StockRow row = new StockRow();
            row.setCode(code);
            row.setName(name);
            row.setMarket(node.path("f13").isNumber() ? node.path("f13").asInt() : null);
            if (!STOCK_PREFIX.matcher(code).matches()) {
                // 白名单挡的是 81xxxx 定向可转债——北交所那条 fs 条件会把它们捎回来
                result.getDropped().add(code + " " + name);
                continue;
            }
            result.getRows().add(row);
        }
        if (!result.getDropped().isEmpty()) {
            log.info("名单页过滤掉 {} 行非股票: {}", result.getDropped().size(), result.getDropped());
        }
        return result;
    }

    // ---------- 异动监管公告 ----------

    /**
     * 一只票在一个窗口内的异动监管公告。一次请求就能装下（实测 600664 跨两个月 19 条），
     * 翻页只是给"哪天公告特别多的票"留的余量。
     *
     * 逐只查而不是全市场扫：服务端过滤参数一律不可信（见类注释），全市场一天 713 条要 8 个请求，
     * 而跟踪集合一共才一百来只——一只一个请求还能顺带把窗口钉死在自己关心的区间上。
     */
    public SurveillanceResult surveillance(String code, LocalDate begin, LocalDate end) {
        if (code == null || code.trim().isEmpty() || begin == null || end == null) {
            return SurveillanceResult.failed(code, "公告查询需要 code、begin、end 三个参数");
        }
        String trimmed = code.trim();
        SurveillanceResult merged = SurveillanceResult.of(trimmed);
        for (int pn = 1; pn <= MAX_ANN_PAGES; pn++) {
            String url = annBase + "/api/security/ann"
                    + "?sr=-1&page_size=" + ANN_PAGE_SIZE + "&page_index=" + pn
                    // ann_type=A 缺了就是一条都不给；换 ALL / f_node / s_node 同样是 0
                    + "&ann_type=A&client_type=web"
                    + "&stock_list=" + trimmed
                    + "&begin_time=" + begin + "%2000:00:00"
                    + "&end_time=" + end + "%2023:59:59";
            SurveillanceResult page = parseSurveillance(fetcher.getText(url), trimmed);
            if (!page.isOk()) {
                return SurveillanceResult.failed(trimmed, "第 " + pn + " 页失败：" + page.getReason());
            }
            merged.setTotalHits(page.getTotalHits());
            merged.setPages(pn);
            merged.setRows(merged.getRows() + page.getRows());
            merged.setUnmatchedSignals(merged.getUnmatchedSignals() + page.getUnmatchedSignals());
            merged.getNotices().addAll(page.getNotices());
            if (page.getRows() == 0 || merged.getRows() >= page.getTotalHits()) {
                return merged;
            }
            if (pn == MAX_ANN_PAGES) {
                merged.setOk(false);
                merged.setReason("撞上页数护栏 " + MAX_ANN_PAGES + "，该窗口未查完");
                log.warn("公告翻页被截断: code={} total_hits={}", trimmed, merged.getTotalHits());
            }
        }
        return merged;
    }

    /** 包级可见，便于用 fixture 离线单测。queriedCode 是回退用代码：上游偶尔不给 codes[]。 */
    SurveillanceResult parseSurveillance(String body, String queriedCode) {
        if (body == null) {
            return SurveillanceResult.failed(queriedCode, "公告源无响应");
        }
        JsonNode data;
        try {
            JsonNode root = mapper.readTree(body);
            data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return SurveillanceResult.failed(queriedCode, "公告源未给出数据");
            }
        } catch (IOException e) {
            log.warn("公告响应解析失败: {}", e.getClass().getSimpleName());
            return SurveillanceResult.failed(queriedCode, "公告源响应无法解析");
        }
        JsonNode list = data.path("list");
        if (!list.isArray()) {
            return SurveillanceResult.failed(queriedCode, "公告源名单格式异常");
        }

        SurveillanceResult result = SurveillanceResult.of(queriedCode);
        result.setTotalHits(data.path("total_hits").asInt(0));
        for (JsonNode node : list) {
            result.setRows(result.getRows() + 1);
            String title = text(node, "title");
            if (title == null) {
                title = text(node, "title_ch");
            }
            LocalDate noticeDate = parseDate(text(node, "notice_date"));
            String artCode = text(node, "art_code");
            if (title == null || noticeDate == null || artCode == null) {
                continue;
            }
            SurveillanceKind kind = null;
            String columnCode = null;
            for (JsonNode col : node.path("columns")) {
                String code = text(col, "column_code");
                if (code == null) {
                    continue;
                }
                if (COL_ABNORMAL.equals(code)) {
                    kind = title.contains("严重") ? SurveillanceKind.SEVERE : SurveillanceKind.ZD;
                } else if (kind == null && code.startsWith(COL_EXCHANGE_WATCH)) {
                    kind = SurveillanceKind.EXCH;
                }
                if (kind != null) {
                    columnCode = code;
                    break;
                }
            }
            if (kind == null) {
                // 风险提示（001002004012 / 001002001004006006）、询证函回复、董事会决议都不算触发。
                // 但标题里有信号词却没有类目码，多半是上游改了结构——留个数，别让它静默清零。
                // 询证函回复是控股方代答，本来就不该入表，不能算进这个哨兵。
                boolean signalWord = title.contains("异常波动") || title.contains("监管警示")
                        || title.contains("监管工作函") || title.contains("纪律处分");
                boolean shareholderReply = title.contains("询证函") || title.contains("回复");
                if (signalWord && !shareholderReply) {
                    result.setUnmatchedSignals(result.getUnmatchedSignals() + 1);
                }
                continue;
            }
            SurveillanceNotice notice = new SurveillanceNotice();
            notice.setCode(stockCode(node, queriedCode));
            notice.setName(shortName(node, queriedCode));
            notice.setAnnDate(noticeDate);
            notice.setKind(kind);
            notice.setTitle(title);
            notice.setColumnCode(columnCode);
            notice.setArtCode(artCode);
            result.getNotices().add(notice);
        }
        return result;
    }

    /** 一只票的公告可能挂在多条 code 上（母公司回复子公司那种），取与查询代码一致的那条。 */
    private static String stockCode(JsonNode node, String queriedCode) {
        for (JsonNode item : node.path("codes")) {
            String code = text(item, "stock_code");
            if (code != null && code.equals(queriedCode)) {
                return code;
            }
        }
        return queriedCode;
    }

    private static String shortName(JsonNode node, String queriedCode) {
        JsonNode codes = node.path("codes");
        if (!codes.isArray() || codes.size() == 0) {
            return null;
        }
        String name = text(codes.get(0), "short_name");
        return name == null ? queriedCode : name;
    }

    /** 上游时间戳形如 "2026-08-21 00:00:00"，只要前 10 位；解不出来就当没有。 */
    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private PoolResult pool(String path, String sort, LocalDate date) {
        String url = poolBase + path
                + "?ut=" + ut
                + "&dpt=wz.ztzt"
                + "&Pageindex=0"
                + "&pagesize=" + poolSize
                // sort 不能省：缺了它上游回 HTTP 200 但 data=null，和"当天没有涨停股"完全无法区分
                + "&sort=" + sort
                + "&date=" + date.format(BASIC);
        return parsePool(fetcher.getText(url));
    }

    /** 包级可见，便于用 fixture 离线单测。 */
    PoolResult parsePool(String body) {
        if (body == null) {
            return PoolResult.failed("行情源无响应");
        }
        JsonNode data;
        try {
            JsonNode root = mapper.readTree(body);
            if (root.path("rc").asInt(-1) != 0) {
                return PoolResult.failed("行情源返回码 " + root.path("rc").asInt(-1));
            }
            data = root.path("data");
        } catch (IOException e) {
            log.warn("池子响应解析失败: {}", e.getClass().getSimpleName());
            return PoolResult.failed("行情源响应无法解析");
        }
        if (data.isMissingNode() || data.isNull()) {
            return PoolResult.failed("行情源未给出该日期数据");
        }

        PoolResult result = new PoolResult();
        result.setOk(true);
        result.setTc(data.path("tc").asInt(0));
        List<PoolRow> rows = new ArrayList<>();
        for (JsonNode node : data.path("pool")) {
            rows.add(toRow(node));
        }
        result.setRows(rows);
        if (result.getTc() > rows.size()) {
            result.setTruncated(true);
            log.warn("池子被 pagesize 截断: tc={} 实际取回={}", result.getTc(), rows.size());
        }
        return result;
    }

    private static PoolRow toRow(JsonNode node) {
        PoolRow row = new PoolRow();
        row.setCode(text(node, "c"));
        row.setName(text(node, "n"));
        row.setMarket(node.path("m").isNumber() ? node.path("m").asInt() : null);
        row.setLbc(node.path("lbc").isNumber() ? node.path("lbc").asInt() : null);
        row.setZbc(node.path("zbc").isNumber() ? node.path("zbc").asInt() : 0);
        row.setZdp(node.path("zdp").isNumber() ? BigDecimal.valueOf(node.path("zdp").asDouble()) : null);
        row.setIndustry(text(node, "hybk"));
        // p / ztp 上游按 ×1000 传输；回撤是比值，换算会约掉，故不除 1000
        row.setPrice(decimal(node, "p"));
        row.setLimitPrice(decimal(node, "ztp"));
        return row;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return node.path(field).isNumber() ? BigDecimal.valueOf(node.path(field).asDouble()) : null;
    }
}
