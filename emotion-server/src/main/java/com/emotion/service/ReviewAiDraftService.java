package com.emotion.service;

import com.emotion.ai.LlmClient;
import com.emotion.ai.LlmException;
import com.emotion.ai.NumberGuard;
import com.emotion.ai.ReviewAiPrompt;
import com.emotion.ai.TokenFiller;
import com.emotion.util.ReviewDocFormatter;
import com.emotion.vo.ReviewAiDraftVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 复盘文档的 <b>AI 草稿</b>：把当天的系统读数交给模型，只让它组织措辞，产出【一】那节
 * 「核心定性」的一段草稿。
 *
 * <p>数怎么保证不是编的，三道：
 * <ol>
 *   <li>提示词里<b>没有数值</b>，只有令牌名（{@link ReviewAiPrompt}）——模型没有可抄的数；</li>
 *   <li>交回来的原文先过 {@link NumberGuard}：写出了一个阿拉伯数字就是违规，因为它手上没有数，
 *       那个数只能是编的；违规回灌一次重问，还违规就整条拦掉，不做「挑能看的留下」；</li>
 *   <li>{@link TokenFiller} 只认系统给过值的令牌，写出没给过的也拦。</li>
 * </ol>
 * 拦下时给的是片段而不是只说「有数字」，否则他得自己去找是哪句。
 *
 * <p><b>这一步一个字节都不写库</b>：草稿只在返回值里，不并进 {@code review_md}，也不进留痕。
 * 正因为这样它走 {@code POST}（一次外呼、有副作用感的动作），而不是塞进
 * {@code GET /api/review/export}——那个接口按同一条件必须逐字节相同，把模型挂上去就破了。
 */
@Service
public class ReviewAiDraftService {

    private static final Logger log = LoggerFactory.getLogger(ReviewAiDraftService.class);
    /** 只剩日期也算有读数：温度、量能、涨跌停全缺时发给模型只会得到一段空话，不如不生成。 */
    private static final int MIN_FACTS = 4;

    private final ReviewExportService reviewExportService;
    private final LlmClient llm;

    public ReviewAiDraftService(ReviewExportService reviewExportService, LlmClient llm) {
        this.reviewExportService = reviewExportService;
        this.llm = llm;
    }

    public ReviewAiDraftVO draft(Long ledgerUserId, LocalDate date) {
        ReviewAiDraftVO vo = new ReviewAiDraftVO();
        vo.setDate(date);
        vo.setModel(llm.modelName());

        Map<String, String> facts =
                ReviewDocFormatter.aiFacts(reviewExportService.docModel(ledgerUserId, date));
        vo.setFacts(facts);
        if (facts.size() < MIN_FACTS) {
            vo.setMessage(date + " 系统读数太少（只凑出 " + facts.size() + " 项），"
                    + "没什么可组织的，这次没生成草稿。先在复盘页拉一次行情快照。");
            return vo;
        }

        ReviewAiPrompt.Prompt prompt = ReviewAiPrompt.build(facts);
        String raw = call(prompt, vo);
        if (raw == null) {
            return vo;
        }
        String bad = violationNote(raw, facts);
        if (bad != null) {
            log.info("草稿第一轮被拦（{}），重问一次", bad);
            vo.getWarnings().add("第一轮产出违规（" + bad + "），已回灌纠正重问一次。");
            raw = call(ReviewAiPrompt.retry(prompt, correction(violations(raw, facts))), vo);
            if (raw == null) {
                return vo;
            }
            bad = violationNote(raw, facts);
            if (bad != null) {
                vo.setBlocked(true);
                vo.setMessage("草稿被拦：" + bad + "。数字一律由系统回填，这段没有生成，"
                        + "【一】的 ✍️ 那栏照旧你自己写。");
                return vo;
            }
        }
        vo.setDraft(TokenFiller.fill(raw, facts));
        vo.getWarnings().addAll(NumberGuard.warnings(raw));
        return vo;
    }

    /** 发一次，失败就把原因写进返回值并返回 {@code null}——调用方据此收摊。 */
    private String call(ReviewAiPrompt.Prompt prompt, ReviewAiDraftVO vo) {
        try {
            return llm.complete(prompt.system, prompt.user);
        } catch (LlmException e) {
            vo.setMessage("草稿没生成：" + e.getMessage());
            return null;
        }
    }

    private static List<String> violations(String raw, Map<String, String> facts) {
        List<String> out = new ArrayList<String>(NumberGuard.violations(raw));
        Set<String> unknown = TokenFiller.unknownTokens(raw, facts);
        if (!unknown.isEmpty()) {
            out.add(TokenFiller.unknownMessage(unknown));
        }
        return out;
    }

    /** 一句拦下的理由；{@code null} = 这份产出干净。 */
    private static String violationNote(String raw, Map<String, String> facts) {
        List<String> v = violations(raw, facts);
        return v.isEmpty() ? null : String.join(" / ", v);
    }

    /**
     * 回灌给模型的纠正要求。只报违规片段，绝不把那个数念回去——念回去它照着抄就行，
     * 那等于系统自己把一个编造的数又发了一遍。
     */
    private static String correction(List<String> violations) {
        return "你的产出违规：" + String.join(" / ", violations)
                + "。数字一律由系统回填，你不许写出任何数字，也不许造新的令牌："
                + "要引用数值就原样写回给你的那些 {令牌}，没在列表里的就不要提。";
    }
}
