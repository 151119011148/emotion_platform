package com.emotion.controller;

import com.emotion.dto.NodeAdoptRequest;
import com.emotion.entity.NodeEvent;
import com.emotion.service.NodeService;
import com.emotion.service.NodeSuggestService;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.NodeSuggestVO;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 节点事件。
 *
 * <p>「待验证 → 有效/失效」这一步只能由他点：{@code /suggest} 把平台按盘面明细复算的结论连来路一起
 * 交出去，{@code /adopt} 才落库，中间没有任何自动写入。判据不齐时 suggest 会直接说"判不了"，
 * 而不是给一个看起来正常的数。
 */
@RestController
@RequestMapping("/api/nodes")
public class NodeController {

    private final NodeService nodeService;
    private final NodeSuggestService nodeSuggestService;

    public NodeController(NodeService nodeService, NodeSuggestService nodeSuggestService) {
        this.nodeService = nodeService;
        this.nodeSuggestService = nodeSuggestService;
    }

    @GetMapping
    public ApiResponse<List<NodeEvent>> list(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(nodeService.listByUser(userId));
    }

    @GetMapping("/current")
    public ApiResponse<NodeEvent> getCurrent(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(nodeService.getCurrent(userId));
    }

    @PostMapping
    public ApiResponse<NodeEvent> create(Authentication auth, @RequestBody NodeEvent event) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(nodeService.create(userId, event));
    }

    @PutMapping("/{id}")
    public ApiResponse<NodeEvent> update(Authentication auth, @PathVariable Long id,
                                         @RequestBody NodeEvent event) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(nodeService.update(userId, id, event));
    }

    /** 复算一遍：建议是什么、哪几条读数、缺哪一样，全部摊开。只读，不写库。 */
    @GetMapping("/{id}/suggest")
    public ApiResponse<NodeSuggestVO> suggest(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(nodeSuggestService.suggest(userId, id));
    }

    /** 采纳：服务端重算并逐字段比对，一致才把八个字段连状态来路一起写进去。 */
    @PostMapping("/{id}/adopt")
    public ApiResponse<NodeEvent> adopt(Authentication auth, @PathVariable Long id,
                                       @RequestBody NodeAdoptRequest body) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(nodeSuggestService.adopt(userId, id,
                body == null ? null : body.getFingerprint()));
    }
}
