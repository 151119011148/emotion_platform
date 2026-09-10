package com.wuwei.controller;

import com.wuwei.service.NodeQueryService;
import com.wuwei.vo.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** P5 节点演变 */
@RestController
@RequestMapping("/api/node")
public class NodeController {

    private final NodeQueryService service;

    public NodeController(NodeQueryService service) {
        this.service = service;
    }

    @GetMapping("/history")
    public ApiResponse<Map<String, Object>> history() {
        return ApiResponse.ok(service.history());
    }
}
