package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.emotion.ai.LlmClient;
import com.emotion.ai.LlmException;
import com.emotion.entity.DailyRecord;
import com.emotion.util.ReviewDocFormatter;
import com.emotion.util.ReviewDocMetrics;
import com.emotion.vo.ReviewAiDraftVO;

/**
 * AI 草稿这一段流程：数从哪来、模型能看见什么、违规了怎么走。
 *
 * <p>这里最该被钉住的不是"草稿写得好不好"，而是那三条不能被日后顺手改掉的规矩：
 * <ol>
 *   <li>发给模型的文本里<b>一个阿拉伯数字都没有</b>——它看不见数才编不出数；</li>
 *   <li>它写出的数（或没给过的令牌）只能<b>整条拦掉</b>，不能挑能看的留下；</li>
 *   <li>整个过程<b>一次库都不写</b>：草稿只活在返回值里，✍️ 那栏与留痕无关。</li>
 * </ol>
 * 第 3 条由构造保证：本服务只有读，测试里 mock 的取数入口也只被调用一次读。
 */
class ReviewAiDraftServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    /** 记下发出去过什么，并按队列给回复；队列空了就把最后一个重复给。 */
    private static final class FakeLlm implements LlmClient {
        final List<String[]> sent = new ArrayList<String[]>();
        List<String> replies = new ArrayList<String>();
        boolean fail;

        @Override
        public String modelName() {
            return "fake-model";
        }

        @Override
        public String complete(String system, String user) {
            sent.add(new String[]{system, user});
            if (fail) {
                throw new LlmException("千问没接通（测试里假装的）");
            }
            return replies.size() == 1 ? replies.get(0) : replies.remove(0);
        }
    }

    private static ReviewDocFormatter.Model model() {
        DailyRecord today = new DailyRecord();
        today.setTradeDate(DAY);
        today.setTemperature(new BigDecimal("52.8"));
        today.setTotalScore(53);
        today.setScoredDims(5);
        today.setStage("混沌");
        today.setStageSeq(1);
        today.setTotalVolume(new BigDecimal("14380.18"));
        today.setUpCount(2393);
        today.setDownCount(2730);
        today.setLimitUpCount(52);
        today.setLimitDownCount(9);
        today.setMaxConsecutiveLimit(7);
        today.setMainTheme("新能源车");
        today.setLeadingStock("襄阳轴承");
        today.setLeadingStockStatus("加速");

        DailyRecord prev = new DailyRecord();
        prev.setTradeDate(DAY.minusDays(1));
        prev.setTemperature(new BigDecimal("59.7"));
        prev.setLimitUpCount(57);
        prev.setLimitDownCount(10);
        prev.setUpCount(3315);
        prev.setDownCount(1821);

        ReviewDocFormatter.Model m = new ReviewDocFormatter.Model();
        m.date = DAY;
        m.today = today;
        m.prev = prev;
        m.nextDate = LocalDate.of(2026, 10, 8);
        m.poolZt = 52;
        m.poolZb = 12;
        m.metrics = ReviewDocMetrics.from(today, prev, 52, 12, null, 6);
        return m;
    }

    private static ReviewAiDraftService service(ReviewDocFormatter.Model m, FakeLlm llm) {
        ReviewExportService export = mock(ReviewExportService.class);
        when(export.docModel(anyLong(), any(LocalDate.class))).thenReturn(m);
        return new ReviewAiDraftService(export, llm);
    }

    @Test
    void cleanDraftComesBackWithSystemNumbersFilledIn() {
        FakeLlm llm = new FakeLlm();
        llm.replies.add("温度 {温度}，成交 {成交额}，比 {昨温度} 那期是往下走的。");
        ReviewAiDraftVO vo = service(model(), llm).draft(2L, DAY);

        assertEquals(1, llm.sent.size());
        assertFalse(llm.sent.get(0)[0].matches("(?s).*[0-9０-９].*"), "system 里出现了数字");
        assertFalse(llm.sent.get(0)[1].matches("(?s).*[0-9０-９].*"), "user 里出现了数字：" + llm.sent.get(0)[1]);
        assertEquals("温度 52.8°，成交 14380.18 亿，比 59.7° 那期是往下走的。", vo.getDraft());
        assertFalse(vo.isBlocked());
        assertEquals("fake-model", vo.getModel());
        assertEquals("52.8°", vo.getFacts().get("温度"));
    }

    @Test
    void aDigitFromTheModelIsRetriedOnceAndAcceptedWhenClean() {
        FakeLlm llm = new FakeLlm();
        llm.replies = new ArrayList<String>(Arrays.asList(
                "温度只有 52.8 度，谈不上修复。",
                "温度 {温度}，广度还没跟上。"));
        ReviewAiDraftVO vo = service(model(), llm).draft(2L, DAY);

        assertEquals(2, llm.sent.size());
        assertEquals("温度 52.8°，广度还没跟上。", vo.getDraft());
        assertTrue(vo.getWarnings().stream().anyMatch(w -> w.contains("第一轮产出违规")),
                vo.getWarnings().toString());
    }

    @Test
    void retryThatStillWritesADigitKillsTheWholeDraft() {
        FakeLlm llm = new FakeLlm();
        llm.replies.add("温度只有 52.8 度，谈不上修复。");
        ReviewAiDraftVO vo = service(model(), llm).draft(2L, DAY);

        assertEquals(2, llm.sent.size());
        assertTrue(vo.isBlocked());
        assertNull(vo.getDraft());
        assertTrue(vo.getMessage().contains("草稿被拦"), vo.getMessage());
    }

    /** 造一个系统没给过的令牌，和造一个数的性质一样：来路不明的东西不进文档。 */
    @Test
    void inventedTokenIsBlocked() {
        FakeLlm llm = new FakeLlm();
        llm.replies.add("主力 {主力动向} 还在观望。");
        ReviewAiDraftVO vo = service(model(), llm).draft(2L, DAY);

        assertTrue(vo.isBlocked());
        assertTrue(vo.getMessage().contains("系统没给的令牌"), vo.getMessage());
        assertTrue(vo.getMessage().contains("主力动向"), vo.getMessage());
    }

    @Test
    void tooFewReadingsMeansNoCallAtAll() {
        ReviewDocFormatter.Model bare = new ReviewDocFormatter.Model();
        bare.date = DAY;
        FakeLlm llm = new FakeLlm();
        llm.replies.add("不该被用到");
        ReviewAiDraftVO vo = service(bare, llm).draft(2L, DAY);

        assertTrue(llm.sent.isEmpty(), "读数太少还打了模型");
        assertNull(vo.getDraft());
        assertTrue(vo.getMessage().contains("读数太少"), vo.getMessage());
    }

    @Test
    void upstreamFailureIsAPlainMessageNotAnException() {
        FakeLlm llm = new FakeLlm();
        llm.fail = true;
        llm.replies.add("");
        ReviewAiDraftVO vo = service(model(), llm).draft(2L, DAY);

        assertFalse(vo.isBlocked());
        assertNull(vo.getDraft());
        assertTrue(vo.getMessage().startsWith("草稿没生成："), vo.getMessage());
    }
}
