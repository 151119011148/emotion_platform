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

    /** 过滤掉的总市值/风格/指数类伪概念：这类 BK 会把成分炒成"全市场"而失去题材区分度。 */
    private static final String[] PSEUDO_CONCEPTS = {
            "昨日涨停", "昨日跌停", "昨日触板", "昨日连板", "昨日炸板",
            "前一日涨停", "连续涨停", "昨日非涨停", "涨停股池", "ST", "低价股", "中价股",
            "次新预增", "预增", "扭亏", "业绩预增", "高质押", "股权激励"
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