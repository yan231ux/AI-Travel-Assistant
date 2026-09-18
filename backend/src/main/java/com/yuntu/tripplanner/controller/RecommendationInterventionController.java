package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.RecommendationInterventionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 推荐人工干预控制器（/admin/recommendations/interventions：设计方案 §6.3）。
 *
 * <p>管理端登记低风险运营干预：景点置顶/降权/推荐黑名单 + 城市精选，
 * 全部带原因与生效窗口；服务端要求 RECOMMEND_OPS 权限，每次保存/删除写审计
 * （recommendation_intervened / recommendation_intervention_removed）。
 * 干预不在推荐流接口外暴露任何写入口——消费由 RecommendationFeedService 内部读取。
 */
@Slf4j
@RestController
@RequestMapping("/admin/recommendations/interventions")
public class RecommendationInterventionController {

    private final RecommendationInterventionService interventionService;

    public RecommendationInterventionController(RecommendationInterventionService interventionService) {
        this.interventionService = interventionService;
    }

    /** 干预列表（scope=ALL/ACTIVE/SCHEDULED/EXPIRED；action/targetType 可选过滤） */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", interventionService.page(UserContext.getUserId(), action, targetType,
                scope, page, pageSize));
        return ResponseEntity.ok(body);
    }

    /** 新建/覆盖保存（同对象同动作 = 覆盖窗口与原因） */
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> req = body == null ? Map.of() : body;
        Map<String, Object> data = interventionService.save(UserContext.getUserId(), req);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "updated".equals(data.get("mode"))
                ? "已更新该干预（同对象同动作覆盖窗口/原因）" : "干预已生效（作用于推荐流排序层，不影响算法分数）");
        resp.put("data", data);
        return ResponseEntity.ok(resp);
    }

    /** 删除干预（即时不再生效，审计留痕） */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable Long id) {
        interventionService.remove(UserContext.getUserId(), id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "干预已移除，推荐流恢复算法排序");
        return ResponseEntity.ok(body);
    }
}
