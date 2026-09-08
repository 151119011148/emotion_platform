package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.dto.PositionRequest;
import com.emotion.dto.PredictionRequest;
import com.emotion.entity.Position;
import com.emotion.entity.Prediction;
import com.emotion.entity.Stock;
import com.emotion.mapper.StockMapper;
import com.emotion.util.ReviewImportParser;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 复盘页那两张内联台账的写入口：持仓、预判与对答案。
 *
 * <p><b>和 md 导入同一个原语</b>——落库仍然走 {@code PositionStore} / {@code PredictionStore} 的
 * {@code replaceForDate}，这里只多做两件事：校验、以及把"这天就这几行"讲清楚。
 * 开第二个写入口真正的风险不是有两个入口，是两个入口语义不同；这里语义完全相同。
 *
 * <p>校验一律<b>一次抛全</b>：五行的台账里两处坏行，页面要一次看到两行怎么说，
 * 而不是改一个、存一次、再看到下一个。
 */
@Service
public class ReviewLedgerService {

    private static final Pattern CODE = Pattern.compile("\\d{6}");
    /** 库里价格列是 DECIMAL(10,2)，多出来的小数位MySQL也会抹掉，那就由服务端先抹。 */
    private static final int MONEY_SCALE = 2;
    /** 复盘页的表两种 kind 一起编辑、一起存，所以两批一起重写——空表也要重写，才清得干净。 */
    private static final Set<String> BOTH_KINDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(Prediction.KIND_PLAN, Prediction.KIND_ANSWER)));

    private final PositionStore positionStore;
    private final PredictionStore predictionStore;
    private final StockMapper stockMapper;

    public ReviewLedgerService(PositionStore positionStore,
                               PredictionStore predictionStore,
                               StockMapper stockMapper) {
        this.positionStore = positionStore;
        this.predictionStore = predictionStore;
        this.stockMapper = stockMapper;
    }

    /**
     * 整日替换当天持仓。空表 = "这天清仓了、一行都不剩"——这条是 md 路径走不通的
     * （解析器拒收空的 {@code 持仓:} 键，见 {@code ReviewImportParser:330} 那段），只有这个入口做得到。
     *
     * @return 落库行数
     */
    public int savePositions(Long userId, LocalDate date, List<PositionRequest> rows) {
        List<PositionRequest> incoming = rows == null ? new ArrayList<PositionRequest>() : rows;
        List<String> errors = new ArrayList<>();
        Map<String, String> names = resolveNames(incoming, errors);

        List<Position> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < incoming.size(); i++) {
            PositionRequest r = incoming.get(i);
            if (r == null || blankPosition(r)) {
                continue;
            }
            String at = "第 " + (i + 1) + " 行 ";
            String code = trim(r.getStockCode());
            if (!CODE.matcher(code).matches()) {
                errors.add(at + "代码要是 6 位数字，收到：" + code);
                continue;
            }
            if (!names.containsKey(code)) {
                errors.add(at + "代码 " + code + " 不在 A股代码表里，请从搜索结果里选一只");
                continue;
            }
            if (!seen.add(code)) {
                errors.add(at + "代码 " + code + " 这一批里已经列过一次");
                continue;
            }
            Position p = new Position();
            p.setUserId(userId);
            p.setTradeDate(date);
            p.setStockCode(code);
            p.setStockName(names.get(code));
            p.setCostPrice(money(errors, at, "成本", r.getCostPrice()));
            p.setCurrentPrice(money(errors, at, "现价", r.getCurrentPrice()));
            p.setFloatPct(r.getFloatPct() != null
                    ? round(r.getFloatPct()) : floatPctOf(p.getCostPrice(), p.getCurrentPrice()));
            p.setAction(emptyToNull(r.getAction()));
            p.setPlannedAction(emptyToNull(r.getPlannedAction()));
            String discipline = trim(r.getDiscipline());
            if (!discipline.isEmpty() && !ReviewImportParser.DISCIPLINE.contains(discipline)) {
                errors.add(at + "纪律只能是 遵守/违约/待执行，收到：" + discipline);
                continue;
            }
            p.setDiscipline(discipline.isEmpty() ? null : discipline);
            out.add(p);
        }
        throwIfAny(errors);
        return positionStore.replaceForDate(userId, date, out);
    }

    /**
     * 整日替换当天的预判与对答案，<b>两种 kind 一起换</b>（见 {@link #BOTH_KINDS}）。
     * 所以前端必须把两批一起发回来——只发 PLAN 会把 ANSWER 一起清掉，这是这个端点唯一的口径。
     *
     * @return 落库行数
     */
    public int savePredictions(Long userId, LocalDate date, List<PredictionRequest> rows) {
        List<PredictionRequest> incoming = rows == null ? new ArrayList<PredictionRequest>() : rows;
        List<String> errors = new ArrayList<>();
        List<Prediction> out = new ArrayList<>();
        Map<String, Set<String>> seenByKind = new HashMap<>();
        for (int i = 0; i < incoming.size(); i++) {
            PredictionRequest r = incoming.get(i);
            if (r == null || blankPrediction(r)) {
                continue;
            }
            String at = "第 " + (i + 1) + " 行 ";
            String kind = trim(r.getKind());
            boolean plan = Prediction.KIND_PLAN.equals(kind);
            boolean answer = Prediction.KIND_ANSWER.equals(kind);
            if (!plan && !answer) {
                errors.add(at + "kind 只能是 PLAN/ANSWER，收到：" + kind);
                continue;
            }
            String name = trim(r.getName());
            if (name.isEmpty()) {
                errors.add(at + "路径名必填：次日对答案靠这个名字回填，没名就接不上");
                continue;
            }
            if (!seenByKind.computeIfAbsent(kind, k -> new HashSet<>()).add(name)) {
                errors.add(at + "路径名「" + name + "」这一批里已经有一次，同名两条对答案会撞车");
                continue;
            }
            Prediction p = new Prediction();
            p.setUserId(userId);
            p.setTradeDate(date);
            p.setKind(kind);
            p.setName(name);
            if (plan) {
                if (r.getProb() == null) {
                    errors.add(at + "概率必填：三路径的概率加起来要能看出你留没留意外");
                    continue;
                }
                if (r.getProb() < 0 || r.getProb() > 100) {
                    errors.add(at + "概率是 0-100 的百分数，收到：" + r.getProb());
                    continue;
                }
                p.setProb(r.getProb());
                p.setConditionText(emptyToNull(r.getConditionText()));
            } else {
                String result = trim(r.getResult());
                if (!ReviewImportParser.ANSWER_RESULT.contains(result)) {
                    errors.add(at + "结果只能是 命中/落空/部分/违约，收到：" + result);
                    continue;
                }
                p.setResult(result);
                p.setResultNote(emptyToNull(r.getResultNote()));
            }
            out.add(p);
        }
        throwIfAny(errors);
        return predictionStore.replaceForDate(userId, date, out, BOTH_KINDS);
    }

    /** 代码只查一次，名字以 t_stock 为准——和导入器同一个规矩，两页不该给出两个名。 */
    private Map<String, String> resolveNames(List<PositionRequest> rows, List<String> errors) {
        Set<String> codes = new LinkedHashSet<>();
        for (PositionRequest r : rows) {
            if (r != null && CODE.matcher(trim(r.getStockCode())).matches()) {
                codes.add(trim(r.getStockCode()));
            }
        }
        Map<String, String> names = new HashMap<>();
        if (codes.isEmpty()) {
            return names;
        }
        for (Stock s : stockMapper.selectList(new LambdaQueryWrapper<Stock>()
                .in(Stock::getCode, codes))) {
            names.put(s.getCode(), s.getName());
        }
        return names;
    }

    private static BigDecimal money(List<String> errors, String at, String label, BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            errors.add(at + label + "不能是负数：" + value.toPlainString());
            return null;
        }
        return round(value);
    }

    /** 手记值优先，只有留空才由成本/现价反推——你记的可能是含费后的数。 */
    static BigDecimal floatPctOf(BigDecimal cost, BigDecimal current) {
        if (cost == null || current == null || cost.signum() <= 0) {
            return null;
        }
        return current.subtract(cost)
                .multiply(BigDecimal.valueOf(100))
                .divide(cost, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static void throwIfAny(List<String> errors) {
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", errors));
        }
    }

    private static boolean blankPosition(PositionRequest r) {
        return trim(r.getStockCode()).isEmpty() && trim(r.getAction()).isEmpty()
                && trim(r.getPlannedAction()).isEmpty() && trim(r.getDiscipline()).isEmpty()
                && r.getCostPrice() == null && r.getCurrentPrice() == null && r.getFloatPct() == null;
    }

    private static boolean blankPrediction(PredictionRequest r) {
        return trim(r.getKind()).isEmpty() && trim(r.getName()).isEmpty() && r.getProb() == null
                && trim(r.getConditionText()).isEmpty() && trim(r.getResult()).isEmpty()
                && trim(r.getResultNote()).isEmpty();
    }

    private static String trim(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String emptyToNull(String raw) {
        String v = trim(raw);
        return v.isEmpty() ? null : v;
    }
}
