package com.emotion.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emotion.entity.ScoringDim;
import com.emotion.entity.ScoringModelConfig;
import com.emotion.entity.ScoringRule;
import com.emotion.entity.ScoringSub;
import com.emotion.service.ScoringModelService;
import com.emotion.service.ScoringModelStore;
import com.emotion.vo.ApiResponse;
import com.emotion.vo.ScoringModelVO;

/**
 * 打分配置（模型 / 维度 / 规则）的 CRUD 与生效模型读取。
 *
 * <p>{@code /effective} 是前端卡片取维度与权重的读端点（当前生效模型，装不到则 source=BUILTIN）；
 * {@code /models}/{@code /dims}/{@code /rules} 是管理页的增删改查。三张表平台全局共享，不绑用户，
 * 但任何改动都记一条审计日志（谁改的），以备将来排查。
 */
@RestController
@RequestMapping("/api/scoring")
public class ScoringModelController {

    private static final Logger log = LoggerFactory.getLogger(ScoringModelController.class);

    private final ScoringModelService service;
    private final ScoringModelStore store;

    public ScoringModelController(ScoringModelService service, ScoringModelStore store) {
        this.service = service;
        this.store = store;
    }

    /** 当前生效模型的维度/权重/规则视图，供前端卡片渲染。永远 200：读不到就 source=BUILTIN。 */
    @GetMapping("/effective")
    public ApiResponse<ScoringModelVO> effective() {
        return ApiResponse.ok(store.effective());
    }

    @GetMapping("/models")
    public ApiResponse<List<ScoringModelConfig>> listModels() {
        return ApiResponse.ok(service.listModels());
    }

    @GetMapping("/models/{id}")
    public ApiResponse<ScoringModelVO> modelDetail(@PathVariable Long id) {
        return ApiResponse.ok(service.modelDetail(id));
    }

    @PostMapping("/models")
    public ApiResponse<ScoringModelConfig> createModel(Authentication auth, @RequestBody ScoringModelConfig body) {
        ScoringModelConfig created = service.createModel(body);
        log.info("打分配置审计 user={} 新建模型 id={} key={}", userId(auth), created.getId(), created.getModelKey());
        return ApiResponse.ok(created);
    }

    @PutMapping("/models/{id}")
    public ApiResponse<ScoringModelConfig> updateModel(Authentication auth, @PathVariable Long id,
                                                       @RequestBody ScoringModelConfig body) {
        ScoringModelConfig updated = service.updateModel(id, body);
        log.info("打分配置审计 user={} 改模型 id={}", userId(auth), id);
        return ApiResponse.ok(updated);
    }

    @DeleteMapping("/models/{id}")
    public ApiResponse<Void> deleteModel(Authentication auth, @PathVariable Long id) {
        service.deleteModel(id);
        log.info("打分配置审计 user={} 删模型 id={}（级联删维度/规则）", userId(auth), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/models/{id}/activate")
    public ApiResponse<ScoringModelConfig> activate(Authentication auth, @PathVariable Long id) {
        ScoringModelConfig activated = service.activate(id);
        log.info("打分配置审计 user={} 激活模型 id={} key={}", userId(auth), id, activated.getModelKey());
        return ApiResponse.ok(activated);
    }

    @PostMapping("/dims")
    public ApiResponse<ScoringDim> createDim(Authentication auth, @RequestBody ScoringDim body) {
        ScoringDim created = service.createDim(body);
        log.info("打分配置审计 user={} 新建维度 model={} key={}", userId(auth), created.getModelId(), created.getDimKey());
        return ApiResponse.ok(created);
    }

    @PutMapping("/dims/{id}")
    public ApiResponse<ScoringDim> updateDim(Authentication auth, @PathVariable Long id,
                                             @RequestBody ScoringDim body) {
        ScoringDim updated = service.updateDim(id, body);
        log.info("打分配置审计 user={} 改维度 id={} key={} weight={}", userId(auth), id, updated.getDimKey(), updated.getWeight());
        return ApiResponse.ok(updated);
    }

    @DeleteMapping("/dims/{id}")
    public ApiResponse<Void> deleteDim(Authentication auth, @PathVariable Long id) {
        service.deleteDim(id);
        log.info("打分配置审计 user={} 删维度 id={}（级联删该维规则）", userId(auth), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/rules")
    public ApiResponse<ScoringRule> createRule(Authentication auth, @RequestBody ScoringRule body) {
        ScoringRule created = service.createRule(body);
        log.info("打分配置审计 user={} 新建规则 model={} dim={}#{}", userId(auth),
                created.getModelId(), created.getDimKey(), created.getRuleNo());
        return ApiResponse.ok(created);
    }

    @PutMapping("/rules/{id}")
    public ApiResponse<ScoringRule> updateRule(Authentication auth, @PathVariable Long id,
                                               @RequestBody ScoringRule body) {
        ScoringRule updated = service.updateRule(id, body);
        log.info("打分配置审计 user={} 改规则 id={}", userId(auth), id);
        return ApiResponse.ok(updated);
    }

    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(Authentication auth, @PathVariable Long id) {
        service.deleteRule(id);
        log.info("打分配置审计 user={} 删规则 id={}", userId(auth), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/subs")
    public ApiResponse<ScoringSub> createSub(Authentication auth, @RequestBody ScoringSub body) {
        ScoringSub created = service.createSub(body);
        log.info("打分配置审计 user={} 新建子指标 model={} dim={} sub={}", userId(auth),
                created.getModelId(), created.getDimKey(), created.getSubKey());
        return ApiResponse.ok(created);
    }

    @PutMapping("/subs/{id}")
    public ApiResponse<ScoringSub> updateSub(Authentication auth, @PathVariable Long id,
                                             @RequestBody ScoringSub body) {
        ScoringSub updated = service.updateSub(id, body);
        log.info("打分配置审计 user={} 改子指标 id={} weight={}", userId(auth), id, updated.getWeight());
        return ApiResponse.ok(updated);
    }

    @DeleteMapping("/subs/{id}")
    public ApiResponse<Void> deleteSub(Authentication auth, @PathVariable Long id) {
        service.deleteSub(id);
        log.info("打分配置审计 user={} 删子指标 id={}（级联删该 sub 的 ladder 与子层）", userId(auth), id);
        return ApiResponse.ok(null);
    }

    private static Long userId(Authentication auth) {
        return auth == null ? null : (Long) auth.getPrincipal();
    }
}
