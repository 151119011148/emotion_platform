package com.emotion.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.market.MarketDataException;
import com.emotion.service.ScoreContextService;
import com.emotion.util.RuleMask;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.HighEcoVO;

/**
 * D5 高位生态（阵眼·抱团·监管）：{@code GET /api/d5/high?date=yyyy-MM-dd}。
 *
 * <p>响应与《D5 融合版 PRD》第十节同构：H/总分/定性 + 阵眼个体 + 抱团与资金 + 监管压制 +
 * 监管反馈 + 监管池 + 交叉信号。装配复用 {@link ScoreContextService#forDate} 同一次取数
 * （监管名单只拉一次、主线 Snapshot 只算一次），不另起取数链路。
 * 阵眼与监管名单/抱团都是全平台共享的一份：前者是人工判断、只有超级管理员能改，后者是公开事实。
 */
@RestController
@RequestMapping("/api/d5")
public class HighEcoController {

    private final ScoreContextService scoreContext;

    public HighEcoController(ScoreContextService scoreContext) {
        this.scoreContext = scoreContext;
    }

    /**
     * 抹不闸：四子项的家数、读数、分数与档位名都是结果，谁都能看。
     *
     * <p>收进超管的是能反推公式的那几格——{@code notes}（判据文案）、{@code forceRisk.reason}
     * （强制风控那句判据；{@code capScore} 留，封顶后的数字是结果）、阵眼逐只的行为/高度/封板/一致
     * 四分项（40/25/20/15 就是从这四格读出来的）、{@code coalition.adjust} 与 {@code pressure.adjust}
     * （修正说明），以及 {@code structureScore}/{@code strengthScore}（和子项总分一起摆着就能解出 60/40）。
     *
     * <p><b>{@code coalition.highThreshold} 必须留</b>：前端「只看高位」那个筛选框拿它过滤监管池，
     * 抹掉是改行为，不只是少一行字。这里的 VO 每次请求都由 {@code HighEcoMetricsService.build}
     * 新建，就地抹不会碰到 {@code forDate} 同时装进来的那棵共享配置树。
     *
     * <p>另外把 {@code signals[].label} 截到全角冒号前——冒号后那截印着
     * {@code 高位监管≥50%}、{@code 中位晋级X%<15%}、{@code 落后市场最高板≥2板} 这类硬阈值，
     * 而信号名本身就是结论，配色读的 {@code level} 也还在。
     */
    @GetMapping("/high")
    public ApiResponse<HighEcoVO> high(@RequestParam(required = false) String date) {
        HighEcoVO vo = scoreContext.highEco(parse(date));
        RuleMask.unlessSuperAdmin(vo, v -> {
            v.setNotes(new ArrayList<>());
            HighEcoVO.ForceRisk fr = v.getForceRisk();
            if (fr != null) {
                fr.setReason(null);
            }
            HighEcoVO.AnchorBlock anchor = v.getAnchor();
            if (anchor != null && anchor.getItems() != null) {
                for (HighEcoVO.AnchorItem item : anchor.getItems()) {
                    item.setActionScore(null);
                    item.setHeightScore(null);
                    item.setSealScore(null);
                    item.setConsistScore(null);
                }
            }
            HighEcoVO.CoalitionBlock coalition = v.getCoalition();
            if (coalition != null) {
                coalition.setAdjust(null);
                coalition.setStructureScore(null);
                coalition.setStrengthScore(null);
            }
            HighEcoVO.PressureBlock pressure = v.getPressure();
            if (pressure != null) {
                pressure.setAdjust(null);
            }
            // 交叉信号的 label 是「信号名：判据＋读数」，冒号后面直接印着 ≥50% / <15% / ≥2板 这类硬阈值。
            // 只留信号名——那是结论，颜色（level）也靠它；后面的读数在 ③④ 两格与阵眼卡里另有出处。
            if (v.getSignals() != null) {
                for (HighEcoVO.Signal s : v.getSignals()) {
                    s.setLabel(headOf(s.getLabel()));
                }
            }
        });
        return ApiResponse.ok(vo);
    }

    /** 取全角冒号前那截；没有冒号就原样返回（宁可多给一句话，也不要给一个空标签）。 */
    private static String headOf(String label) {
        int cut = label == null ? -1 : label.indexOf('：');
        return cut < 0 ? label : label.substring(0, cut);
    }

    private static LocalDate parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new MarketDataException("日期格式应为 yyyy-MM-dd，收到：" + raw);
        }
    }
}
