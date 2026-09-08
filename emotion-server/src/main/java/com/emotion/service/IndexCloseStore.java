package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.IndexClose;
import com.emotion.mapper.IndexCloseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 五大指数收盘的读写。公开数据、不绑用户，和 {@code t_market_stock} 同一族。
 *
 * <p>两个写入口、一个原语：复盘 md 导入走 {@link #replaceForDate}（那天说了哪几只就是哪几只，
 * 整日重建），{@code /api/market/snapshot} 的日 K 自动取数走 {@link #upsertForDate}（只覆盖它真取到的那几只）。
 * 读侧一个字都不用管谁写的。
 */
@Service
public class IndexCloseStore {

    private static final Logger log = LoggerFactory.getLogger(IndexCloseStore.class);
    private static final int CHUNK = 400;

    private final IndexCloseMapper mapper;

    public IndexCloseStore(IndexCloseMapper mapper) {
        this.mapper = mapper;
    }

    /** null = 这次没说指数，跳过（-1）；空表 = 明确要清掉那天的五行。 */
    @Transactional(rollbackFor = Exception.class)
    public int replaceForDate(LocalDate date, List<IndexClose> rows) {
        if (rows == null) {
            return -1;
        }
        mapper.delete(new LambdaQueryWrapper<IndexClose>().eq(IndexClose::getTradeDate, date));
        return insert(date, rows);
    }

    /**
     * 自动取数用的<b>按代码</b>覆盖：只删这批里出现过的 index_code，其余行原样留着。
     *
     * <p>这里不能用 {@link #replaceForDate}：日 K 一次未必五只都给（北证50 最容易掉），
     * 整日删除会把 md 导进来的那几只一起抹掉，而"抹掉"的依据只是这次腾讯没回。
     */
    @Transactional(rollbackFor = Exception.class)
    public int upsertForDate(LocalDate date, List<IndexClose> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<String> codes = distinctCodes(rows);
        if (codes.isEmpty()) {
            return 0;
        }
        mapper.delete(new LambdaQueryWrapper<IndexClose>()
                .eq(IndexClose::getTradeDate, date)
                .in(IndexClose::getIndexCode, codes));
        return insert(date, rows);
    }

    /** 这批要覆盖哪些代码。null 代码的行不算，重复只留一次。 */
    static List<String> distinctCodes(List<IndexClose> rows) {
        List<String> codes = new ArrayList<>();
        for (IndexClose row : rows) {
            if (row.getIndexCode() != null && !codes.contains(row.getIndexCode())) {
                codes.add(row.getIndexCode());
            }
        }
        return codes;
    }

    private int insert(LocalDate date, List<IndexClose> rows) {
        for (int from = 0; from < rows.size(); from += CHUNK) {
            mapper.insertBatch(rows.subList(from, Math.min(from + CHUNK, rows.size())));
        }
        log.info("{} 指数收盘写入 {} 行", date, rows.size());
        return rows.size();
    }

    public List<IndexClose> read(LocalDate date) {
        return mapper.selectList(new LambdaQueryWrapper<IndexClose>()
                .eq(IndexClose::getTradeDate, date)
                .orderByAsc(IndexClose::getId));
    }
}
