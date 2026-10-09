package com.emotion.controller;

import com.emotion.dto.NodeAdoptRequest;
import com.emotion.entity.NodeEvent;
import com.emotion.service.NodeBreakService;
import com.emotion.service.NodeService;
import com.emotion.service.NodeSuggestService;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.BreakNodeCreateVO;
import com.emotion.vo.NodeSuggestVO;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 节点事件。
 *
 * <p>「待验证 → 有效/失效」这一步只能由他点：{@code /suggest} 把平台按盘面明细复算的结论连来路一起
 * 交出去，{@code /adopt} 才落库，中间没有任何自动写入。判据不齐时 suggest 会直接说"判不了"，
 * 而不是给一个看起来正常的数。
 * <p>节点全平台共享一份，谁能改由 {@code SuperAdminWriteInterceptor} 统一挡。
 */
@RestController
@RequestMapping("/api/nodes")
public class NodeController {

    private final NodeService nodeService;
    private final NodeSuggestService nodeSuggestService;
    private final NodeBreakService nodeBreakService;

    public NodeController(NodeService nodeService, NodeSuggestService nodeSuggestService,
                          NodeBreakService nodeBreakService) {
        this.nodeService = nodeService;
        this.nodeSuggestService = nodeSuggestService;
        this.nodeBreakService = nodeBreakService;
    }

    @GetMapping
    public ApiResponse<List<com.emotion.vo.NodeVO>> list() {
        return ApiResponse.ok(nodeService.listAll());
    }

    @GetMapping("/current")
    public ApiResponse<com.emotion.vo.NodeVO> getCurrent() {
        return ApiResponse.ok(nodeService.getCurrent());
    }

    @PostMapping
    public ApiResponse<com.emotion.vo.NodeVO> create(@RequestBody NodeEvent event) {
        return ApiResponse.ok(nodeService.create(event));
    }

    @PutMapping("/{id}")
    public ApiResponse<NodeEvent> update(@PathVariable Long id, @RequestBody NodeEvent event) {
        return ApiResponse.ok(nodeService.update(id, event));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        nodeService.deleteNode(id);
        return ApiResponse.ok(null);
    }

    /**
     * 把曲线上那颗 ☆/★ 立成节点：试探日落一行，成功日连前一天那次试探一起落两行。
     * 判定读的是 {@link com.emotion.service.TiantiService#breakDay}，页面不自己判、也不自己拼字段。
     */
    @PostMapping("/break")
    public ApiResponse<BreakNodeCreateVO> createBreak(@RequestParam String date) {
        return ApiResponse.ok(nodeBreakService.createNodes(parseDate(date)));
    }

    /** 「从今日天梯新增节点」的轻量预填：D0日期/涨停跌停家数/最高板/今日龙头候选。只读本地表。 */
    @GetMapping("/ladder-intel")
    public ApiResponse<com.emotion.vo.NodePrefillVO> ladderIntel(
            @RequestParam(required = false) String date) {
        return ApiResponse.ok(nodeService.ladderIntel(parseDate(date)));
    }

    /** 日期参数：留空按 null（各接口自己决定默认），格式不对给中文提示，不吐堆栈。 */
    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd，收到：" + raw);
        }
    }

    /**
     * 复算一遍：建议是什么、哪几条读数、缺哪一样，全部摊开。只读，不写库。 */
    @GetMapping("/{id}/suggest")
    public ApiResponse<NodeSuggestVO> suggest(@PathVariable Long id) {
        return ApiResponse.ok(nodeSuggestService.suggest(id));
    }

    /** 采纳：服务端重算并逐字段比对，一致才把八个字段连状态来路一起写进去。 */
    @PostMapping("/{id}/adopt")
    public ApiResponse<NodeEvent> adopt(@PathVariable Long id, @RequestBody NodeAdoptRequest body) {
        return ApiResponse.ok(nodeSuggestService.adopt(id,
                body == null ? null : body.getFingerprint()));
    }
}
