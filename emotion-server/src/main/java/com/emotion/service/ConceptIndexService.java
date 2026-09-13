package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.StockConcept;
import com.emotion.market.ConceptBoard;
import com.emotion.market.ConceptMembers;
import com.emotion.market.EastmoneyClient;
import com.emotion.mapper.StockConceptMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 全市场「股票-概念」索引的构建与维护（D2 题材聚合的地基）。
 *
 * <p>东财涨停池只给行业(hybk)不给概念，题材热度必须先建索引：遍历东财全部概念板块
 * （约 504 个），逐个拉成分股，落成 code→concept 的全局映射。概念成分变化慢，索引低频
 * 全量重建即可——这是除每日 fetch 之外的独立动作，不挂在 T 套路里（避免每次拉取都扫全市场）。
 */
@Service
public class ConceptIndexService {

    private static final Logger log = LoggerFactory.getLogger(ConceptIndexService.class);

    /**
     * 过滤掉的伪概念清单。这类 BK 会让涨停池被"通道/流量/权重/持仓"类标签污染而失去题材区分度，
     * 数据库级剔除——重建索引时根本不写进 {@code t_stock_concept}，题材表/强度榜都不再出现。
     * 注意 {@link #isPseudo} 用子串匹配，新增项请避免误伤真实产业题材（如"央企改革"不打进去）。
     */
    private static final String[] PSEUDO_CONCEPTS = {
            // 交易机制 / 通道类：融资融券、沪深港通、转融券等资金通道，非题材
            "融资融券", "沪股通", "深股通", "港股通", "转融券",
            // 平台流量 / 热度类：东财热股、人气榜等流量标签
            "东方财富热股", "热股", "人气榜", "热门",
            // 指数成分 / 基准权重类：宽基与风格指数成分股，非题材
            "MSCI", "标准普尔", "富时罗素", "罗素", "深证成指", "深证100", "深证300",
            "沪深300", "中证500", "中证1000", "上证180", "上证380", "上证50", "科创50", "创业板指",
            // 机构持仓 / 资金风格类
            "机构重仓", "基金重仓", "QFII重仓", "社保重仓", "保险重仓", "券商重仓", "信托重仓",
            "证金持股", "汇金持股", "国家队", "养老金持股", "北向资金",
            // 涨停池 / 连板等聚合标签（把成分炒成"今日涨停全市场"）
            "昨日涨停", "昨日跌停", "昨日触板", "昨日连板", "昨日炸板",
            "前一日涨停", "连续涨停", "昨日非涨停", "涨停股池", "昨涨停",
            // 市值 / 风格类
            "低价股", "中价股", "高价股", "微盘股", "小盘股", "中盘股", "大盘股", "超大盘",
            "趋势股", "破发股", "破净股",
            // 股东 / 业绩 / 制度类标签
            "预盈预增", "预增", "扭亏", "业绩预增", "高质押", "股权激励", "ST",
            "次新股", "送转",
            // 兜底聚合 / 财报事件标签
            "题材股", "中报首亏", "季报首亏", "年报首亏", "业绩预降", "业绩预减"
    };

    private final EastmoneyClient eastmoney;
    private final StockConceptMapper mapper;

    public ConceptIndexService(EastmoneyClient eastmoney, StockConceptMapper mapper) {
        this.eastmoney = eastmoney;
        this.mapper = mapper;
    }

    /**
     * 全量重建索引：先清空再整盘重扫全部概念板块成分。
     *
     * @return 写回的「股票-概念」行数；-1 表示上游一块概念都没拿到（不重建，保留旧索引）
     */
    @Transactional(rollbackFor = Exception.class)
    public long rebuild() {
        List<ConceptBoard> boards = eastmoney.listConcepts();
        if (boards == null || boards.isEmpty()) {
            log.warn("东财概念板块清单为空，索引不重建");
            return -1L;
        }
        List<StockConcept> rows = new ArrayList<>();
        int boardCount = 0;
        for (ConceptBoard board : boards) {
            List<StockConcept> members = membersOf(board);
            if (members.isEmpty()) {
                continue;
            }
            rows.addAll(members);
            boardCount++;
        }
        if (rows.isEmpty()) {
            log.warn("概念成分股全为空，索引不重建");
            return -1L;
        }
        mapper.delete(new LambdaQueryWrapper<>());
        for (int from = 0; from < rows.size(); from += 1000) {
            mapper.insertBatch(rows.subList(from, Math.min(from + 1000, rows.size())));
        }
        log.info("题材索引重建完成：{} 个概念 {} 行", boardCount, rows.size());
        return rows.size();
    }

    private List<StockConcept> membersOf(ConceptBoard board) {
        List<StockConcept> out = new ArrayList<>();
        if (isPseudo(board.getName())) {
            return out;
        }
        ConceptMembers members;
        try {
            members = eastmoney.conceptMembers(board.getCode());
        } catch (RuntimeException e) {
            log.warn("概念 {} 成分拉取失败，跳过: {}", board.getCode(), e.getMessage());
            return out;
        }
        if (!members.isOk() || members.getRows().isEmpty()) {
            return out;
        }
        for (com.emotion.market.StockRow row : members.getRows()) {
            if (row.getCode() == null || !row.getCode().matches("\\d{6}")) {
                continue;
            }
            StockConcept c = new StockConcept();
            c.setCode(row.getCode());
            c.setConceptCode(board.getCode());
            c.setConcept(board.getName());
            c.setName(row.getName() == null ? "" : row.getName());
            out.add(c);
        }
        return out;
    }

    private static boolean isPseudo(String name) {
        if (name == null) {
            return true;
        }
        // 时序/聚合类："昨日X/前日X/最近X/连续X" 一律是回看标签（昨日高振幅、最近多板…），非题材
        if (name.startsWith("昨日") || name.startsWith("前日") || name.startsWith("最近")
                || name.startsWith("连续") || name.startsWith("上周") || name.startsWith("本周")) {
            return true;
        }
        for (String p : PSEUDO_CONCEPTS) {
            if (name.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /** 当前索引规模（行数）。 */
    public long count() {
        return mapper.selectCount(new LambdaQueryWrapper<>());
    }
}