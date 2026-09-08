package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.Prediction;
import com.emotion.mapper.PredictionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 预判与对答案的读写。两种行同表：{@code PLAN} 落在计划那天，{@code ANSWER} 落在回写那天。
 *
 * <p>删除按<b>kind</b> 收窄：一份只写了 {@code 预判:} 的文件不该把那天已有的 {@code 对答案:} 行清掉。
 * 反过来，写了 {@code 预判:} 却没写满三条，那没写的路径就真的从台账上消失了——这是"重导等于重写
 * 这一类"的代价，比 upsert 留下的幽灵行诚实。
 */
@Service
public class PredictionStore {

    private static final Logger log = LoggerFactory.getLogger(PredictionStore.class);
    private static final int CHUNK = 400;

    private final PredictionMapper mapper;

    public PredictionStore(PredictionMapper mapper) {
        this.mapper = mapper;
    }

    /** rows 里出现过哪种 kind，就只重写那种 kind。空列表什么都不动，返回 0。 */
    @Transactional(rollbackFor = Exception.class)
    public int replaceForDate(Long userId, LocalDate date, List<Prediction> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        Set<String> kinds = new HashSet<>();
        for (Prediction row : rows) {
            kinds.add(row.getKind());
        }
        return replaceForDate(userId, date, rows, kinds);
    }

    /**
     * 明确点名"这次重写哪几种 kind"，kind 集合从行里推不出来时用这个。
     *
     * <p>复盘页那块可编辑台账一次把 PLAN + ANSWER 两批全交回来，语义是"这天就这几行"——
     * 两批都空 = 把这天清光，而上面那个按行推 kind 的重写做不到（空行的 kind 集合是空集，
     * 什么都不删）。导入器仍走上面那个：一份只写了 {@code 预判:} 的文件不该动那天的对答案。
     *
     * @param rows null = 这次没说预判，整块跳过（返回 -1）
     */
    @Transactional(rollbackFor = Exception.class)
    public int replaceForDate(Long userId, LocalDate date, List<Prediction> rows, Set<String> kinds) {
        if (rows == null) {
            return -1;
        }
        if (kinds == null || kinds.isEmpty()) {
            return 0;
        }
        mapper.delete(new LambdaQueryWrapper<Prediction>()
                .eq(Prediction::getUserId, userId)
                .eq(Prediction::getTradeDate, date)
                .in(Prediction::getKind, kinds));
        for (int from = 0; from < rows.size(); from += CHUNK) {
            mapper.insertBatch(rows.subList(from, Math.min(from + CHUNK, rows.size())));
        }
        log.info("{} 预判留痕写入 {} 行（kind {}）", date, rows.size(), kinds);
        return rows.size();
    }

    public List<Prediction> read(Long userId, LocalDate date) {
        return mapper.selectList(new LambdaQueryWrapper<Prediction>()
                .eq(Prediction::getUserId, userId)
                .eq(Prediction::getTradeDate, date)
                .orderByAsc(Prediction::getKind)
                .orderByAsc(Prediction::getId));
    }
}
